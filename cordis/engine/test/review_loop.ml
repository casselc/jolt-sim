(* The review loop on the engine, in memory (docs/cordis/rfc/0006-turn-
   runtime.md, revision 2, stage 1 of the slice).

   The host here is the slice's loopback: a store whose records become
   durable when the host syncs, and an outbox that journals effectful keys
   (each runs at most once and is answered again from the journal), runs
   observational intents once per attempt, may lose receipts and deliver
   them twice, and answers reconciles from its journal. The world is a
   cassette: repo.read returns a context; llm.complete approves a patch once
   it carries two review notes, so the loop fails twice, appends twice, and
   ships.

   The store is the run store's interface (Store): in memory for the
   properties, SQLite for an example; a crash drops the engine and every
   record not yet synced, and the engine is recovered from what is durable
   (Engine.recover), while the outbox, a separate process, keeps its
   journal and what is in flight.

   Properties:
   - the example: a fixed schedule with no faults reaches rest with the
     draft carrying two notes, the verdict Pass and shipped true; on
     SQLite, crashing twice part way, it rests in the same place;
   - schedule independence (routes.qnt's consistent, and Cordis's
     confluence): under any schedule the host picks, with lost and
     duplicated receipts, delayed durability, and the input changed part way
     through, the run reaches rest with exactly the assets and streams a
     fault-free run of the final input reaches;
   - effectful keys take effect at most once, and no intent leaves before
     its record is durable; a failing loop rests only at its cap;
   - all of it with crashes at any point, recovered from the durable
     records, and every record round-trips through its durable form. *)

open Cordis_engine
module V = Cordis_cel.Value
module E = Engine

let read path = In_channel.with_open_text path In_channel.input_all

let checked =
  lazy
    (match Cordis_graph.Patch.load (read Sys.argv.(1)) with
     | Ok s -> s.checked
     | Error es -> failwith (String.concat "; " es))

let classes = function "llm.complete" -> E.Observational | _ -> E.Effectful

let str s = V.String s
let field v f = match v with V.Map m -> V.map_find m (V.String f) | _ -> None
let diff path patch = V.make_map [ (str "path", str path); (str "patch", str patch) ]

let count_sub s sub =
  let n = String.length sub in
  let rec go i c = if i + n > String.length s then c else if String.sub s i n = sub then go (i + n) (c + 1) else go (i + 1) c in
  go 0 0

