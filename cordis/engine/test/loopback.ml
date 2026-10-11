(* The slice's loopback host for the engine (docs/cordis/rfc/0006-turn-
   runtime.md, revision 2, stage 1), shared by the review-loop properties
   and the conformance runner.

   A store whose records become durable when the host syncs (in memory or
   on SQLite); an outbox that journals effectful keys (each runs at most
   once and is answered again from the journal), runs observational intents
   once per attempt, may lose receipts and deliver them twice, and answers
   reconciles from its journal; crashes that drop the engine, every
   unsynced record and every message the outbox had not taken. The world
   is a cassette: repo.read returns a context; llm.complete approves a
   patch once it carries two review notes (never one that says "never").

   With [recording], every host step is also projected onto quint/
   runtime.qnt's actions (one host step may be several: a sync and the
   releases it lets out; a crash and what recovery asks), with the model's
   variables after each, for quintgo to replay. *)

open Cordis_engine
module V = Cordis_cel.Value
module E = Engine
module J = Yojson.Safe

let read path = In_channel.with_open_text path In_channel.input_all

let checked =
  lazy
    (match Cordis_graph.Patch.load (read Sys.argv.(1)) with
     | Ok s -> s.checked
     | Error es -> failwith (String.concat "; " es))

(* the kinds a test declares keyed (sent again with the same key, which the world deduplicates) *)
let keyed_kinds = ref []

let classes = function "llm.complete" -> E.Observational | k when List.mem k !keyed_kinds -> E.Keyed | _ -> E.Effectful

