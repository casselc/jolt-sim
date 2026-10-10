(* The turn runtime's core (docs/cordis/rfc/0006-turn-runtime.md, revision
   2: decision 1A, stage 1 of the slice), sans-IO.

   It holds one instance of a checked graph: the inputs, the streams, and a
   slot per asset with its value, its revision, the revisions it was
   computed from, its cell's state and its fiber. It performs no IO, reads
   no clock and makes no choice: the host asks [ready] for the enabled
   internal actions and [perform]s the one it picks (the schedule is the
   host's oracle), feeds external [event]s to [handle], and carries out the
   [command]s both return.

   What it implements, and the model each part answers to:
   - recomputation (quint/routes.qnt): an asset is stale when an input,
     stream or asset it reads has a revision other than the one it was
     computed from; a guarded asset computes only while its guard holds and
     is withdrawn when it stops holding; a route's append is keyed by the
     scrutinee's computation (the episode, and which computation of the
     scrutinee), so it happens once per computation however often the
     route runs, even when a new computation gives the same value (a critic
     that repeats itself), and a full stream refuses it; a new input starts
     a new episode and empties the streams;
   - turns (quint/engine.qnt): each step that changes durable state appends
     one record; an intent is released only once its record is durable (the
     output gate); a receipt applies only to the fiber awaiting its key and
     attempt, so a redelivered or stale receipt, or one from an attempt
     already abandoned, changes nothing (the fiber's awaited key and
     attempt are the dedup; quint/observe.qnt's applyLate);
   - lost receipts (quint/observe.qnt, quint/lifecycle.qnt): an
     observational intent is attempted again, up to a bound, and then the
     computation fails; an effectful one makes the fiber Uncertain, which
     only a reconcile answer moves on.

   The kernel (cordis/kernel) is the control plane for installing and
   retiring fibers across revisions (cutover, stage 5); within one
   revision, recomputation is value-level, which the int-valued kernel does
   not model, so this core does not step it. Graph.Compile still checks the
   compiled shape at admission. *)

module V = Cordis_cel.Value
module Check = Cordis_graph.Check
module Smap = Map.Make (String)

type cls = Observational | Effectful

type intent = { key : string; kind : string; request : V.t; cls : cls; attempt : int }

type receipt = Done of V.t | Lost

type fiber =
  | Ready
  | Running of { turn : int; args : (string * V.t) list; receipts : (string * V.t) list; basis : (string * int) list }
  | Awaiting of { turn : int; args : (string * V.t) list; receipts : (string * V.t) list; basis : (string * int) list; intent : intent }
  | Uncertain of { turn : int; args : (string * V.t) list; receipts : (string * V.t) list; basis : (string * int) list; intent : intent }
  | Failed of string

