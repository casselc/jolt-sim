(* Cutover on the engine (docs/cordis/rfc/0006-turn-runtime.md, decision 6;
   quint/cutover.qnt): the review loop moved to a candidate revision while
   it runs.

   Candidates, each review.cg with one edit:
   - r1: the critic asks for max_tokens 4001 (the cassette ignores it, so a
     loop cut over to r1 rests where a fresh run of r1 does);
   - r2: the critic's turn divides by zero at run time (its soak fails);
   - r3: the notes stream's capacity changes (a rewiring).

   Examples: a soak, then a cutover part way through the loop, every asset
   ending on r1, the critic's requests switching from 4000 to 4001 once and
   for good, the records auditing clean across the revisions; a failed soak
   and a rewiring leave the live instance as it was; a crash between the
   cutover's begin and end is recovered and finished.
   Property: under any schedule, with lost and duplicated receipts and
   crashes, a cutover to r1 at a drawn step rests where a fresh run of r1
   does, every asset on r1, and the records audit clean; and after every
   step no asset is on r1 while an asset it reads is not (cutover.qnt's
   ordered). *)

open Cordis_engine
open Loopback
module V = Cordis_cel.Value
module E = Engine

let edit sub by text =
  let n = String.length sub in
  match List.find_opt (fun i -> String.sub text i n = sub) (List.init (String.length text - n) Fun.id) with
  | Some i -> String.sub text 0 i ^ by ^ String.sub text (i + n) (String.length text - i - n)
  | None -> failwith ("no " ^ sub)

let candidate f =
  let r0 = Lazy.force live in
  revision_of ~from:(Lazy.force live_file, r0.checked) (f (Lazy.force live_text))

let r1 = lazy (candidate (edit "max_tokens: 4000" "max_tokens: 4001"))
let r2 = lazy (candidate (edit "t.endsWith('\\nLGTM'))," "t.endsWith('\\nLGTM')) && 1 / (size(resp.text) - size(resp.text)) == 0,"))
let r3 = lazy (candidate (edit "up to 8" "up to 7"))

let shadow_cassette (i : E.intent) = (cassette i, actual i.kind)

let admit h (c : E.revision) =
  match E.admit h.t c with
  | Ok (t, cmds) ->
      h.t <- t;
      h.revisions <- c :: h.revisions;
      h.admitting <- Some c;
      obey h cmds
  | Error m -> failwith ("admit: " ^ m)

(* take [n] choices, the first offered each time *)
let steps h n =
  let rec go k = if k < n then match choices h ~change:false with [] -> () | c :: _ -> take h no_faults ~next_input:(fun () -> V.Null) c; go (k + 1) in
  go 0