(* the world *)
let cassette (i : E.intent) =
  let text s = V.make_map [ (str "text", str s) ] in
  match i.kind with
  | "repo.read" -> (match i.request with V.String p -> text ("context of " ^ p) | _ -> text "?")
  | "llm.complete" -> (
      (* the note names what it reviewed, so a note left from another request shows *)
      let part n = match field i.request "prompt" with Some (V.List xs) when Array.length xs = 2 -> (match xs.(n) with V.String p -> p | _ -> "") | _ -> "" in
      (* a patch that says never is never approved: its loop rests at the stream's cap *)
      if count_sub (part 1) "\n// " >= 2 && count_sub (part 1) "never" = 0 then text "LGTM" else text ("needs work (" ^ part 0 ^ ")"))
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

type host = {
  mutable t : E.t;
  store : store;
  mutable crashes : int;  (** crashes still allowed *)
  mutable last_input : V.t option;
      (** the sender keeps an input until it is durable, and sends it again after a crash; the same input again changes nothing *)
  mutable codec_errors : string list;
  mutable unsynced : int;  (** the highest sequence appended, not yet durable *)
  mutable durable : int;
  mutable outbox : E.intent list;  (** released, not yet sent *)
  journal : (string, V.t) Hashtbl.t;  (** effectful keys sent, and their receipts *)
  effects : (string, int) Hashtbl.t;  (** times each effectful key took effect *)
  mutable inflight : (string * int * E.receipt) list;
  mutable reconciles : (string * int) list;
  mutable released_early : int;  (** intents released other than in answer to a Durable event: the output gate broken *)
}

let new_host ?(store = memory_store ()) ?(crashes = 0) t =
  { t; store; crashes; codec_errors = []; last_input = None; unsynced = 0; durable = 0; outbox = []; journal = Hashtbl.create 8; effects = Hashtbl.create 8;
    inflight = []; reconciles = []; released_early = 0 }

let obey ?(durable = false) h cmds =
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

let take h (f : faults) ~next_input = function
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
      let t, cmds = E.recover ~classes (Lazy.force checked) "review_loop" (h.store.load ()) in
      h.t <- t;
      h.unsynced <- h.durable;
      (* releases from durable records pass the output gate *)
      obey ~durable:true h cmds;
      Option.iter (fun v -> feed h (E.Set_input ("request", v))) h.last_input
  | Send i ->
      h.outbox <- List.filter (fun j -> j != i) h.outbox;
      let receipt =
        match i.cls with
        | E.Effectful -> (
            match Hashtbl.find_opt h.journal i.key with
            | Some r -> r (* answered from the journal: never sent twice *)
            | None ->
                let r = cassette i in
                Hashtbl.replace h.journal i.key r;
                Hashtbl.replace h.effects i.key (1 + Option.value (Hashtbl.find_opt h.effects i.key) ~default:0);
                r)
        | E.Observational -> cassette i
      in
      h.inflight <- h.inflight @ [ (i.key, i.attempt, if f.lose () then E.Lost else E.Done receipt) ]
  | Deliver n ->
      let k, a, r = List.nth h.inflight n in
      if not (f.dup ()) then h.inflight <- List.filteri (fun m _ -> m <> n) h.inflight;
      feed h (E.Receipt (k, a, r))
  | Answer (k, n) ->
      h.reconciles <- List.filter (( <> ) (k, n)) h.reconciles;
      feed h (E.Reconciled (k, n, Hashtbl.find_opt h.journal k))
  | Change_input ->
      let v = next_input () in
      h.last_input <- Some v;
      feed h (E.Set_input ("request", v))

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

let start ?store ?crashes ?(input = diff "src/main.ml" "let x = 1") () =
  let h = new_host ?store ?crashes (E.create ~classes (Lazy.force checked) "review_loop") in
  h.last_input <- Some input;
  feed h (E.Set_input ("request", input));
  h

(* what rest looks like: each asset's value and each stream *)
let snapshot h =
  List.map (fun a -> (a, Option.map V.to_string (E.value h.t a))) [ "draft"; "verdict"; "shipped" ]
  @ [ ("notes", Some (V.to_string (V.List (Array.of_list (E.stream h.t "notes"))))) ]

(* ---- the example ---------------------------------------------------------------------------------- *)

let example () =
  let h = start () in
  let _ = run ~pick:(fun _ -> 0) h in
  Alcotest.(check (list (pair string string))) "no failures" [] (E.failures h.t);
  Alcotest.(check bool) "quiet" true (E.quiet h.t);
  Alcotest.(check int) "two notes" 2 (List.length (E.stream h.t "notes"));
  Alcotest.(check (option string)) "shipped" (Some "true") (Option.map V.to_string (E.value h.t "shipped"));
  (match E.value h.t "verdict" with
   | Some v -> Alcotest.(check (option string)) "verdict" (Some "Pass") (Option.map V.to_string (field v "tag") |> Option.map (fun s -> String.sub s 1 (String.length s - 2)))
   | None -> Alcotest.fail "no verdict");
  (match E.value h.t "draft" with
   | Some d -> Alcotest.(check int) "the draft carries both notes" 2 (count_sub (match field d "patch" with Some (V.String p) -> p | _ -> "") "\n// ")
   | None -> Alcotest.fail "no draft");
  Alcotest.(check int) "repo.read once per critic computation" 3 (Hashtbl.length h.effects);
  Alcotest.(check (list string)) "records round-trip" [] h.codec_errors

(* the same loop on SQLite, crashing twice part way and recovering from the file *)
let sqlite_example () =
  let path = Filename.temp_file "cordis-engine" ".db" in
  let reference = start () in
  let _ = run ~pick:(fun _ -> 0) reference in
  let h = start ~store:(sqlite_store path) ~crashes:2 () in
  let _ = run ~crash_at:[ 20; 45 ] ~pick:(fun _ -> 0) h in
  Alcotest.(check int) "both crashes happened" 0 h.crashes;
  Alcotest.(check (list (pair string string))) "no failures" [] (E.failures h.t);
  Alcotest.(check (list (pair string (option string)))) "rests where the uncrashed run does" (snapshot reference) (snapshot h);
  Alcotest.(check bool) "records on disk" true (List.length (h.store.load ()) > 10);
  Sys.remove path

(* ---- schedule independence ------------------------------------------------------------------------ *)

let settings =
  let cases = match Sys.getenv_opt "ENGINE_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  let seed = match Sys.getenv_opt "ENGINE_PROPS_SEED" with Some s -> int_of_string s | None -> 29 in
  Hegel.Settings.create ~test_cases:cases ~seed ()

let inputs = [| diff "a.ml" "let a = 1"; diff "b.ml" "let b = 2"; diff "c.ml" ""; diff "n.ml" "never" |]

let schedules () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:engine:review-loop" (fun tc ->
      let draw n = if n <= 1 then 0 else Hegel.draw tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let first = draw (Array.length inputs) and final = draw (Array.length inputs) in
      let change = draw 2 = 1 in
      (* when the input may change: early, or part way through the loop *)
      let after = draw 80 in
      (* faults: each receipt lost or duplicated with a drawn chance, at most a few times, so an
         observational intent is not lost on every attempt *)
      let losses = ref (draw 3) and dups = ref (draw 4) in
      let crashes = draw 4 in
      let crash_at = List.init crashes (fun _ -> draw 150) in
      let faults =
        { lose = (fun () -> !losses > 0 && draw 3 = 0 && (decr losses; true));
          dup = (fun () -> !dups > 0 && draw 3 = 0 && (decr dups; true)) }
      in
      let h = start ~crashes ~input:inputs.(first) () in
      let _ = run ~faults ~after ~crash_at ?change:(if change then Some (1, fun () -> inputs.(final)) else None) ~pick:draw h in
      let final = if change then final else first in
      let reference = start ~input:inputs.(final) () in
      let _ = run ~pick:(fun _ -> 0) reference in
      Hegel.require tc ~msg:(String.concat "; " (List.map (fun (a, m) -> a ^ ": " ^ m) (E.failures h.t))) (E.failures h.t = []);
      let busy =
        E.Smap.fold
          (fun a (s : E.slot) acc ->
            match s.fiber with
            | E.Ready | E.Failed _ -> acc
            | E.Running r -> Printf.sprintf "%s running turn %d" a r.turn :: acc
            | E.Awaiting w -> Printf.sprintf "%s awaiting %s attempt %d" a w.intent.key w.intent.attempt :: acc
            | E.Uncertain u -> Printf.sprintf "%s uncertain on %s attempt %d" a u.intent.key u.intent.attempt :: acc)
          h.t.slots []
      in
      Hegel.require tc
        ~msg:(Printf.sprintf "not quiet at rest: %s; ready %s; gated %d; in flight %d; outbox %d; reconciles %d"
                (String.concat ", " busy) (String.concat ", " (List.map E.action_name (E.ready h.t))) (List.length h.t.gated)
                (List.length h.inflight) (List.length h.outbox) (List.length h.reconciles))
        (E.quiet h.t);
      let show s = String.concat ", " (List.map (fun (a, v) -> a ^ "=" ^ Option.value v ~default:"-") s) in
      Hegel.require tc
        ~msg:(Printf.sprintf "rest differs from the fault-free run of the final input:\n  got  %s\n  want %s" (show (snapshot h)) (show (snapshot reference)))
        (snapshot h = snapshot reference);
      Hashtbl.iter (fun k n -> Hegel.require tc ~msg:(Printf.sprintf "%s took effect %d times" k n) (n <= 1)) h.effects;
      (* routes.qnt's noStall: a failing loop rests only at its cap, and then nothing is shipped *)
      let passed = match E.value h.t "verdict" with Some v -> field v "tag" = Some (str "Pass") | None -> false in
      Hegel.require tc ~msg:"a failing loop rested below its cap"
        (passed || List.length (E.stream h.t "notes") = 8);
      Hegel.require tc ~msg:"shipped while the verdict fails" (passed || E.value h.t "shipped" = None);
      Hegel.require tc ~msg:("records that do not round-trip: " ^ String.concat ", " h.codec_errors) (h.codec_errors = []);
      Hegel.require tc ~msg:(Printf.sprintf "%d intents left before their records were durable" h.released_early) (h.released_early = 0))

(* ---- the durable form ---------------------------------------------------------------------------- *)

let codec_round_trip () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:engine:codec" (fun tc ->
      let draw n = Hegel.draw tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let int64 () = Hegel.draw tc (Hegel.Generators.integers ~min_value:min_int ~max_value:max_int ()) |> Int64.of_int in
      let double () =
        match draw 6 with
        | 0 -> Float.nan | 1 -> Float.infinity | 2 -> -0.0 | 3 -> Float.min_float
        | _ -> Hegel.draw tc (Hegel.Generators.floats ())
      in
      let text () = Hegel.draw tc (Hegel.Generators.text ~max_size:8 ()) in
      let rec value depth : V.t =
        match draw (if depth = 0 then 10 else 14) with
        | 0 -> V.Null | 1 -> V.Bool (draw 2 = 0) | 2 -> V.Int (int64 ()) | 3 -> V.Uint (int64 ())
        | 4 -> V.Double (double ()) | 5 -> V.String (text ()) | 6 -> V.Bytes (String.init (draw 4) (fun _ -> Char.chr (draw 256)))
        | 7 -> V.Timestamp (int64 (), draw 1_000_000_000) | 8 -> V.Duration (int64 (), draw 1_000_000_000)
        | 9 -> V.Enum { tname = "e.E"; num = draw 5 }
        | 10 -> V.List (Array.init (draw 3) (fun _ -> value (depth - 1)))
        | 11 ->
            let keys = List.sort_uniq compare (List.init (draw 3) (fun _ -> text ())) in
            V.make_map (List.map (fun k -> (V.String k, value (depth - 1))) keys)
        | 12 -> V.Optional (if draw 2 = 0 then None else Some (value (depth - 1)))
        | _ -> V.Message { tname = "m.M"; fields = [ ("f", value (depth - 1)) ] }
      in
      let v = value 3 in
      let r = { E.seq = 1; facts = [ E.Stream ("s", [ v ], 1) ] } in
      let back = Codec.of_record (Codec.record r) in
      (* compared by their durable bytes and structurally: NaN is not equal to itself under V.equal *)
      Hegel.require tc ~msg:("did not round-trip: " ^ V.to_string v) (Codec.record back = Codec.record r && compare back r = 0))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "engine-review-loop"
    [ ("example",
        [ Alcotest.test_case "the review loop reaches rest and ships" `Quick example;
          Alcotest.test_case "on SQLite, crashing twice, it rests where the uncrashed run does" `Quick sqlite_example ]);
      ("properties", [ Alcotest.test_case "every value round-trips through the durable form" `Quick codec_round_trip; Alcotest.test_case "any schedule, with faults and a changed input, rests where a fault-free run does" `Quick schedules ]) ]
