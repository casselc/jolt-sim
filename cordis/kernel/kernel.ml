type key = string
type fid = int

module Keys = Set.Make (String)
module Kmap = Map.Make (String)
module Fmap = Map.Make (Int)

type op = Put of int | Add of int
type stage = Provide of key * int | Op of key * op
type intent = { kind : string; ikey : string; compensate : string option }
type iteration = { stages : stage list; emit : intent option }

type component = {
  name : string;
  requires : Keys.t;
  provides : Keys.t;
  iters : iteration list;
}

type inverse =
  | Restore of fid * key * int option
  | Sub of fid * key * int
  | Compensate of intent
  | Irreversible of intent

type view = fid Kmap.t
type receipt = Definite | Failed | Lost

type life =
  | Inactive
  | Reloading of {
      rest : iteration list;
      acc : inverse list;
      committed : view;
      awaiting : intent option;
    }
  | Active of { acc : inverse list; committed : view }
  | Uncertain of {
      rest : iteration list;
      acc : inverse list;
      committed : view;
      pending : intent;
    }
  | Unloading of { acc : inverse list; committed : view; failing : bool }
  | Failed

type fiber = {
  comp : component;
  parent : fid option;
  table : int Kmap.t;
  retired : bool;
  life : life;
  episode : int;
}

type registry = fiber Fmap.t
type error = string

let empty = Fmap.empty

let committed_of = function
  | Reloading { committed; _ }
  | Active { committed; _ }
  | Uncertain { committed; _ }
  | Unloading { committed; _ } ->
      Some committed
  | Inactive | Failed -> None

let installed reg n =
  match Fmap.find_opt n reg with
  | Some f -> committed_of f.life <> None
  | None -> false

let committed reg n =
  match Fmap.find_opt n reg with Some f -> committed_of f.life | None -> None

let is_active f = match f.life with Active _ -> true | _ -> false

let provider reg k =
  Fmap.fold
    (fun n f found ->
      match found with
      | Some _ -> found
      | None -> if is_active f && Kmap.mem k f.table then Some n else None)
    reg None

let target reg n =
  match Fmap.find_opt n reg with
  | None -> None
  | Some f when f.retired -> None
  | Some f ->
      Keys.fold
        (fun k acc ->
          match acc with
          | None -> None
          | Some v -> (
              match provider reg k with
              | Some m -> Some (Kmap.add k m v)
              | None -> None))
        f.comp.requires (Some Kmap.empty)

let relied reg n =
  Fmap.exists
    (fun m f ->
      m <> n
      &&
      match committed_of f.life with
      | Some w -> Kmap.exists (fun _ p -> p = n) w
      | None -> false)
    reg

let same_view a b = Kmap.equal Int.equal a b

let target_is reg n w =
  match target reg n with Some t -> same_view t w | None -> false

let quiet reg =
  Fmap.for_all
    (fun n f ->
      match f.life with
      | Inactive -> target reg n = None
      | Active { committed; _ } -> target_is reg n committed
      | Failed -> true
      | Reloading _ | Uncertain _ | Unloading _ -> false)
    reg

let bindings reg =
  Fmap.fold
    (fun n f acc -> Kmap.fold (fun k v acc -> (n, k, v) :: acc) f.table acc)
    reg []
  |> List.sort compare

type rule = Begin | Iter | Finish | Divert | Leave | Unload

let rule_name = function
  | Begin -> "begin"
  | Iter -> "iter"
  | Finish -> "finish"
  | Divert -> "divert"
  | Leave -> "leave"
  | Unload -> "unload"

let next reg n =
  match Fmap.find_opt n reg with
  | None -> None
  | Some f -> (
      match f.life with
      | Inactive -> if target reg n <> None then Some Begin else None
      | Reloading { rest; committed; awaiting = None; _ } ->
          if not (target_is reg n committed) then Some Divert
          else if rest = [] then Some Finish
          else Some Iter
      | Reloading { awaiting = Some _; _ } ->
          (* inertial: the turn in flight lands before anything else *)
          None
      | Active { committed; _ } ->
          if target_is reg n committed then None else Some Leave
      | Uncertain _ -> None
      | Unloading _ -> if relied reg n then None else Some Unload
      | Failed -> None)