(* the critic's requests, in record order: the max_tokens of each completion it asked for *)
let max_tokens_asked h =
  List.concat_map
    (fun (r : E.record) ->
      List.filter_map
        (function
          | E.Slot ("verdict", { fiber = E.Awaiting { intent; _ }; _ }) when intent.kind = "llm.complete" && intent.attempt = 1 -> (
              match field intent.request "max_tokens" with Some (V.Int n) -> Some (Int64.to_int n) | _ -> None)
          | _ -> None)
        r.facts)
    (h.store.load ())

let rec dedup = function a :: (b :: _ as rest) when a = b -> dedup rest | a :: rest -> a :: dedup rest | [] -> []

let fresh_r1 () =
  let r = Lazy.force r1 in
  let h = new_host ~budget:1000 (E.create ~classes ~budget:1000 ~worst ~revision:r.id ~cell_hashes:r.cell_hashes r.checked "review_loop") in
  h.last_input <- Some (diff "src/main.ml" "let x = 1");
  feed h (E.Set_input ("request", diff "src/main.ml" "let x = 1"));
  ignore (run ~pick:(fun _ -> 0) h);
  h

let audit h =
  E.audit ~classes ~budget:1000 ~worst ~revisions:(Lazy.force r1 :: h.revisions) (Lazy.force live).checked "review_loop" (h.store.load ())

let cutover_example () =
  let h = start () in
  steps h 25;
  let c = Lazy.force r1 in
  Alcotest.(check (result unit string)) "the candidate soaks" (Ok ())
    (E.soak ~classes ~budget:1000 ~worst c "review_loop" ~inputs:(E.inputs h.t) ~cassette:shadow_cassette);
  admit h c;
  ignore (run ~pick:(fun _ -> 0) h);
  Alcotest.(check (list (pair string string))) "no failures" [] (E.failures h.t);
  Alcotest.(check (option string)) "no cutover under way" None (E.cutting h.t);
  Alcotest.(check string) "the live revision" c.id (E.revision h.t);
  List.iter (fun a -> Alcotest.(check string) (a ^ " runs r1") c.id (E.asset_revision h.t a)) [ "draft"; "verdict"; "shipped" ];
  Alcotest.(check (list (pair string (option string)))) "rests where a fresh run of r1 does" (snapshot (fresh_r1 ())) (snapshot h);
  Alcotest.(check (list int)) "the critic switched from 4000 to 4001 once, for good" [ 4000; 4001 ] (dedup (max_tokens_asked h));
  Alcotest.(check (list string)) "the records audit clean across the revisions" [] (audit h)

let soak_rejects () =
  let h = start () in
  steps h 25;
  let before = snapshot h in
  (match E.soak ~classes ~budget:1000 ~worst (Lazy.force r2) "review_loop" ~inputs:(E.inputs h.t) ~cassette:shadow_cassette with
   | Ok () -> Alcotest.fail "a candidate whose critic divides by zero soaked"
   | Error _ -> ());
  Alcotest.(check (list (pair string (option string)))) "the live instance is untouched" before (snapshot h);
  Alcotest.(check string) "still on the live revision" (Lazy.force live).id (E.revision h.t)

let rewiring_refused () =
  let h = start () in
  match E.admit h.t (Lazy.force r3) with
  | Ok _ -> Alcotest.fail "a rewiring was admitted for a rolling cutover"
  | Error _ -> Alcotest.(check (option string)) "no cutover under way" None (E.cutting h.t)

let crash_mid_cutover () =
  let h = start ~crashes:1 () in
  steps h 25;
  admit h (Lazy.force r1);
  steps h 3;
  (* the begin record is durable, the cutover is not finished: crash *)
  take h no_faults ~next_input:(fun () -> V.Null) Sync;
  Alcotest.(check bool) "mid-cutover" true (E.cutting h.t <> None);
  take h no_faults ~next_input:(fun () -> V.Null) Crash;
  Alcotest.(check bool) "recovered mid-cutover" true (E.cutting h.t <> None);
  ignore (run ~pick:(fun _ -> 0) h);
  Alcotest.(check string) "finished on r1" (Lazy.force r1).id (E.revision h.t);
  Alcotest.(check (list (pair string (option string)))) "rests where a fresh run of r1 does" (snapshot (fresh_r1 ())) (snapshot h)

let settings =
  let cases = match Sys.getenv_opt "ENGINE_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  let seed = match Sys.getenv_opt "ENGINE_PROPS_SEED" with Some s -> int_of_string s | None -> 31 in
  Hegel.Settings.create ~test_cases:cases ~seed ()

let cutover_anywhere () =
  let reference = snapshot (fresh_r1 ()) in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:engine:cutover" (fun tc ->
      let draw n = if n <= 1 then 0 else Hegel.draw tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let losses = ref (draw 3) and dups = ref (draw 4) in
      let faults =
        { lose = (fun () -> !losses > 0 && draw 3 = 0 && (decr losses; true)); dup = (fun () -> !dups > 0 && draw 3 = 0 && (decr dups; true)) }
      in
      let crashes = draw 3 in
      let h = start ~crashes () in
      (* the cutover is admitted at a drawn step: before, during or after the loop *)
      let at = draw 120 and admitted = ref false in
      (* cutover.qnt's ordered, after every step: an asset on r1 reads only assets on r1 *)
      let disorder = ref [] in
      let check_order () =
        let r = (Lazy.force r1).id in
        List.iter
          (fun (a, deps) ->
            if E.asset_revision h.t a = r then
              List.iter (fun d -> if E.asset_revision h.t d <> r then disorder := Printf.sprintf "%s on r1 before %s" a d :: !disorder) deps)
          [ ("verdict", [ "draft" ]); ("shipped", [ "verdict" ]) ]
      in
      let crash_at = List.init crashes (fun _ -> draw 160) in
      let rec go k =
        if k > 5000 then failwith "no rest"
        else begin
          if k = at && not !admitted then (admit h (Lazy.force r1); admitted := true);
          if List.mem k crash_at && h.crashes > 0 then (take h faults ~next_input:(fun () -> V.Null) Crash; go (k + 1))
          else
            match choices h ~change:false with
            | [] when not !admitted -> admit h (Lazy.force r1); admitted := true; go (k + 1)
            | [] -> ()
            | cs -> take h faults ~next_input:(fun () -> V.Null) (List.nth cs (draw (List.length cs))); check_order (); go (k + 1)
        end
      in
      go 0;
      Hegel.require tc ~msg:(String.concat "; " (List.map (fun (a, m) -> a ^ ": " ^ m) (E.failures h.t))) (E.failures h.t = []);
      Hegel.require tc ~msg:(String.concat "; " !disorder) (!disorder = []);
      Hegel.require tc ~msg:"a cutover still under way at rest" (E.cutting h.t = None);
      Hegel.require tc ~msg:"not on r1" (E.revision h.t = (Lazy.force r1).id);
      Hegel.require tc ~msg:"rest differs from a fresh run of r1" (snapshot h = reference);
      let ds = audit h in
      Hegel.require tc ~msg:("the audit: " ^ String.concat "; " ds) (ds = []);
      Hegel.require tc ~msg:("spend: " ^ String.concat "; " h.spend_errors) (h.spend_errors = []))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "engine-cutover"
    [ ( "examples",
        [ Alcotest.test_case "soak, then cut over part way: rests on r1, switching once" `Quick cutover_example;
          Alcotest.test_case "a failed soak leaves the live instance untouched" `Quick soak_rejects;
          Alcotest.test_case "a rewiring is refused" `Quick rewiring_refused;
          Alcotest.test_case "a crash between begin and end is recovered and finished" `Quick crash_mid_cutover ] );
      ("properties", [ Alcotest.test_case "a cutover at any step, with faults and crashes, rests on r1" `Quick cutover_anywhere ]) ]
