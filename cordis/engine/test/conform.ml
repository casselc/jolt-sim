(* Conformance runs (docs/cordis/rfc/0006-turn-runtime.md, revision 2,
   stage 1): the review loop on the loopback host under seeded random
   schedules, faults, crashes and an input change, each run written as a
   model-level ITF trace over quint/runtime.qnt for quintgo to replay.

     conform.exe REVIEW.cg OUT_DIR RUNS SEED SPEC

   quint/check.sh replays them ("runs conformance-engine"). *)

open Cordis_engine
open Loopback

let () =
  let out = Sys.argv.(2) and runs = int_of_string Sys.argv.(3) and seed = int_of_string Sys.argv.(4) and spec = Sys.argv.(5) in
  let inputs = [| diff "a.ml" "let a = 1"; diff "b.ml" "let b = 2"; diff "n.ml" "never" |] in
  for k = 1 to runs do
    let rng = Random.State.make [| seed; k |] in
    let draw n = if n <= 1 then 0 else Random.State.int rng n in
    let losses = ref (draw 3) and dups = ref (draw 4) in
    let faults =
      { lose = (fun () -> !losses > 0 && draw 3 = 0 && (decr losses; true)); dup = (fun () -> !dups > 0 && draw 3 = 0 && (decr dups; true)) }
    in
    let crashes = draw 4 in
    let h = new_host ~crashes (Engine.create ~classes (Lazy.force checked) "review_loop") in
    start_recording h;
    let first = inputs.(draw (Array.length inputs)) and final = inputs.(draw (Array.length inputs)) in
    take h no_faults ~next_input:(fun () -> first) Change_input;
    let steps =
      run ~faults ~after:(draw 80) ~crash_at:(List.init crashes (fun _ -> draw 150)) ~change:(1, fun () -> final) ~pick:draw h
    in
    let path = Filename.concat out (Printf.sprintf "engine-%d-%d.itf.json" seed k) in
    let oc = open_out path in
    Yojson.Safe.pretty_to_channel oc (itf ~spec ~description:(Printf.sprintf "review loop, seed %d run %d, %d host steps" seed k steps) h);
    close_out oc
  done