let enabled reg =
  Fmap.fold
    (fun n _ acc -> match next reg n with Some r -> (n, r) :: acc | None -> acc)
    reg []
  |> List.rev

type output = Released of fid * intent | Compensated of fid * intent

(* The table that holds a declared key for fiber [n]: its own for a provided
   key, the provider's through the committed view for a required one. *)
let owner n f committed k =
  if Keys.mem k f.comp.provides then Some n else Kmap.find_opt k committed

let get_binding reg m k =
  match Fmap.find_opt m reg with
  | Some f -> Kmap.find_opt k f.table
  | None -> None

let set_binding reg m k v =
  match Fmap.find_opt m reg with
  | None -> reg
  | Some f ->
      let table =
        match v with Some x -> Kmap.add k x f.table | None -> Kmap.remove k f.table
      in
      Fmap.add m { f with table } reg

let run_stage n committed (reg, acc) stage =
  let f = Fmap.find n reg in
  match stage with
  | Provide (k, v) ->
      let prev = get_binding reg n k in
      (set_binding reg n k (Some v), Restore (n, k, prev) :: acc)
  | Op (k, op) -> (
      match owner n f committed k with
      | None -> (reg, acc) (* unreachable for a confined component *)
      | Some m -> (
          let prev = get_binding reg m k in
          match op, prev with
          | Put v, _ -> (set_binding reg m k (Some v), Restore (m, k, prev) :: acc)
          | Add d, Some x -> (set_binding reg m k (Some (x + d)), Sub (m, k, d) :: acc)
          | Add _, None -> (reg, acc)))

let undo (reg, outs) n = function
  | Restore (m, k, prev) -> (set_binding reg m k prev, outs)
  | Sub (m, k, d) -> (
      match get_binding reg m k with
      | Some x -> (set_binding reg m k (Some (x - d)), outs)
      | None -> (reg, outs))
  | Compensate i ->
      let c = { i with kind = Option.get i.compensate; ikey = i.ikey ^ "/compensate" } in
      (reg, Compensated (n, c) :: outs)
  | Irreversible _ -> (reg, outs)

let set_life reg n life =
  let f = Fmap.find n reg in
  Fmap.add n { f with life } reg

let released_inverse i =
  match i.compensate with Some _ -> Compensate i | None -> Irreversible i

let apply reg n rule =
  match Fmap.find_opt n reg with
  | None -> Error (Printf.sprintf "no fiber %d" n)
  | Some f -> (
      if next reg n <> Some rule then
        Error (Printf.sprintf "%s is not enabled for fiber %d" (rule_name rule) n)
      else
        match rule, f.life with
        | Begin, Inactive ->
            let committed = Option.get (target reg n) in
            let reg = Fmap.add n { f with episode = f.episode + 1 } reg in
            Ok
              ( set_life reg n
                  (Reloading { rest = f.comp.iters; acc = []; committed; awaiting = None }),
                [] )
        | Iter, Reloading { rest = it :: rest; acc; committed; awaiting = None } ->
            let reg, acc = List.fold_left (run_stage n committed) (reg, acc) it.stages in
            let emit =
              Option.map (fun i -> { i with ikey = Printf.sprintf "%s@%d" i.ikey f.episode }) it.emit
            in
            let reg = set_life reg n (Reloading { rest; acc; committed; awaiting = emit }) in
            let outs = match emit with Some i -> [ Released (n, i) ] | None -> [] in
            Ok (reg, outs)
        | Finish, Reloading { rest = []; acc; committed; awaiting = None } ->
            Ok (set_life reg n (Active { acc; committed }), [])
        | Divert, Reloading { acc; committed; _ } ->
            Ok (set_life reg n (Unloading { acc; committed; failing = false }), [])
        | Leave, Active { acc; committed } ->
            Ok (set_life reg n (Unloading { acc; committed; failing = false }), [])
        | Unload, Unloading { acc; failing; _ } ->
            let reg, outs = List.fold_left (fun s inv -> undo s n inv) (reg, []) acc in
            Ok (set_life reg n (if failing then Failed else Inactive), List.rev outs)
        | _ -> Error "unreachable: next and apply disagree")

let rec check_stages comp = function
  | [] -> Ok ()
  | Provide (k, _) :: rest ->
      if Keys.mem k comp.provides then check_stages comp rest
      else Error (Printf.sprintf "%s provides undeclared key %s" comp.name k)
  | Op (k, _) :: rest ->
      if Keys.mem k comp.provides || Keys.mem k comp.requires then check_stages comp rest
      else Error (Printf.sprintf "%s operates on undeclared key %s" comp.name k)

let check_component comp =
  if not (Keys.disjoint comp.requires comp.provides) then
    Error (comp.name ^ " requires a key it provides")
  else
    List.fold_left
      (fun acc it -> match acc with Ok () -> check_stages comp it.stages | e -> e)
      (Ok ()) comp.iters

type orch =
  | Insert of fid * component * fid option
  | Retire of fid
  | Remove of fid
  | Receive of fid * receipt
  | Reconcile of fid * bool
  | Restart of fid

let orch_name = function
  | Insert _ -> "insert"
  | Retire _ -> "retire"
  | Remove _ -> "remove"
  | Receive _ -> "receive"
  | Reconcile _ -> "reconcile"
  | Restart _ -> "restart"

let ( let* ) = Result.bind

let orchestrate reg = function
  | Insert (n, comp, parent) ->
      let* () = if Fmap.mem n reg then Error (Printf.sprintf "fiber %d exists" n) else Ok () in
      let* () =
        match parent with
        | Some p when not (Fmap.mem p reg) -> Error (Printf.sprintf "no parent %d" p)
        | _ -> Ok ()
      in
      let* () =
        if Fmap.exists (fun _ g -> not (Keys.disjoint g.comp.provides comp.provides)) reg
        then Error (comp.name ^ " provides a key another fiber provides")
        else Ok ()
      in
      let* () = check_component comp in
      Ok
        (Fmap.add n
           { comp; parent; table = Kmap.empty; retired = false; life = Inactive; episode = 0 }
           reg)
  | Retire n -> (
      match Fmap.find_opt n reg with
      | None -> Error (Printf.sprintf "no fiber %d" n)
      | Some f -> Ok (Fmap.add n { f with retired = true } reg))
  | Remove n -> (
      match Fmap.find_opt n reg with
      | None -> Error (Printf.sprintf "no fiber %d" n)
      | Some f ->
          let settled = match f.life with Inactive | Failed -> true | _ -> false in
          if f.retired && settled && Kmap.is_empty f.table
             && not (Fmap.exists (fun _ g -> g.parent = Some n) reg)
          then Ok (Fmap.remove n reg)
          else Error (Printf.sprintf "fiber %d cannot be removed yet" n))
  | Receive (n, r) -> (
      match Fmap.find_opt n reg with
      | Some { life = Reloading { rest; acc; committed; awaiting = Some i }; _ } -> (
          match r with
          | Definite ->
              Ok
                (set_life reg n
                   (Reloading
                      { rest; acc = released_inverse i :: acc; committed; awaiting = None }))
          | Failed -> Ok (set_life reg n (Unloading { acc; committed; failing = true }))
          | Lost -> Ok (set_life reg n (Uncertain { rest; acc; committed; pending = i })))
      | _ -> Error (Printf.sprintf "fiber %d awaits no receipt" n))
  | Reconcile (n, took_effect) -> (
      match Fmap.find_opt n reg with
      | Some { life = Uncertain { rest; acc; committed; pending }; _ } ->
          if took_effect then
            Ok
              (set_life reg n
                 (Reloading
                    { rest; acc = released_inverse pending :: acc; committed; awaiting = None }))
          else Ok (set_life reg n (Unloading { acc; committed; failing = true }))
      | _ -> Error (Printf.sprintf "fiber %d is not uncertain" n))
  | Restart n -> (
      match Fmap.find_opt n reg with
      | Some ({ life = Failed; _ } as f) -> Ok (Fmap.add n { f with life = Inactive } reg)
      | _ -> Error (Printf.sprintf "fiber %d has not failed" n))
