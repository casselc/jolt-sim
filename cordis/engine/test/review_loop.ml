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
open Loopback
module V = Cordis_cel.Value
module E = Engine
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

(* a budget too small for the loop: the critic's second computation cannot reserve its completion,
   so the loop stops there, within its budget, with nothing reserved for an intent never sent *)
let tight_budget () =
  let h = start ~budget:8 () in
  let _ = run ~pick:(fun _ -> 0) h in
  let reserved, settled = E.spend h.t in
  Alcotest.(check (list string)) "within the budget, every step" [] h.spend_errors;
  Alcotest.(check bool) "settled within the budget" true (settled + reserved <= 8);
  Alcotest.(check int) "nothing reserved" 0 reserved;
  Alcotest.(check (option string)) "nothing shipped" None (Option.map V.to_string (E.value h.t "shipped"));
  Alcotest.(check bool) "blocked, not at rest" false (E.quiet h.t)

(* the audit reads records against the code: a run's records read against a graph whose critic asks
   for a different max_tokens show every critic turn that asked *)
let audit_example () =
  let h = start () in
  let _ = run ~pick:(fun _ -> 0) h in
  let records = h.store.load () in
  Alcotest.(check (list string)) "the records audit clean against their own graph" []
    (E.audit ~classes ~budget:1000 ~worst (Lazy.force checked) "review_loop" records);
  let text = read Sys.argv.(1) in
  let changed =
    let sub = "max_tokens: 4000" in
    match List.find_opt (fun i -> String.sub text i (String.length sub) = sub) (List.init (String.length text - String.length sub) Fun.id) with
    | Some i -> String.sub text 0 i ^ "max_tokens: 4001" ^ String.sub text (i + String.length sub) (String.length text - i - String.length sub)
    | None -> text
  in
  Alcotest.(check bool) "the graph was changed" true (changed <> text);
  let other = match Cordis_graph.Patch.load changed with Ok s -> s.checked | Error es -> failwith (String.concat "; " es) in
  let differences = E.audit ~classes ~budget:1000 ~worst other "review_loop" records in
  Alcotest.(check int) "every completion request differs" 3
    (List.length (List.filter (fun m -> count_sub m " asks " > 0) differences))

(* one route arm appending to two streams: each append is keyed by its stream as well as by the
   computation it answers, so the second is not taken for the first (routes.qnt's log append) *)
let two_appends () =
  let text = read Sys.argv.(1) in
  let edit sub by text =
    let n = String.length sub in
    match List.find_opt (fun i -> String.sub text i n = sub) (List.init (String.length text - n) Fun.id) with
    | Some i -> String.sub text 0 i ^ by ^ String.sub text (i + n) (String.length text - i - n)
    | None -> failwith ("no " ^ sub)
  in
  let text =
    text
    |> edit "stream notes : string<max 500> up to 8;" "stream notes : string<max 500> up to 8;\n  stream log : string<max 500> up to 8;"
    |> edit "Fail(r) -> notes <~ r.notes" "Fail(r) -> notes <~ r.notes, log <~ 'failed'"
  in
  let checked = match Cordis_graph.Patch.load text with Ok s -> s.checked | Error es -> failwith (String.concat "; " es) in
  let h = new_host ~budget:1000 (E.create ~classes ~budget:1000 ~worst checked "review_loop") in
  h.last_input <- Some (diff "src/main.ml" "let x = 1");
  feed h (E.Set_input ("request", diff "src/main.ml" "let x = 1"));
  let _ = run ~crash_at:[ 30 ] ~pick:(fun _ -> 0) h in
  Alcotest.(check (list (pair string string))) "no failures" [] (E.failures h.t);
  Alcotest.(check int) "two notes" 2 (List.length (E.stream h.t "notes"));
  Alcotest.(check int) "the log has one entry per failing verdict" 2 (List.length (E.stream h.t "log"))

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
      Hegel.require tc ~msg:("spend: " ^ String.concat "; " h.spend_errors) (h.spend_errors = []);
      (* the replay audit (decision 5C): every recorded turn evaluates again to what was recorded *)
      let differences = E.audit ~classes ~budget:1000 ~worst (Lazy.force checked) "review_loop" (h.store.load ()) in
      Hegel.require tc ~msg:("the audit: " ^ String.concat "; " differences) (differences = []);
      Hegel.require tc ~msg:"a reservation outlived its intent" (fst (E.spend h.t) = 0);
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
          Alcotest.test_case "on SQLite, crashing twice, it rests where the uncrashed run does" `Quick sqlite_example;
          Alcotest.test_case "a budget too small stops the loop within it" `Quick tight_budget;
          Alcotest.test_case "the replay audit reads records against the code" `Quick audit_example;
          Alcotest.test_case "a route arm appending to two streams appends to both" `Quick two_appends ]);
      ("properties", [ Alcotest.test_case "every value round-trips through the durable form" `Quick codec_round_trip; Alcotest.test_case "any schedule, with faults and a changed input, rests where a fault-free run does" `Quick schedules ]) ]