type slot = {
  value : V.t option;
  rev : int;
  seen : (string * int) list;  (** the revision of each dependency it was computed (or withdrawn) from *)
  state : V.t;  (** the cell's state, a map *)
  computes : int;  (** computations begun: part of every intent key *)
  fiber : fiber;
}

type source = { svalue : V.t option; srev : int }

(* A record is the durable form of one step: the facts it changed. *)
type fact =
  | Slot of string * slot
  | Input of string * source
  | Stream of string * V.t list * int
  | Answered of string * int * int  (** scrutinee, episode, computation *)
  | Episode of int

type record = { seq : int; facts : fact list }

type command =
  | Persist of record
  | Release of intent  (** hand to the outbox: its record is durable *)
  | Reconcile of string * int  (** ask the outbox whether this key (attempt) took effect *)

type event =
  | Set_input of string * V.t
  | Durable of int  (** every record up to this sequence number is durable *)
  | Receipt of string * int * receipt  (** key, attempt, what came back *)
  | Reconciled of string * int * V.t option  (** key, attempt; the receipt, if the intent took effect *)

type action =
  | Compute of string  (** begin computing a stale asset *)
  | Withdraw of string  (** a guard or a dependency no longer holds *)
  | Turn of string  (** evaluate the running fiber's next turn *)
  | Append of string * string  (** run a route's append for the scrutinee's current revision, into the stream *)

type t = {
  g : Check.graph_info;
  cells : Check.cell_info Smap.t;  (** by asset *)
  registry : Cordis_cel.Proto.registry;
  classes : string -> cls;
  max_attempts : int;
  inputs : source Smap.t;
  streams : (V.t list * int) Smap.t;
  slots : slot Smap.t;
  answered : (string * int * int) list;
  episode : int;
  seq : int;  (** the last record appended *)
  durable : int;
  gated : (int * intent) list;  (** intents waiting for their record to be durable *)
  refused : int;  (** appends a full stream refused *)
}

let action_name = function
  | Compute a -> "compute " ^ a
  | Withdraw a -> "withdraw " ^ a
  | Turn a -> "turn " ^ a
  | Append (s, st) -> Printf.sprintf "append %s -> %s" s st

(* ---- creation ------------------------------------------------------------------------------------- *)

exception Engine_error of string

let err fmt = Printf.ksprintf (fun m -> raise (Engine_error m)) fmt

let eval_init registry (e : Cordis_cel.Ast.expr option) =
  match e with
  | None -> err "a state field's initial value is a hole"
  | Some e -> (
      match Cordis_cel.Proto.with_registry registry (fun () -> Cordis_cel.Eval.run e) with
      | { value = V.Error m; _ } -> err "a state initial value: %s" m
      | { value; _ } -> value)

let create ?(classes = fun _ -> Effectful) ?(max_attempts = 3) (checked : Check.checked) graph =
  let g = match List.find_opt (fun (g : Check.graph_info) -> g.gname = graph) checked.graphs with Some g -> g | None -> err "no graph %s" graph in
  let cell name = match List.find_opt (fun (c : Check.cell_info) -> c.cname = name) checked.cells with Some c -> c | None -> err "no cell %s" name in
  let cells = List.fold_left (fun m (a : Check.asset_info) -> Smap.add a.aname (cell a.acell) m) Smap.empty g.assets in
  let slots =
    List.fold_left
      (fun m (a : Check.asset_info) ->
        let c = Smap.find a.aname cells in
        let state = V.make_map (List.map (fun (f, e) -> (V.String f, eval_init checked.registry e)) c.inits) in
        Smap.add a.aname { value = None; rev = 0; seen = []; state; computes = 0; fiber = Ready } m)
      Smap.empty g.assets
  in
  { g; cells; registry = checked.registry; classes; max_attempts;
    inputs = List.fold_left (fun m (n, _) -> Smap.add n { svalue = None; srev = 0 } m) Smap.empty g.inputs;
    streams = List.fold_left (fun m (n, _) -> Smap.add n ([], 0) m) Smap.empty g.streams;
    slots; answered = []; episode = 0; seq = 0; durable = 0; gated = []; refused = 0 }

(* ---- reading -------------------------------------------------------------------------------------- *)

let asset_info t a = List.find (fun (x : Check.asset_info) -> x.aname = a) t.g.assets

(* the current value and revision of an input, stream or asset *)
let current t name =
  match Smap.find_opt name t.inputs with
  | Some s -> Some (s.svalue, s.srev)
  | None -> (
      match Smap.find_opt name t.streams with
      | Some (vs, r) -> Some (Some (V.List (Array.of_list vs)), r)
      | None -> Option.map (fun s -> (s.value, s.rev)) (Smap.find_opt name t.slots))

let value t name = Option.join (Option.map fst (current t name))

let rev t name = match current t name with Some (_, r) -> r | None -> err "unknown name %s" name

let tag v = match v with V.Map m -> (match V.map_find m (V.String "tag") with Some (V.String s) -> Some s | _ -> None) | _ -> None

let explicit t s = Option.value (List.assoc_opt s t.g.route_cases) ~default:[]

let case_matches t s case v =
  match tag v with
  | None -> false
  | Some tg -> if case = "_" then not (List.mem tg (explicit t s)) else tg = case

(* everything an asset's computation depends on: its arguments and its guards' scrutinees *)
let deps t a =
  let ai = asset_info t a in
  List.sort_uniq compare (ai.args @ List.map fst ai.guards)

let fiber_busy = function Running _ | Awaiting _ | Uncertain _ -> true | Ready | Failed _ -> false

(* stale: a dependency's revision moved since it was computed, or a
   dependency is itself stale or computing (routes.qnt's verdictStale is
   draftStale or ...): a value is current only if everything beneath it is *)
let rec stale t a =
  let s = Smap.find a t.slots in
  List.exists
    (fun d ->
      List.assoc_opt d s.seen <> Some (rev t d)
      || match Smap.find_opt d t.slots with Some ds -> fiber_busy ds.fiber || stale t d | None -> false)
    (deps t a)

(* whether the asset may hold a value now: every dependency available, and
   every guard's scrutinee current and in the case *)
let enabled t a =
  let ai = asset_info t a in
  List.for_all (fun d -> value t d <> None) ai.args
  && List.for_all
       (fun (s, case) ->
         (not (Smap.mem s t.slots && stale t s))
         && match value t s with Some v -> case_matches t s case v | None -> false)
       ai.guards
  && List.for_all (fun d -> not (Smap.mem d t.slots && stale t d)) ai.args

let ready t =
  let assets =
    List.concat_map
      (fun (ai : Check.asset_info) ->
        let a = ai.aname in
        let s = Smap.find a t.slots in
        match s.fiber with
        | Running _ -> [ Turn a ]
        | Awaiting _ | Uncertain _ | Failed _ -> []
        | Ready ->
            if not (stale t a) then []
            else if enabled t a then [ Compute a ]
            else if
              (* withdraw once its dependencies are settled, so it is not withdrawn and recomputed for nothing *)
              List.for_all (fun d -> not (Smap.mem d t.slots && (stale t d || fiber_busy (Smap.find d t.slots).fiber))) (deps t a)
            then [ Withdraw a ]
            else [])
      t.g.assets
  in
  let appends =
    List.filter_map
      (fun (s, case, stream, _, _) ->
        match Smap.find_opt s t.slots with
        | Some sl when (not (stale t s)) && sl.fiber = Ready && not (List.mem (s, t.episode, sl.computes) t.answered) -> (
            match sl.value with Some v when case_matches t s case v -> Some (Append (s, stream)) | _ -> None)
        | _ -> None)
      t.g.append_exprs
  in
  assets @ List.sort_uniq compare appends

let quiet t = ready t = [] && t.gated = [] && Smap.for_all (fun _ s -> not (fiber_busy s.fiber)) t.slots

(* ---- records -------------------------------------------------------------------------------------- *)

let record t facts =
  let seq = t.seq + 1 in
  ({ t with seq }, { seq; facts })

let with_slot t a s = { t with slots = Smap.add a s t.slots }

(* ---- turns ---------------------------------------------------------------------------------------- *)

let eval_turn t a ~turn ~args ~receipts =
  let s = Smap.find a t.slots in
  let c = Smap.find a t.cells in
  let tr = List.nth c.turns turn in
  match tr.program with
  | None -> Error (Printf.sprintf "%s's turn %d is pending (a hole)" a turn)
  | Some p -> (
      let limit = match tr.cost with Some e when e.max < Cordis_cel.Cost.inf -> Some e.max | _ -> None in
      let vars = args @ [ ("state", s.state) ] @ receipts in
      match Cordis_cel.Proto.with_registry t.registry (fun () -> Cordis_cel.Eval.run ?limit ~vars p) with
      | { value = V.Error m; _ } -> Error (Printf.sprintf "%s's turn %d: %s" a turn m)
      | { value; _ } -> Ok value)

let perform t action =
  match action with
  | Compute a ->
      let s = Smap.find a t.slots in
      let ai = asset_info t a in
      let c = Smap.find a t.cells in
      let args = List.map2 (fun (p, _) d -> (p, Option.get (value t d))) c.params ai.args in
      let basis = List.map (fun d -> (d, rev t d)) (deps t a) in
      let s = { s with computes = s.computes + 1; fiber = Running { turn = 0; args; receipts = []; basis } } in
      let t, r = record (with_slot t a s) [ Slot (a, s) ] in
      (t, [ Persist r ])
  | Withdraw a ->
      let s = Smap.find a t.slots in
      let seen = List.map (fun d -> (d, rev t d)) (deps t a) in
      let s = { s with value = None; rev = (if s.value = None then s.rev else s.rev + 1); seen } in
      let t, r = record (with_slot t a s) [ Slot (a, s) ] in
      (t, [ Persist r ])
  | Turn a -> (
      let s = Smap.find a t.slots in
      let c = Smap.find a t.cells in
      match s.fiber with
      | Running { turn; args; receipts; basis } -> (
          let last = turn = List.length c.turns - 1 in
          match eval_turn t a ~turn ~args ~receipts with
          | Error m ->
              let s = { s with fiber = Failed m } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r ])
          | Ok out when not last ->
              let kind = snd (List.nth c.uses turn) in
              let intent =
                { key = Printf.sprintf "%s/%s#%d.%d" t.g.gname a s.computes turn; kind; request = out; cls = t.classes kind; attempt = 1 }
              in
              let s = { s with fiber = Awaiting { turn; args; receipts; basis; intent } } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
          | Ok out ->
              let result, updates =
                match c.updated, out with
                | [], v -> (v, [])
                | fs, V.List xs when Array.length xs = List.length fs + 1 ->
                    (xs.(0), List.combine fs (List.tl (Array.to_list xs)))
                | _ -> err "%s's last turn gave %s" a (V.to_string out)
              in
              let state =
                match s.state with
                | V.Map m -> V.make_map (List.map (fun (k, v) -> match k with V.String f when List.mem_assoc f updates -> (k, List.assoc f updates) | _ -> (k, v)) m)
                | st -> st
              in
              let changed = match s.value with Some v -> not (V.equal v result) | None -> true in
              let s = { s with value = Some result; rev = (if changed then s.rev + 1 else s.rev); seen = basis; state; fiber = Ready } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r ]))
      | _ -> err "turn %s: not running" a)
  | Append (sc, stream) ->
      let sl = Smap.find sc t.slots in
      let v = Option.get sl.value in
      let _, case, _, binder, e =
        List.find (fun (s', case, st, _, _) -> s' = sc && st = stream && case_matches t sc case v) t.g.append_exprs
      in
      ignore case;
      let x =
        match e with
        | None -> err "route %s: the append is a hole" sc
        | Some e -> (
            let payload = match v with V.Map m -> Option.value (V.map_find m (V.String "value")) ~default:V.Null | _ -> V.Null in
            let vars = match binder with Some b -> [ (b, payload) ] | None -> [] in
            match Cordis_cel.Proto.with_registry t.registry (fun () -> Cordis_cel.Eval.run ~vars e) with
            | { value = V.Error m; _ } -> err "route %s: %s" sc m
            | { value; _ } -> value)
      in
      let vs, r = Smap.find stream t.streams in
      let cap = Option.value (List.assoc_opt stream t.g.capacities) ~default:max_int in
      let answered = (sc, t.episode, sl.computes) :: t.answered in
      if List.length vs >= cap then
        let t = { t with answered; refused = t.refused + 1 } in
        let t, rcd = record t [ Answered (sc, t.episode, sl.computes) ] in
        (t, [ Persist rcd ])
      else
        let vs = vs @ [ x ] in
        let t = { t with answered; streams = Smap.add stream (vs, r + 1) t.streams } in
        let t, rcd = record t [ Answered (sc, t.episode, sl.computes); Stream (stream, vs, r + 1) ] in
        (t, [ Persist rcd ])

(* ---- events --------------------------------------------------------------------------------------- *)

let release_durable t =
  let out, keep = List.partition (fun (seq, _) -> seq <= t.durable) t.gated in
  ({ t with gated = keep }, List.map (fun (_, i) -> Release i) out)

(* the fiber awaiting this key and attempt, if any: the one place a receipt may apply *)
let awaiting t key attempt =
  Smap.fold
    (fun a s acc ->
      match s.fiber with
      | (Awaiting { intent; _ } | Uncertain { intent; _ }) when intent.key = key && intent.attempt = attempt -> Some (a, s)
      | _ -> acc)
    t.slots None

let resume t a (s : slot) v =
  match s.fiber with
  | Awaiting { turn; args; receipts; basis; _ } | Uncertain { turn; args; receipts; basis; _ } ->
      let c = Smap.find a t.cells in
      let x = fst (List.nth c.uses turn) in
      let s = { s with fiber = Running { turn = turn + 1; args; receipts = receipts @ [ (x, v) ]; basis } } in
      let t, r = record (with_slot t a s) [ Slot (a, s) ] in
      (t, [ Persist r ])
  | _ -> (t, [])

let handle t ev =
  match ev with
  | Set_input (n, v) -> (
      match Smap.find_opt n t.inputs with
      | None -> err "no input %s" n
      | Some src when src.svalue <> None && V.equal (Option.get src.svalue) v -> (t, [])
      | Some src ->
          (* a new input starts a new episode: the streams belong to the request they were written for *)
          let src = { svalue = Some v; srev = src.srev + 1 } in
          let streams = Smap.map (fun (_, r) -> ([], r + 1)) t.streams in
          let episode = t.episode + 1 in
          let t = { t with inputs = Smap.add n src t.inputs; streams; episode } in
          let t, r =
            record t (Input (n, src) :: Episode episode :: List.map (fun (s, (vs, r)) -> Stream (s, vs, r)) (Smap.bindings streams))
          in
          (t, [ Persist r ]))
  | Durable seq -> release_durable { t with durable = max t.durable seq }
  | Receipt (key, n, Done v) -> (match awaiting t key n with Some (a, s) -> resume t a s v | None -> (t, []))
  | Receipt (key, n, Lost) -> (
      match awaiting t key n with
      | Some (a, ({ fiber = Awaiting ({ intent; _ } as w); _ } as s)) -> (
          match intent.cls with
          | Observational when intent.attempt < t.max_attempts ->
              let intent = { intent with attempt = intent.attempt + 1 } in
              let s = { s with fiber = Awaiting { w with intent } } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
          | Observational ->
              let s = { s with fiber = Failed (Printf.sprintf "%s: %d attempts lost" intent.key intent.attempt) } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r ])
          | Effectful ->
              let s = { s with fiber = Uncertain { turn = w.turn; args = w.args; receipts = w.receipts; basis = w.basis; intent } } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r; Reconcile (intent.key, intent.attempt) ]))
      | _ -> (t, []))
  | Reconciled (key, n, Some v) -> (match awaiting t key n with Some (a, s) -> resume t a s v | None -> (t, []))
  | Reconciled (key, n, None) -> (
      (* it did not take effect, and the outbox has fenced the key: send it again *)
      match awaiting t key n with
      | Some (a, ({ fiber = Uncertain u; _ } as s)) ->
          let intent = { u.intent with attempt = u.intent.attempt + 1 } in
          let s = { s with fiber = Awaiting { turn = u.turn; args = u.args; receipts = u.receipts; basis = u.basis; intent } } in
          let t, r = record (with_slot t a s) [ Slot (a, s) ] in
          ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
      | _ -> (t, []))