(* a revision of the graph from its text: its id is the graph's hash, which covers its cells; a cell's
   state carries unless Compat's state plan, against [from], says to migrate it *)
let revision_of ?from text : E.revision =
  match Cordis_graph.Parse.file text with
  | Error m -> failwith m
  | Ok file -> (
      match Cordis_graph.Check.check file with
      | Error es -> failwith (String.concat "; " es)
      | Ok checked ->
          let hs = Cordis_graph.Print.hashes file in
          let cells = List.filter_map (fun (c : Cordis_graph.Check.cell_info) -> Option.map (fun h -> (c.cname, h)) (List.assoc_opt c.cname hs)) checked.cells in
          let plan =
            match from with
            | None -> []
            | Some (old_file, old_checked) -> Cordis_graph.Compat.state_plan ~old_file ~old_checked ~new_file:file ~new_checked:checked
          in
          let carry c = match List.assoc_opt c plan with Some (Cordis_graph.Compat.Migrate _) -> false | _ -> true in
          { E.id = List.assoc "review_loop" hs; checked; cell_hashes = cells; carry })

let live_text = lazy (read Sys.argv.(1))
let live = lazy (revision_of (Lazy.force live_text))
let live_file = lazy (match Cordis_graph.Parse.file (Lazy.force live_text) with Ok f -> f | Error m -> failwith m)

(* budgets: each kind's worst case (policy) and what the world actually charges *)
let worst = function "llm.complete" -> 5 | _ -> 1
let actual = function "llm.complete" -> 3 | _ -> 1

let str s = V.String s
let field v f = match v with V.Map m -> V.map_find m (V.String f) | _ -> None
let diff path patch = V.make_map [ (str "path", str path); (str "patch", str patch) ]

let count_sub s sub =
  let n = String.length sub in
  let rec go i c = if i + n > String.length s then c else if String.sub s i n = sub then go (i + n) (c + 1) else go (i + 1) c in
  go 0 0

(* the world *)
(* how long the critic's failing answers run, in lines of 80: a real model's, over the notes' bound *)
let note_lines = ref 0

let cassette (i : E.intent) =
  let text s = V.make_map [ (str "text", str s) ] in
  match i.kind with
  | "repo.read" -> (match i.request with V.String p -> text ("context of " ^ p) | _ -> text "?")
  | "llm.complete" -> (
      (* the prompt is [instruction, context, patch]; the note names what it reviewed, so a note left
         from another request shows *)
      let part n = match field i.request "prompt" with Some (V.List xs) when Array.length xs = 3 -> (match xs.(n + 1) with V.String p -> p | _ -> "") | _ -> "" in
      let done_ t = V.make_map [ (str "text", str t); (str "finish", str "stop") ] in
      (* a patch that says never is never approved: its loop rests at the stream's cap *)
      if count_sub (part 1) "\n// " >= 2 && count_sub (part 1) "never" = 0 then done_ "LGTM" else done_ ("needs work (" ^ part 0 ^ ")" ^ String.concat "" (List.init !note_lines (fun k -> Printf.sprintf "\nline %d: %s" k (String.make 70 'x')))))
  | k -> failwith ("no cassette for " ^ k)

(* ---- the host ------------------------------------------------------------------------------------- *)

type store = { append : E.record -> unit; sync : unit -> int; load : unit -> E.record list; crash : unit -> unit }

let memory_store () =
  let m = Store.Memory.create () in
  { append = Store.Memory.append m; sync = (fun () -> Store.Memory.sync m); load = (fun () -> Store.Memory.load m);
    crash = (fun () -> Store.Memory.crash m) }

let sqlite_store path =
  let m = Store.Sqlite.create path in
  { append = Store.Sqlite.append m; sync = (fun () -> Store.Sqlite.sync m); load = (fun () -> Store.Sqlite.load m);
    crash = (fun () -> Store.Sqlite.crash m) }

type choice =
  | Crash
  | Act of E.action
  | Sync
  | Send of E.intent
  | Deliver of int  (** the n-th receipt in flight *)
  | Answer of string * int  (** a reconcile *)
  | Change_input

(* a model step: the runtime.qnt action, its arguments, and the model's variables after it *)
type mstep = { action : string; picks : (string * J.t) list; vars : (string * J.t) list }

type host = {
  mutable t : E.t;
  mutable recording : mstep list option;  (** newest first *)
  mutable captured : E.command list;  (** the commands of the current host step, in order *)
  store : store;
  mutable crashes : int;  (** crashes still allowed *)
  mutable revisions : E.revision list;  (** the candidates this host has admitted, for recovery *)
  mutable admitting : E.revision option;
      (** the admitter keeps a candidate until its cutover is durable, and admits it again after a crash that lost it *)
  mutable last_input : V.t option;
      (** the sender keeps an input until it is durable, and sends it again after a crash; the same input again changes nothing *)
  mutable codec_errors : string list;
  mutable unsynced : int;  (** the highest sequence appended, not yet durable *)
  mutable durable : int;
  mutable outbox : E.intent list;  (** released, not yet sent *)
  journal : (string, V.t * int) Hashtbl.t;  (** effectful keys sent, and their receipts and costs *)
  budget : int option;
  mutable charged : int;  (** what the world has charged, across crashes *)
  mutable spend_errors : string list;  (** budget.qnt's withinBudget and accounted, checked after every step *)
  effects : (string, int) Hashtbl.t;  (** times each effectful or keyed key took effect *)
  server : (string, V.t * int) Hashtbl.t;  (** a keyed intent's key, as the world deduplicates it: its one answer and cost *)
  mutable inflight : (string * int * E.receipt) list;
  mutable reconciles : (string * int) list;
  mutable released_early : int;  (** intents released other than in answer to a Durable event: the output gate broken *)
}

let new_host ?(store = memory_store ()) ?(crashes = 0) ?budget t =
  { budget; charged = 0; spend_errors = []; t; recording = None; captured = []; store; crashes; revisions = []; admitting = None; codec_errors = []; last_input = None; unsynced = 0; durable = 0; outbox = []; journal = Hashtbl.create 8; effects = Hashtbl.create 8; server = Hashtbl.create 8;
    inflight = []; reconciles = []; released_early = 0 }

let obey ?(durable = false) h cmds =
  h.captured <- h.captured @ cmds;
  List.iter
    (function
      | E.Persist r ->
          (* the durable form must give the record back exactly *)
          (match Codec.record r with
           | text -> (
               match Codec.of_record text with
               | r' when Codec.record r' = text && r' = r -> ()
               | _ -> h.codec_errors <- Printf.sprintf "record %d" r.seq :: h.codec_errors
               | exception Codec.Bad m -> h.codec_errors <- m :: h.codec_errors)
           | exception Codec.Bad m -> h.codec_errors <- m :: h.codec_errors);
          h.store.append r;
          h.unsynced <- max h.unsynced r.seq
      | E.Release i ->
          if not durable then h.released_early <- h.released_early + 1;
          h.outbox <- h.outbox @ [ i ]
      | E.Reconcile (k, n) -> h.reconciles <- h.reconciles @ [ (k, n) ])
    cmds

let feed h ev =
  let t, cmds = E.handle h.t ev in
  h.t <- t;
  obey ~durable:(match ev with E.Durable _ -> true | _ -> false) h cmds

(* Faults, drawn by [chance]: a receipt lost, a receipt delivered twice. *)
type faults = { lose : unit -> bool; dup : unit -> bool }

let no_faults = { lose = (fun () -> false); dup = (fun () -> false) }

let choices h ~change =
  List.map (fun a -> Act a) (E.ready h.t)
  @ (if h.unsynced > h.durable then [ Sync ] else [])
  @ List.map (fun i -> Send i) h.outbox
  @ List.mapi (fun n _ -> Deliver n) h.inflight
  @ List.map (fun (k, n) -> Answer (k, n)) h.reconciles
  @ if change then [ Change_input ] else []

let take_ h (f : faults) ~next_input = function
  | Act a ->
      let t, cmds = E.perform h.t a in
      h.t <- t;
      obey h cmds
  | Sync ->
      h.durable <- h.store.sync ();
      feed h (E.Durable h.durable)
  | Crash ->
      h.crashes <- h.crashes - 1;
      h.store.crash ();
      (* what the engine had released or asked for and the outbox had not yet taken dies with it
         (quint/handoff.qnt's lost frames); the outbox's journal and its answers in flight survive *)
      h.outbox <- [];
      h.reconciles <- [];
      let r = Lazy.force live in
      let t, cmds =
        E.recover ~classes ?budget:h.budget ~worst ~revision:r.id ~cell_hashes:r.cell_hashes ~revisions:h.revisions r.checked "review_loop"
          (h.store.load ())
      in
      h.t <- t;
      h.unsynced <- h.durable;
      (* releases from durable records pass the output gate *)
      obey ~durable:true h cmds;
      Option.iter (fun v -> feed h (E.Set_input ("request", v))) h.last_input;
      Option.iter
        (fun (c : E.revision) ->
          if E.cutting h.t = None && E.revision h.t <> c.id then
            match E.admit h.t c with Ok (t, cmds) -> h.t <- t; obey h cmds | Error m -> failwith ("re-admit: " ^ m))
        h.admitting
  | Send i ->
      h.outbox <- List.filter (fun j -> j != i) h.outbox;
      let receipt =
        match i.cls with
        | E.Effectful -> (
            match Hashtbl.find_opt h.journal i.key with
            | Some r -> r (* answered from the journal: never sent twice, never charged twice *)
            | None ->
                let r = (cassette i, actual i.kind) in
                h.charged <- h.charged + snd r;
                Hashtbl.replace h.journal i.key r;
                Hashtbl.replace h.effects i.key (1 + Option.value (Hashtbl.find_opt h.effects i.key) ~default:0);
                r)
        | E.Keyed -> (
            (* each send is journaled by attempt; the world applies the key once: a send of a key it has
               applied runs nothing and charges nothing *)
            let jkey = Printf.sprintf "%s#%d" i.key i.attempt in
            match Hashtbl.find_opt h.journal jkey with
            | Some r -> r
            | None ->
                let r =
                  match Hashtbl.find_opt h.server i.key with
                  | Some (v, _) -> (v, 0)
                  | None ->
                      let r = (cassette i, actual i.kind) in
                      h.charged <- h.charged + snd r;
                      Hashtbl.replace h.server i.key r;
                      Hashtbl.replace h.effects i.key (1 + Option.value (Hashtbl.find_opt h.effects i.key) ~default:0);
                      r
                in
                Hashtbl.replace h.journal jkey r;
                r)
        | E.Observational -> (
            (* each attempt runs once and is charged once; the same attempt released again (after a crash)
               is answered from the journal, as handoff.qnt has the outbox do for every key *)
            let jkey = Printf.sprintf "%s#%d" i.key i.attempt in
            match Hashtbl.find_opt h.journal jkey with
            | Some r -> r
            | None ->
                let r = (cassette i, actual i.kind) in
                h.charged <- h.charged + snd r;
                Hashtbl.replace h.journal jkey r;
                r)
      in
      h.inflight <- h.inflight @ [ (i.key, i.attempt, if f.lose () then E.Lost else E.Done (fst receipt, snd receipt)) ]
  | Deliver n ->
      let k, a, r = List.nth h.inflight n in
      if not (f.dup ()) then h.inflight <- List.filteri (fun m _ -> m <> n) h.inflight;
      feed h (E.Receipt (k, a, r))
  | Answer (k, n) ->
      h.reconciles <- List.filter (( <> ) (k, n)) h.reconciles;
      (* asked by key: the journal for an effectful key, the world's record for a keyed one *)
      let found = match Hashtbl.find_opt h.journal k with Some r -> Some r | None -> Hashtbl.find_opt h.server k in
      feed h (E.Reconciled (k, n, found))
  | Change_input ->
      let v = next_input () in
      h.last_input <- Some v;
      feed h (E.Set_input ("request", v))

(* ---- the projection onto quint/runtime.qnt --------------------------------------------------------- *)

let assets = [ "draft"; "verdict"; "shipped" ]
let int n : J.t = `Assoc [ ("#bigint", `String (string_of_int n)) ]
let some v : J.t = `Assoc [ ("tag", `String "Some"); ("value", v) ]
let await_value key attempt uncertain : J.t = `Assoc [ ("key", `String key); ("attempt", int attempt); ("uncertain", `Bool uncertain) ]

let awaiting (t : E.t) : J.t =
  `Assoc
    [ ( "#map",
        `List
          (List.map
             (fun a ->
               let w =
                 match (E.Smap.find a t.slots).fiber with
                 | E.Awaiting { intent; _ } -> await_value intent.key intent.attempt false
                 | E.Uncertain { intent; _ } -> await_value intent.key intent.attempt true
                 | _ -> await_value "" 0 false
               in
               `List [ `String a; w ])
             assets) ) ]

let fiber_of (t : E.t) a = (E.Smap.find a t.slots).fiber

(* which runtime.qnt action a record is, from the fiber before and after it *)
let classify ?answer (before : E.t) (r : E.record) =
  let slot = List.find_map (function E.Slot (a, s) -> Some (a, s) | _ -> None) r.facts in
  match slot with
  | None -> ("persist", [])
  | Some (a, s) -> (
      let args key attempt = [ ("asset", `String a); ("key", `String key); ("attempt", int attempt) ] in
      match fiber_of before a, s.fiber with
      | E.Awaiting { intent = j; _ }, E.Awaiting { intent = i; _ } when j.key = i.key && j.attempt = i.attempt - 1 -> ("retry", args i.key i.attempt)
      | E.Uncertain { intent = j; _ }, E.Awaiting { intent = i; _ } when j.key = i.key -> ("refetch", args i.key i.attempt)
      | _, E.Awaiting { intent = i; _ } -> ("emit", args i.key i.attempt)
      | _, E.Uncertain { intent = i; _ } -> ("doubt", args i.key i.attempt)
      | E.Awaiting { intent = j; _ }, E.Failed _ -> ("giveUp", args j.key j.attempt)
      | (E.Awaiting { intent = j; _ } | E.Uncertain { intent = j; _ }), _ ->
          (* the answer that resumed it, as delivered: not what the fiber awaited *)
          let key, attempt = Option.value answer ~default:(j.key, j.attempt) in
          ("resume", args key attempt)
      | _ -> ("persist", []))

let project ?answer h ~before ~crashed ~synced cmds =
  match h.recording with
  | None -> ()
  | Some steps ->
      let steps = ref steps in
      let seq = ref (if crashed then h.durable else before.E.seq) in
      let push action picks = steps := { action; picks; vars = [ ("seq", int !seq); ("durable", int h.durable); ("awaiting", awaiting h.t) ] } :: !steps in
      if crashed then push "crash" [];
      if synced then push "sync" [];
      List.iter
        (function
          | E.Persist r ->
              seq := r.seq;
              let action, picks = classify ?answer (if crashed then h.t else before) r in
              push action picks
          | E.Release i -> push "release" [ ("key", `String i.key); ("attempt", int i.attempt) ]
          | E.Reconcile (k, n) -> push "ask" [ ("key", `String k); ("attempt", int n) ])
        cmds;
      if cmds = [] && not crashed && not synced then push "outside" [];
      h.recording <- Some !steps

let take h f ~next_input c =
  let before = h.t in
  let answer = match c with Deliver n -> (let k, a, _ = List.nth h.inflight n in Some (k, a)) | Answer (k, n) -> Some (k, n) | _ -> None in
  h.captured <- [];
  take_ h f ~next_input c;
  project ?answer h ~before ~crashed:(c = Crash) ~synced:(c = Sync) h.captured;
  let reserved, settled = E.spend h.t in
  (match h.budget with
   | Some b when settled + reserved > b -> h.spend_errors <- Printf.sprintf "settled %d + reserved %d over the budget %d" settled reserved b :: h.spend_errors
   | _ -> ());
  if h.charged > settled + reserved then
    h.spend_errors <- Printf.sprintf "the world charged %d, the books hold %d + %d" h.charged settled reserved :: h.spend_errors

let start_recording h =
  h.recording <- Some [ { action = "init"; picks = []; vars = [ ("seq", int h.t.seq); ("durable", int h.durable); ("awaiting", awaiting h.t) ] } ]

(* the recorded steps as a model-level ITF trace, its #meta naming the model for quintgo *)
let itf ~spec ~description h : J.t =
  let steps = List.rev (Option.value h.recording ~default:[]) in
  `Assoc
    [ ( "#meta",
        `Assoc
          [ ("format", `String "ITF"); ("source", `String "cordis-engine"); ("description", `String description);
            ( "quintgo",
              `Assoc
                [ ("spec", `String spec); ("module", `String "runtime");
                  ( "constants",
                    `Assoc
                      [ ("ASSETS", `String "Set(\"draft\", \"verdict\", \"shipped\")"); ("MAX", `String "3");
                        ("EARLY", `String "false"); ("ANY_ATTEMPT", `String "false") ] );
                  ("invariants", `List [ `String "safety" ]); ("actor", `String "engine") ] ) ] );
      ("vars", `List [ `String "seq"; `String "durable"; `String "awaiting" ]);
      ( "states",
        `List
          (List.mapi
             (fun i s ->
               `Assoc
                 ([ ("#meta", `Assoc [ ("index", `Int i) ]); ("mbt::actionTaken", `String s.action);
                    ("mbt::nondetPicks", `Assoc (List.map (fun (k, v) -> (k, some v)) s.picks)) ]
                 @ s.vars))
             steps) ) ]

(* run to rest: [pick n] chooses among n choices *)
let run ?(faults = no_faults) ?(limit = 5000) ?(after = 0) ?(crash_at = []) ?change ~pick h =
  let changes = ref (match change with Some (n, _) -> n | None -> 0) in
  let next_input = match change with Some (_, f) -> f | None -> fun () -> V.Null in
  let rec go steps =
    if steps >= limit then failwith (Printf.sprintf "no rest after %d steps" limit)
    else
      (* a pending change is offered after [after] steps, or as soon as nothing else is left *)
      (* a crash strikes at its drawn step, whatever else is enabled *)
      if List.mem steps crash_at && h.crashes > 0 then (take h faults ~next_input Crash; go (steps + 1)) else
      let cs = choices h ~change:false in
      match if !changes > 0 && (steps >= after || cs = []) then cs @ [ Change_input ] else cs with
      | [] -> steps
      | cs ->
          let c = List.nth cs (pick (List.length cs)) in
          (match c with Change_input -> decr changes | _ -> ());
          take h faults ~next_input c;
          go (steps + 1)
  in
  go 0

let start ?store ?crashes ?(budget = 1000) ?(input = diff "src/main.ml" "let x = 1") () =
  let r = Lazy.force live in
  let h = new_host ?store ?crashes ~budget (E.create ~classes ~budget ~worst ~revision:r.id ~cell_hashes:r.cell_hashes r.checked "review_loop") in
  h.last_input <- Some input;
  feed h (E.Set_input ("request", input));
  h

(* what rest looks like: each asset's value and each stream *)
let snapshot h =
  List.map (fun a -> (a, Option.map V.to_string (E.value h.t a))) [ "draft"; "verdict"; "shipped" ]
  @ [ ("notes", Some (V.to_string (V.List (Array.of_list (E.stream h.t "notes"))))) ]


