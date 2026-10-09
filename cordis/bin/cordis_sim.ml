(* cordis-sim: run a simulation campaign and print its verdict.

     cordis-sim [--scenario chain|random] [--runs N] [--seed S]
                [--mutant design|blind-retry] [--itf DIR] [--itf-runs K]
                [--spec PATH]

   Exit 1 when the verdict fails (an `always` violated, an `unreachable`
   reached, or no runs), 0 otherwise; unreached witnesses are warnings. With
   --itf, the first K runs are written as model-level ITF traces for
   `quintgo validate`. *)

open Cordis_sim

let () =
  let scenario = ref "chain" and runs = ref 200 and seed = ref 1 and mutant = ref "design" in
  let itf = ref "" and itf_runs = ref 5 and spec = ref "quint/lifecycle.qnt" in
  let fibers = ref 4 in
  Arg.parse
    [
      ("--scenario", Arg.Set_string scenario, "chain | random");
      ("--fibers", Arg.Set_int fibers, "fibers in a random scenario");
      ("--runs", Arg.Set_int runs, "runs in the campaign");
      ("--seed", Arg.Set_int seed, "first seed");
      ("--mutant", Arg.Set_string mutant, "design | blind-retry");
      ("--itf", Arg.Set_string itf, "directory for ITF traces");
      ("--itf-runs", Arg.Set_int itf_runs, "how many runs to write as ITF");
      ("--spec", Arg.Set_string spec, "path of quint/lifecycle.qnt, recorded in each trace");
    ]
    (fun _ -> ())
    "cordis-sim [options]";
  let mutant = match !mutant with "blind-retry" -> Sim.Blind_retry | _ -> Sim.Design in
  let cfg = { Sim.default with mutant } in
  let a = Assertions.create () in
  Sim.declare_witnesses a;
  let spec = if Filename.is_relative !spec then Filename.concat (Sys.getcwd ()) !spec else !spec in
  for k = 0 to !runs - 1 do
    let seed = !seed + k in
    let sc =
      match !scenario with
      | "random" -> Scenario.random (Oracle.splitmix (seed * 7919)) ~fibers:!fibers
      | _ -> Scenario.chain
    in
    let r = Sim.run ~assertions:a cfg sc (Oracle.splitmix seed) in
    if !itf <> "" && k < !itf_runs then
      Sim.write_itf ~spec ~reconcile:(mutant = Sim.Design)
        ~path:(Filename.concat !itf (Printf.sprintf "%s-%d.itf.json" sc.name seed))
        ~description:(Printf.sprintf "cordis-sim %s seed %d" sc.name seed)
        sc r
  done;
  let v = Assertions.verdict a in
  Format.printf "%d runs of %s (%s)@." (Assertions.runs a) !scenario
    (match mutant with Sim.Design -> "design" | Sim.Blind_retry -> "mutant blind-retry");
  Assertions.print_verdict Format.std_formatter v;
  exit (if v.failed then 1 else 0)
