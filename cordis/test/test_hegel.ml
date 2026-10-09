(* The simulator under Hegel: the oracle's every answer is a Hegel draw, so
   a failing run shrinks to a minimal schedule and fault set. *)

open Cordis_sim

let seen_failure = ref None

let property ?(cfg = Sim.default) ~scenario tc =
  let o = Cordis_hegel.Hegel_oracle.of_test_case tc in
  let a = Assertions.create () in
  let sc = scenario o in
  let r = Sim.run ~assertions:a cfg sc o in
  if !seen_failure = None then seen_failure := Assertions.first_failure a;
  Hegel.note tc (Printf.sprintf "%d steps: %s" (List.length r.steps)
                   (String.concat " " (List.map (fun (s : Itf.step) -> s.action) r.steps)));
  Hegel.require tc
    ~msg:(Option.value (Assertions.first_failure a) ~default:"")
    (Assertions.first_failure a = None)

let settings = Hegel.Settings.create ~test_cases:200 ~seed:1 ()

let test_chain () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:chain" (property ~scenario:(fun _ -> Scenario.chain))

let test_random () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:random"
    (property ~scenario:(fun o -> Scenario.random o ~fibers:(2 + o.int 4)))

(* Negative control: Hegel must find, and shrink, the blind-retry duplicate. *)
let test_blind_retry_found () =
  seen_failure := None;
  let found =
    try
      Hegel.run_hegel_test
        ~settings:{ settings with print_blob = false }
        ~database_key:"cordis:blind-retry"
        (property ~cfg:{ Sim.default with mutant = Sim.Blind_retry } ~scenario:(fun _ -> Scenario.chain));
      false
    with _ -> true
  in
  Alcotest.(check bool) "Hegel reports a failing case" true found;
  Alcotest.(check (option string)) "and it is the duplicate emission"
    (Some "at most once: no emission takes effect twice") !seen_failure

let () =
  Alcotest.run "cordis-hegel"
    [
      ( "hegel",
        [
          Alcotest.test_case "chain: every always holds" `Quick test_chain;
          Alcotest.test_case "random scenarios: every always holds" `Slow test_random;
          Alcotest.test_case "blind retry is found" `Quick test_blind_retry_found;
        ] );
    ]
