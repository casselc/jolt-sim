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
     scrutinee) and by its stream, so it happens once per computation and
     stream (an arm may append to several streams) however often the
     route runs, even when a new computation gives the same value (a critic
     that repeats itself), and a full stream refuses it; a new input starts
     a new episode and empties the streams;
   - turns (quint/engine.qnt): each step that changes durable state appends
     one record; an intent is released only once its record is durable (the
     output gate); a receipt applies only to the fiber awaiting its key and
     attempt, so a redelivered or stale receipt, or one from an attempt
     already abandoned, changes nothing (the fiber's awaited key and
     attempt are the dedup; quint/observe.qnt's applyLate);
   - budgets (quint/budget.qnt): a turn that ends in an intent reserves its
     kind's worst-case cost in the same record, and does not run while the
     reservation would take the graph over its budget; a receipt settles
     at its actual cost, once; an Uncertain intent's reservation is held;
     an attempt abandoned for another is charged at its worst case
     (observe.qnt's expire), so a late answer is never under-counted;
   - cutover (quint/cutover.qnt): an admitted revision that keeps the
     graph's wiring is cut over asset by asset, at commit boundaries (an
     asset switches only with its fiber idle, so every receipt is applied
     under the revision that issued its intent), providers before
     consumers, between durable begin and end records; an unchanged cell
     keeps its value and memo, a changed one carries or resets its state
     and recomputes; a candidate is first soaked in a shadow that answers
     its intents from a cassette and emits nothing;
   - lost receipts (quint/observe.qnt, quint/lifecycle.qnt): an
     observational intent is attempted again, up to a bound, and then the
     computation fails; an effectful one makes the fiber Uncertain, which
     only a reconcile answer moves on; a keyed one (RFC 0003 revision 4:
     an HTTP PUT or DELETE, or a POST with an idempotency key) is sent
     again with the same key, which the world deduplicates, and is
     Uncertain once its sends run out (observe.qnt's keyed class).

   The kernel (cordis/kernel) is the control plane for installing and
   retiring fibers across revisions (cutover, stage 5); within one
   revision, recomputation is value-level, which the int-valued kernel does
   not model, so this core does not step it. Graph.Compile still checks the
   compiled shape at admission. *)

module V = Cordis_cel.Value
module Check = Cordis_graph.Check
module Smap = Map.Make (String)

type cls = Observational | Keyed | Effectful

type intent = { key : string; kind : string; request : V.t; cls : cls; attempt : int }

type receipt =
  | Done of V.t * int
      (** the receipt, and what it actually cost (negative: unknown, charged its worst case); for a keyed
          intent, the world says this answer covers every send of it (halo-flash's [Halo-Answer: replayed]
          or [adopted]), so the other sends are released *)
  | Generated of V.t * int * int option
      (** as [Done], but no earlier send's work is in the answer: the world generated it afresh, or did
          not say (a world that keeps no keys says nothing). For a keyed intent the other sends are
          charged: what the world says it discarded under the key, if it says (halo-flash's
          [Halo-Discarded-Tokens]), else their worst case (observe.qnt's REPORTED and DISCARDED) *)
  | Lost  (** no answer: the outcome is unknown *)
  | Refused of string  (** a definite failure (the world refused it, and it did not run): the computation fails *)

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

(* a revision of the graph: its id (the graph definition's hash, which
   covers its cells), what was checked, each cell's hash, and whether a
   cell's state carries from the revision before (Compat.state_plan) *)
type revision = { id : string; checked : Check.checked; cell_hashes : (string * string) list; carry : string -> bool }

(* A record is the durable form of one step: the facts it changed. *)
type fact =
  | Slot of string * slot
  | Input of string * source
  | Stream of string * V.t list * int
  | Answered of string * string * int * int  (** scrutinee, stream, episode, computation *)
  | Episode of int
  | Budget of int * int  (** reserved, settled *)
  | Cutover_begin of string  (** the candidate revision's id *)
  | Asset_revision of string * string  (** an asset now runs this revision *)
  | Cutover_end of string
  | Refused of string * string  (** a stream refused an append: the stream, why (full, or the value over its bounds) *)

type record = { seq : int; facts : fact list }

type command =
  | Persist of record
  | Release of intent  (** hand to the outbox: its record is durable *)
  | Reconcile of string * int  (** ask the outbox whether this key (attempt) took effect *)

type event =
  | Set_input of string * V.t
  | Durable of int  (** every record up to this sequence number is durable *)
  | Receipt of string * int * receipt  (** key, attempt, what came back *)
  | Reconciled of string * int * (V.t * int) option  (** key, attempt; the receipt and its cost, if the intent took effect *)

type action =
  | Compute of string  (** begin computing a stale asset *)
  | Withdraw of string  (** a guard or a dependency no longer holds *)
  | Turn of string  (** evaluate the running fiber's next turn *)
  | Append of string * string  (** run a route's append for the scrutinee's current revision, into the stream *)
  | Switch of string  (** cut an asset over to the candidate revision *)
  | Finish_cutover

type t = {
  g : Check.graph_info;
  cells : Check.cell_info Smap.t;  (** by asset *)
  registry : Cordis_cel.Proto.registry;
  classes : string -> cls;
  max_attempts : int;
  inputs : source Smap.t;
  streams : (V.t list * int) Smap.t;
  slots : slot Smap.t;
  answered : (string * string * int * int) list;
  episode : int;
  seq : int;  (** the last record appended *)
  durable : int;
  gated : (int * intent) list;  (** intents waiting for their record to be durable *)
  refused : int;  (** appends a full stream refused *)
  budget : int option;  (** the graph's budget, in cost units; None: unlimited *)
  worst : string -> int;  (** an intent kind's worst-case cost: policy *)
  reserved : int;
  settled : int;
  rev : string;  (** the live revision *)
  cell_hashes : (string * string) list;  (** the live revision's cells, by name *)
  asset_rev : string Smap.t;  (** the revision each asset runs *)
  cutting : revision option;  (** the candidate, between cutover-begin and cutover-end *)
}

let action_name = function
  | Compute a -> "compute " ^ a
  | Withdraw a -> "withdraw " ^ a
  | Turn a -> "turn " ^ a
  | Append (s, st) -> Printf.sprintf "append %s -> %s" s st
  | Switch a -> "switch " ^ a
  | Finish_cutover -> "finish cutover"

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

let create ?(classes = fun _ -> Effectful) ?(max_attempts = 3) ?budget ?(worst = fun _ -> 0) ?(revision = "") ?(cell_hashes = [])
    (checked : Check.checked) graph =
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
    slots; answered = []; episode = 0; seq = 0; durable = 0; gated = []; refused = 0;
    budget; worst; reserved = 0; settled = 0;
    rev = revision; cell_hashes; asset_rev = List.fold_left (fun m (a : Check.asset_info) -> Smap.add a.aname revision m) Smap.empty g.assets;
    cutting = None }

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

(* whether reserving [w] more keeps the graph within its budget *)
let fits t w = match t.budget with None -> true | Some b -> t.settled + t.reserved + w <= b

(* the intent kind the fiber's turn ends in, if it ends in one *)
let turn_kind t a turn =
  let c = Smap.find a t.cells in
  if turn < List.length c.turns - 1 then Some (snd (List.nth c.uses turn)) else None

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
        | Running { turn; _ } -> (
            (* a turn that ends in an intent runs only if its reservation fits *)
            match turn_kind t a turn with Some k when not (fits t (t.worst k)) -> [] | _ -> [ Turn a ])
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
        | Some sl when (not (stale t s)) && sl.fiber = Ready && not (List.mem (s, stream, t.episode, sl.computes) t.answered) -> (
            match sl.value with Some v when case_matches t s case v -> Some (Append (s, stream)) | _ -> None)
        | _ -> None)
      t.g.append_exprs
  in
  (* cutover: an asset switches with its fiber idle and every asset it reads switched before it *)
  let cutover =
    match t.cutting with
    | None -> []
    | Some c ->
        let pending = List.filter (fun (ai : Check.asset_info) -> Smap.find ai.aname t.asset_rev <> c.id) t.g.assets in
        if pending = [] then [ Finish_cutover ]
        else
          List.filter_map
            (fun (ai : Check.asset_info) ->
              let idle = not (fiber_busy (Smap.find ai.aname t.slots).fiber) in
              let providers_done = List.for_all (fun d -> (not (Smap.mem d t.slots)) || Smap.find d t.asset_rev = c.id) ai.args in
              if idle && providers_done then Some (Switch ai.aname) else None)
            pending
  in
  assets @ List.sort_uniq compare appends @ cutover

(* what a step costs the loop, in CEL cost units, for fairness across graphs (Sched): a turn its
   estimate's max, which admission bounded; any other step one unit *)
let step_cost t = function
  | Turn a -> (
      match (Smap.find a t.slots).fiber with
      | Running { turn; _ } -> (
          match (List.nth (Smap.find a t.cells).turns turn).cost with
          | Some e when e.max < Cordis_cel.Cost.inf -> max 1 e.max
          | _ -> 1)
      | _ -> 1)
  | _ -> 1

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
      (* an argument over its parameter's declared bounds is refused, not truncated: the cell's cost was
         estimated from them *)
      let over =
        List.find_map
          (fun (p, v) -> match List.assoc_opt p c.param_bounds with Some b -> Option.map (fun m -> p ^ ": " ^ m) (Check.exceeds b v) | None -> None)
          args
      in
      let fiber = match over with Some m -> Failed (Printf.sprintf "%s's argument %s" a m) | None -> Running { turn = 0; args; receipts = []; basis } in
      let s = { s with computes = s.computes + 1; fiber } in
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
              (* the reservation is in the same record as the intent (budget.qnt's reserveAfterCommit) *)
              let t = { (with_slot t a s) with reserved = t.reserved + t.worst kind } in
              let t, r = record t [ Slot (a, s); Budget (t.reserved, t.settled) ] in
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
              match Check.exceeds c.result_bound result with
              | Some m ->
                  (* a result over its declared bounds is refused: whatever reads it assumed them *)
                  let s = { s with fiber = Failed (Printf.sprintf "%s's result: %s" a m) } in
                  let t, r = record (with_slot t a s) [ Slot (a, s) ] in
                  (t, [ Persist r ])
              | None ->
              let changed = match s.value with Some v -> not (V.equal v result) | None -> true in
              let s = { s with value = Some result; rev = (if changed then s.rev + 1 else s.rev); seen = basis; state; fiber = Ready } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r ]))
      | _ -> err "turn %s: not running" a)
  | Switch a -> (
      match t.cutting with
      | None -> err "switch %s: no cutover under way" a
      | Some c ->
          let ai = asset_info t a in
          let nc = List.find (fun (x : Check.cell_info) -> x.cname = ai.acell) c.checked.cells in
          let unchanged = List.assoc_opt ai.acell t.cell_hashes = List.assoc_opt ai.acell c.cell_hashes && List.assoc_opt ai.acell c.cell_hashes <> None in
          let s = Smap.find a t.slots in
          let s =
            if unchanged then s (* the same code: its value and memo stand *)
            else
              let inits = List.map (fun (f, e) -> (f, eval_init c.checked.registry e)) nc.inits in
              let old = match s.state with V.Map m -> m | _ -> [] in
              let state =
                V.make_map
                  (List.map
                     (fun (f, v) -> (V.String f, if c.carry ai.acell then Option.value (V.map_find old (V.String f)) ~default:v else v))
                     inits)
              in
              (* new code: recompute; a failed computation gets another chance under it *)
              { s with state; seen = []; fiber = Ready }
          in
          let t = { t with slots = Smap.add a s t.slots; cells = Smap.add a nc t.cells; asset_rev = Smap.add a c.id t.asset_rev } in
          let t, r = record t [ Slot (a, s); Asset_revision (a, c.id) ] in
          (t, [ Persist r ]))
  | Finish_cutover -> (
      match t.cutting with
      | None -> err "finish: no cutover under way"
      | Some c ->
          let g = List.find (fun (g : Check.graph_info) -> g.gname = t.g.gname) c.checked.graphs in
          let t = { t with g; registry = c.checked.registry; rev = c.id; cell_hashes = c.cell_hashes; cutting = None } in
          let t, r = record t [ Cutover_end c.id ] in
          (t, [ Persist r ]))
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
      let answered = (sc, stream, t.episode, sl.computes) :: t.answered in
      (* a value over the stream's element bounds is refused, as a full stream refuses: the cells reading
         the stream were costed with those bounds *)
      let over = match List.assoc_opt stream t.g.stream_bounds with Some b -> Check.exceeds b x | None -> None in
      if over <> None || List.length vs >= cap then
        let why = match over with Some m -> m | None -> Printf.sprintf "full, at %d" cap in
        let t = { t with answered; refused = t.refused + 1 } in
        let t, rcd = record t [ Answered (sc, stream, t.episode, sl.computes); Refused (stream, why) ] in
        (t, [ Persist rcd ])
      else
        let vs = vs @ [ x ] in
        let t = { t with answered; streams = Smap.add stream (vs, r + 1) t.streams } in
        let t, rcd = record t [ Answered (sc, stream, t.episode, sl.computes); Stream (stream, vs, r + 1) ] in
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

(* what an intent holds reserved: its worst case, or for a keyed intent its worst case once per send
   (observe.qnt): a lost send is not charged but held until the intent is answered. A world that keeps
   keys answers a resend from the generation it holds (running, adopted within its grace window, or
   finished), whose usage the answer reports, and says so: the other sends are released. A resend after
   it stopped an unfinished original generates afresh, and the stopped part is in no answer
   (observe.qnt's graceExpiry), as is every send's work to a world that keeps no keys: then the other
   sends are charged *)
let held t (intent : intent) = match intent.cls with Keyed -> intent.attempt * t.worst intent.kind | _ -> t.worst intent.kind

let resume ?fresh t a (s : slot) v cost =
  match s.fiber with
  | Awaiting { turn; args; receipts; basis; intent } | Uncertain { turn; args; receipts; basis; intent } ->
      let c = Smap.find a t.cells in
      let x = fst (List.nth c.uses turn) in
      let s = { s with fiber = Running { turn = turn + 1; args; receipts = receipts @ [ (x, v) ]; basis } } in
      (* the reservation settles at the actual cost, once: this fiber awaits the key no longer *)
      (* a cost the world did not report is charged at the worst case, never as nothing *)
      let cost = if cost < 0 then t.worst intent.kind else cost in
      (* every send's reservation is released; a keyed intent's other sends are charged unless the answer
         covers them: what the world says it discarded (no more than their worst cases), else their worst
         cases (observe_report.qnt's graceExpiryDiscarded, observe_modes.qnt's graceExpiryReported) *)
      let others =
        match intent.cls, fresh with
        | Keyed, Some discarded ->
            let worst = (intent.attempt - 1) * t.worst intent.kind in
            (match discarded with Some d -> min worst (max 0 d) | None -> worst)
        | _ -> 0
      in
      let t = { (with_slot t a s) with reserved = t.reserved - held t intent; settled = t.settled + cost + others } in
      let t, r = record t [ Slot (a, s); Budget (t.reserved, t.settled) ] in
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
  | Receipt (key, n, Done (v, cost)) -> (match awaiting t key n with Some (a, s) -> resume t a s v cost | None -> (t, []))
  | Receipt (key, n, Generated (v, cost, discarded)) -> (
      match awaiting t key n with Some (a, s) -> resume ~fresh:discarded t a s v cost | None -> (t, []))
  | Receipt (key, n, Refused why) -> (
      (* it did not run: the reservation is released, and the computation fails *)
      match awaiting t key n with
      | Some (a, ({ fiber = Awaiting { intent; _ }; _ } as s)) ->
          let s = { s with fiber = Failed (Printf.sprintf "%s: %d refused: %s" intent.key intent.attempt why) } in
          let t = { (with_slot t a s) with reserved = t.reserved - held t intent } in
          let t, r = record t [ Slot (a, s); Budget (t.reserved, t.settled) ] in
          (t, [ Persist r ])
      | _ -> (t, []))
  | Receipt (key, n, Lost) -> (
      match awaiting t key n with
      | Some (a, ({ fiber = Awaiting ({ intent; _ } as w); _ } as s)) -> (
          (* the abandoned attempt is charged at its worst case: its late answer is never applied *)
          let wc = t.worst intent.kind in
          let charged = { t with reserved = t.reserved - wc; settled = t.settled + wc } in
          match intent.cls with
          | Keyed when intent.attempt < t.max_attempts && fits t wc ->
              (* sent again with the same key, under a reservation of its own; the lost send's is held, not
                 charged, until the intent is answered *)
              let intent = { intent with attempt = intent.attempt + 1 } in
              let s = { s with fiber = Awaiting { w with intent } } in
              let t = { (with_slot t a s) with reserved = t.reserved + wc } in
              let t, r = record t [ Slot (a, s); Budget (t.reserved, t.settled) ] in
              ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
          | Observational when intent.attempt < t.max_attempts && fits charged wc ->
              let intent = { intent with attempt = intent.attempt + 1 } in
              let s = { s with fiber = Awaiting { w with intent } } in
              let t = { (with_slot charged a s) with reserved = charged.reserved + wc } in
              let t, r = record t [ Slot (a, s); Budget (t.reserved, t.settled) ] in
              ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
          | Observational ->
              let why = if intent.attempt < t.max_attempts then "no budget for another attempt" else "attempts lost" in
              let s = { s with fiber = Failed (Printf.sprintf "%s: %d %s" intent.key intent.attempt why) } in
              let t, r = record (with_slot charged a s) [ Slot (a, s); Budget (charged.reserved, charged.settled) ] in
              (t, [ Persist r ])
          | Keyed | Effectful ->
              (* a keyed intent whose sends ran out may have taken effect: Uncertain, as an effectful one *)
              let s = { s with fiber = Uncertain { turn = w.turn; args = w.args; receipts = w.receipts; basis = w.basis; intent } } in
              let t, r = record (with_slot t a s) [ Slot (a, s) ] in
              (t, [ Persist r; Reconcile (intent.key, intent.attempt) ]))
      | _ -> (t, []))
  | Reconciled (key, n, Some (v, cost)) -> (match awaiting t key n with Some (a, s) -> resume t a s v cost | None -> (t, []))
  | Reconciled (key, n, None) -> (
      (* it did not take effect, and the outbox has fenced the key: send it again *)
      match awaiting t key n with
      | Some (a, ({ fiber = Uncertain u; _ } as s)) ->
          let intent = { u.intent with attempt = u.intent.attempt + 1 } in
          let s = { s with fiber = Awaiting { turn = u.turn; args = u.args; receipts = u.receipts; basis = u.basis; intent } } in
          (* a keyed intent's new send reserves its own worst case, as every send of it does *)
          let t = match intent.cls with Keyed -> { t with reserved = t.reserved + t.worst intent.kind } | _ -> t in
          let t, r = record (with_slot t a s) [ Slot (a, s); Budget (t.reserved, t.settled) ] in
          ({ t with gated = t.gated @ [ (r.seq, intent) ] }, [ Persist r ])
      | _ -> (t, []))

let failures t = Smap.fold (fun a s acc -> match s.fiber with Failed m -> (a, m) :: acc | _ -> acc) t.slots []
let stream t n = fst (Smap.find n t.streams)
let refused t = t.refused
let episode t = t.episode
let spend t = (t.reserved, t.settled)

(* ---- recovery ------------------------------------------------------------------------------------- *)

(* Rebuild an instance from its durable records (quint/engine.qnt's crash:
   memory is the durable state). Every intent a durable record awaits is
   released again, under the same key and attempt: the outbox answers it
   from its journal if it was sent (quint/handoff.qnt), and the fiber's
   awaited key and attempt make a second answer change nothing. Every
   Uncertain fiber is reconciled again. A fiber that was running re-runs
   its turn, which is deterministic. *)
let recover ?classes ?max_attempts ?budget ?worst ?revision ?cell_hashes ?(revisions = []) (checked : Check.checked) graph
    (records : record list) =
  let t = create ?classes ?max_attempts ?budget ?worst ?revision ?cell_hashes checked graph in
  let known id = match List.find_opt (fun (c : revision) -> c.id = id) revisions with Some c -> c | None -> err "recovery needs revision %s" id in
  let apply t = function
    | Slot (a, s) -> { t with slots = Smap.add a s t.slots }
    | Input (n, src) -> { t with inputs = Smap.add n src t.inputs }
    | Stream (n, vs, r) -> { t with streams = Smap.add n (vs, r) t.streams }
    | Answered (s, st, e, c) -> { t with answered = (s, st, e, c) :: t.answered }
    | Episode e -> { t with episode = e }
    | Budget (reserved, settled) -> { t with reserved; settled }
    | Cutover_begin id -> { t with cutting = Some (known id) }
    | Asset_revision (a, id) ->
        let c = known id in
        let ai = asset_info t a in
        { t with asset_rev = Smap.add a id t.asset_rev; cells = Smap.add a (List.find (fun (x : Check.cell_info) -> x.cname = ai.acell) c.checked.cells) t.cells }
    | Cutover_end id ->
        let c = known id in
        let g = List.find (fun (g : Check.graph_info) -> g.gname = t.g.gname) c.checked.graphs in
        { t with g; registry = c.checked.registry; rev = c.id; cell_hashes = c.cell_hashes; cutting = None }
    | Refused _ -> { t with refused = t.refused + 1 }
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

(* ---- the replay audit ----------------------------------------------------------------------------- *)

(* RFC 0006 decision 5C: the records are the truth, and replay is an audit
   off the recovery path. Every turn the records show (a fiber that was
   Running turn k, and is next Awaiting turn k's intent, or Ready with a
   value after the last turn) is evaluated again from what the earlier
   record held (its arguments, receipts and the cell's state) and must give
   what was recorded: the same request, or the same result and state.
   Cells are pure CEL, so a difference means nondeterminism, or records
   read against a graph whose code is not the code that wrote them. A turn
   that failed is checked to fail again. Returns the differences found. *)
let audit ?classes ?max_attempts ?budget ?worst ?(revisions = []) (checked : Check.checked) graph (records : record list) =
  let t0 = create ?classes ?max_attempts ?budget ?worst checked graph in
  (* across a cutover, each asset's turns are evaluated with the code its asset ran when they were recorded *)
  let cells = ref t0.cells in
  let problems = ref [] in
  let say fmt = Printf.ksprintf (fun m -> problems := m :: !problems) fmt in
  let last = Hashtbl.create 8 in
  List.iter
    (fun (r : record) ->
      List.iter
        (function
          | Slot (a, s) ->
              (match Hashtbl.find_opt last a, s.fiber with
               | Some ({ fiber = Running { turn; args; receipts; _ }; _ } as before), after -> (
                   let t = { t0 with slots = Smap.add a before t0.slots; cells = !cells } in
                   let c = Smap.find a t.cells in
                   let last_turn = turn = List.length c.turns - 1 in
                   match after, eval_turn t a ~turn ~args ~receipts with
                   | Awaiting { intent; turn = k; _ }, Ok req when k = turn && not last_turn ->
                       if not (V.equal req intent.request) then
                         say "record %d: %s turn %d asks %s, the record says %s" r.seq a turn (V.to_string req) (V.to_string intent.request)
                   | Ready, Ok out when last_turn ->
                       let result = match c.updated, out with [], v -> v | _, V.List xs -> xs.(0) | _, v -> v in
                       if s.value = None || not (V.equal result (Option.get s.value)) then
                         say "record %d: %s's last turn gives %s, the record says %s" r.seq a (V.to_string result)
                           (match s.value with Some v -> V.to_string v | None -> "nothing")
                   | Failed _, Error _ -> ()
                   | Failed m, Ok _ -> say "record %d: %s failed (%s), but its turn %d evaluates" r.seq a m turn
                   | _, Error m -> say "record %d: %s turn %d no longer evaluates: %s" r.seq a turn m
                   | (Running _ | Uncertain _), _ -> ()
                   | _ -> ())
               | _ -> ());
              Hashtbl.replace last a s
          | Asset_revision (a, id) -> (
              match List.find_opt (fun (c : revision) -> c.id = id) revisions with
              | Some c ->
                  let ai = List.find (fun (x : Check.asset_info) -> x.aname = a) t0.g.assets in
                  cells := Smap.add a (List.find (fun (x : Check.cell_info) -> x.cname = ai.acell) c.checked.cells) !cells
              | None -> say "record %d: %s runs revision %s, which the audit was not given" r.seq a id)
          | _ -> ())
        r.facts)
    records;
  List.rev !problems

(* ---- admission and the shadow ------------------------------------------------------------------- *)

(* what must not change for a rolling cutover: the graph's wiring *)
let wiring (g : Check.graph_info) =
  ( List.map fst g.inputs, List.map fst g.streams,
    List.map (fun (a : Check.asset_info) -> (a.aname, a.acell, a.args, a.guards)) g.assets,
    List.map (fun (s, c, st, b, _) -> (s, c, st, b)) g.append_exprs, g.capacities )

(* Begin cutting over to an admitted candidate: a durable cutover-begin
   record; the assets then switch as [ready] offers them. A candidate that
   rewires the graph is refused: that is a restart (decision 6A), not a
   rolling cutover. *)
let admit t (c : revision) =
  if t.cutting <> None then Error "a cutover is already under way"
  else
    match List.find_opt (fun (g : Check.graph_info) -> g.gname = t.g.gname) c.checked.graphs with
    | None -> Error (Printf.sprintf "the candidate has no graph %s" t.g.gname)
    | Some g when wiring g <> wiring t.g -> Error "the candidate rewires the graph; a rolling cutover keeps the wiring (restart instead)"
    | Some _ ->
        let t, r = record { t with cutting = Some c } [ Cutover_begin c.id ] in
        Ok (t, [ Persist r ])

(* The shadow soak (decision 6C): the candidate runs alone, in memory, on
   the given inputs, its intents answered by [cassette], until it comes to
   rest. It returns a verdict and nothing else: no record and no intent of
   a shadow leaves it (cutover.qnt's noShadowEmission). *)
let soak ?classes ?budget ?worst ?(limit = 10_000) (c : revision) graph ~inputs ~(cassette : intent -> V.t * int) =
  match create ?classes ?budget ?worst c.checked graph with
  | exception Engine_error m -> Error m
  | t0 -> (
      let rec go t steps =
        if steps > limit then Error (Printf.sprintf "the candidate did not come to rest in %d steps" limit)
        else
          let t, _ = handle t (Durable t.seq) in
          let t =
            Smap.fold
              (fun _ (s : slot) t ->
                match s.fiber with
                | Awaiting { intent; _ } -> fst (handle t (Receipt (intent.key, intent.attempt, (let v, c = cassette intent in Done (v, c)))))
                | _ -> t)
              t.slots t
          in
          match ready t with
          | a :: _ -> go (fst (perform t a)) (steps + 1)
          | [] -> (
              match failures t with
              | [] when quiet t -> Ok ()
              | [] -> Error "the candidate stopped short of rest (its budget?)"
              | fs -> Error (String.concat "; " (List.map (fun (a, m) -> a ^ ": " ^ m) fs)))
      in
      match List.fold_left (fun t (n, v) -> fst (handle t (Set_input (n, v)))) t0 inputs with
      | t -> (try go t 0 with Engine_error m -> Error m)
      | exception Engine_error m -> Error m)

let inputs t = Smap.fold (fun n (s : source) acc -> match s.svalue with Some v -> (n, v) :: acc | None -> acc) t.inputs []
let revision t = t.rev
let cutting t = Option.map (fun c -> c.id) t.cutting
let asset_revision t a = Smap.find a t.asset_rev