let failures t = Smap.fold (fun a s acc -> match s.fiber with Failed m -> (a, m) :: acc | _ -> acc) t.slots []
let stream t n = fst (Smap.find n t.streams)
let refused t = t.refused
let episode t = t.episode

(* ---- recovery ------------------------------------------------------------------------------------- *)

(* Rebuild an instance from its durable records (quint/engine.qnt's crash:
   memory is the durable state). Every intent a durable record awaits is
   released again, under the same key and attempt: the outbox answers it
   from its journal if it was sent (quint/handoff.qnt), and the fiber's
   awaited key and attempt make a second answer change nothing. Every
   Uncertain fiber is reconciled again. A fiber that was running re-runs
   its turn, which is deterministic. *)
let recover ?classes ?max_attempts (checked : Check.checked) graph (records : record list) =
  let t = create ?classes ?max_attempts checked graph in
  let apply t = function
    | Slot (a, s) -> { t with slots = Smap.add a s t.slots }
    | Input (n, src) -> { t with inputs = Smap.add n src t.inputs }
    | Stream (n, vs, r) -> { t with streams = Smap.add n (vs, r) t.streams }
    | Answered (s, e, c) -> { t with answered = (s, e, c) :: t.answered }
    | Episode e -> { t with episode = e }
  in
  let t = List.fold_left (fun t (r : record) -> List.fold_left apply { t with seq = r.seq; durable = r.seq } r.facts) t records in
  let refused = t.refused in
  let cmds =
    Smap.fold
      (fun _ s acc ->
        match s.fiber with
        | Awaiting { intent; _ } -> Release intent :: acc
        | Uncertain { intent; _ } -> Reconcile (intent.key, intent.attempt) :: acc
        | _ -> acc)
      t.slots []
  in
  ({ t with refused }, List.rev cmds)
