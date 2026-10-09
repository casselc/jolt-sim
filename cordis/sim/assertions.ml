type kind = Always | Sometimes | Unreachable

type entry = {
  kind : kind;
  mutable evaluated : int;
  mutable hits : int;  (** true results (sometimes/reachable), or violations *)
}

type t = {
  table : (string, entry) Hashtbl.t;
  mutable order : string list;
  mutable runs : int;
  mutable setup : bool;
  mutable first_failure : string option;
}

let create () =
  { table = Hashtbl.create 16; order = []; runs = 0; setup = false; first_failure = None }

let entry t kind msg =
  match Hashtbl.find_opt t.table msg with
  | Some e -> e
  | None ->
      let e = { kind; evaluated = 0; hits = 0 } in
      Hashtbl.add t.table msg e;
      t.order <- msg :: t.order;
      e

let fail t msg = if t.first_failure = None then t.first_failure <- Some msg

let always t cond msg =
  let e = entry t Always msg in
  e.evaluated <- e.evaluated + 1;
  if not cond then (e.hits <- e.hits + 1; fail t msg)

let always_or_unreachable = always

let sometimes t cond msg =
  let e = entry t Sometimes msg in
  e.evaluated <- e.evaluated + 1;
  if cond then e.hits <- e.hits + 1

let reachable t msg = sometimes t true msg

let unreachable t msg =
  let e = entry t Unreachable msg in
  e.evaluated <- e.evaluated + 1;
  e.hits <- e.hits + 1;
  fail t msg

let setup_complete t = t.setup <- true
let declare_witness t msg = ignore (entry t Sometimes msg)
let setup_done t = t.setup
let runs t = t.runs
let end_run t = t.runs <- t.runs + 1
let first_failure t = t.first_failure

type line = Fail of string | Warn of string | Ok_ of string
type verdict = { lines : line list; failed : bool }

let verdict t =
  let lines =
    List.rev_map
      (fun msg ->
        let e = Hashtbl.find t.table msg in
        match e.kind with
        | Always ->
            if e.hits > 0 then Fail (Printf.sprintf "always violated %d/%d: %s" e.hits e.evaluated msg)
            else if e.evaluated = 0 then Warn (Printf.sprintf "always never evaluated: %s" msg)
            else Ok_ (Printf.sprintf "always held %d times: %s" e.evaluated msg)
        | Sometimes ->
            if e.hits = 0 then Warn (Printf.sprintf "witness not reached (a coverage gap, not a pass): %s" msg)
            else Ok_ (Printf.sprintf "witness reached %d times: %s" e.hits msg)
        | Unreachable ->
            if e.hits > 0 then Fail (Printf.sprintf "unreachable reached %d times: %s" e.hits msg)
            else Ok_ (Printf.sprintf "unreachable never reached: %s" msg))
      t.order
  in
  let lines = if t.runs = 0 then Fail "no runs: an empty campaign is not a pass" :: lines else lines in
  { lines; failed = List.exists (function Fail _ -> true | _ -> false) lines }

let print_verdict ppf v =
  List.iter
    (function
      | Fail s -> Format.fprintf ppf "FAIL %s@." s
      | Warn s -> Format.fprintf ppf "WARN %s@." s
      | Ok_ s -> Format.fprintf ppf "ok   %s@." s)
    v.lines
