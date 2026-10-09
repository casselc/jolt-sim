open Cordis_kernel
open Cordis_sim

let keys = Kernel.Keys.of_list
let ok = function Ok x -> x | Error e -> Alcotest.fail e

(* ---- Order (ported from ensemble.order) ---------------------------------------------- *)

let children = [ (1, Order.Permanent); (2, Order.Temporary); (3, Order.Transient) ]

let test_restart_ok () =
  Alcotest.(check bool) "permanent restarts on normal" true (Order.restart_ok Permanent Normal);
  Alcotest.(check bool) "temporary never" false (Order.restart_ok Temporary (Crash "x"));
  Alcotest.(check bool) "transient not on shutdown" false (Order.restart_ok Transient Shutdown);
  Alcotest.(check bool) "transient on crash" true (Order.restart_ok Transient (Crash "x"))

let plan = Alcotest.(pair (list int) (list int))

let test_restart_plan () =
  Alcotest.check plan "one_for_one" ([], [ 2 ]) (Order.restart_plan One_for_one children 2);
  Alcotest.check plan "one_for_all: stop the others in stop order, start all but temporary"
    ([ 3; 1 ], [ 1; 2; 3 ])
    (Order.restart_plan One_for_all children 2);
  Alcotest.check plan "one_for_all from 1: the temporary child stays down" ([ 3; 2 ], [ 1; 3 ])
    (Order.restart_plan One_for_all children 1);
  Alcotest.check plan "rest_for_one" ([ 3 ], [ 2; 3 ]) (Order.restart_plan Rest_for_one children 2);
  Alcotest.check plan "not a child" ([], []) (Order.restart_plan One_for_all children 9)

let test_intensity () =
  let allow = function Order.Allow t -> Some t | Exceed _ -> None in
  Alcotest.(check (option (list int))) "within budget" (Some [ 5; 9 ])
    (allow (Order.intensity ~times:[ 5 ] ~now:9 ~period:10 ~max_r:2));
  Alcotest.(check (option (list int))) "old restarts fall out" (Some [ 20 ])
    (allow (Order.intensity ~times:[ 1; 2 ] ~now:20 ~period:10 ~max_r:1));
  Alcotest.(check (option (list int))) "one too many" None
    (allow (Order.intensity ~times:[ 5; 6 ] ~now:7 ~period:10 ~max_r:2))

(* ---- Kernel ------------------------------------------------------------------------- *)

let comp ?(requires = []) ?(provides = []) name iters =
  { Kernel.name; requires = keys requires; provides = keys provides; iters }

let it ?emit stages = { Kernel.stages; emit }
let db = comp "db" ~provides:[ "db" ] [ it [ Kernel.Provide ("db", 7) ] ]
let api = comp "api" ~requires:[ "db" ] [ it [ Kernel.Op ("db", Kernel.Add 1) ] ]
let insert reg n c = ok (Kernel.orchestrate reg (Kernel.Insert (n, c, None)))
let step reg n r = fst (ok (Kernel.apply reg n r))

let rec settle reg =
  match Kernel.enabled reg with [] -> reg | (n, r) :: _ -> settle (step reg n r)

let life_name reg n =
  match (Kernel.Fmap.find n reg).Kernel.life with
  | Kernel.Inactive -> "inactive"
  | Reloading _ -> "reloading"
  | Active _ -> "active"
  | Uncertain _ -> "uncertain"
  | Unloading _ -> "unloading"
  | Failed -> "failed"

let test_activation_order () =
  let reg = insert (insert Kernel.empty 2 api) 1 db in
  Alcotest.(check (list (pair int string)))
    "only the provider can begin" [ (1, "begin") ]
    (List.map (fun (n, r) -> (n, Kernel.rule_name r)) (Kernel.enabled reg));
  let reg = settle reg in
  Alcotest.(check bool) "quiet" true (Kernel.quiet reg);
  Alcotest.(check string) "consumer active" "active" (life_name reg 2);
  Alcotest.(check (list (triple int string int))) "consumer's Add lands in the provider's table"
    [ (1, "db", 8) ] (Kernel.bindings reg)

let test_guard () =
  let reg = settle (insert (insert Kernel.empty 1 db) 2 api) in
  let reg = ok (Kernel.orchestrate reg (Kernel.Retire 1)) in
  let reg = step reg 1 Kernel.Leave in
  Alcotest.(check bool) "provider is relied on" true (Kernel.relied reg 1);
  Alcotest.(check (option string)) "so it may not unload" None
    (Option.map Kernel.rule_name (Kernel.next reg 1));
  Alcotest.(check bool) "the apply is refused" true
    (Result.is_error (Kernel.apply reg 1 Kernel.Unload));
  let reg = settle reg in
  Alcotest.(check (list (triple int string int))) "everything reverted" [] (Kernel.bindings reg);
  Alcotest.(check string) "provider inactive" "inactive" (life_name reg 1)

let call = { Kernel.kind = "llm.complete"; ikey = "k"; compensate = Some "llm.forget" }
let emitter = comp "emitter" ~provides:[ "x" ] [ it ~emit:call [ Kernel.Provide ("x", 1) ] ]

let test_uncertain_blocks_unload () =
  let reg = insert Kernel.empty 1 emitter in
  let reg = step reg 1 Kernel.Begin in
  let reg, outs = ok (Kernel.apply reg 1 Kernel.Iter) in
  (match outs with
   | [ Kernel.Released (1, i) ] -> Alcotest.(check string) "episode in the key" "k@1" i.ikey
   | _ -> Alcotest.fail "expected one released intent");
  let reg = ok (Kernel.orchestrate reg (Kernel.Retire 1)) in
  Alcotest.(check (option string)) "inertial: nothing until the receipt" None
    (Option.map Kernel.rule_name (Kernel.next reg 1));
  let reg = ok (Kernel.orchestrate reg (Kernel.Receive (1, Kernel.Lost))) in
  Alcotest.(check string) "uncertain" "uncertain" (life_name reg 1);
  Alcotest.(check (option string)) "retired, but no rule moves an uncertain fiber" None
    (Option.map Kernel.rule_name (Kernel.next reg 1));
  let reg = ok (Kernel.orchestrate reg (Kernel.Reconcile (1, true))) in
  let reg = step reg 1 Kernel.Divert in
  let _, outs = ok (Kernel.apply reg 1 Kernel.Unload) in
  match outs with
  | [ Kernel.Compensated (1, c) ] ->
      Alcotest.(check string) "the compensation is issued" "llm.forget" c.kind
  | _ -> Alcotest.fail "expected one compensation"

let test_failed_receipt () =
  let reg = insert Kernel.empty 1 emitter in
  let reg = step (step reg 1 Kernel.Begin) 1 Kernel.Iter in
  let reg = ok (Kernel.orchestrate reg (Kernel.Receive (1, Kernel.Failed))) in
  let reg = step reg 1 Kernel.Unload in
  Alcotest.(check string) "failed" "failed" (life_name reg 1);
  Alcotest.(check (list (triple int string int))) "partial accumulator unwound" [] (Kernel.bindings reg);
  let reg = ok (Kernel.orchestrate reg (Kernel.Restart 1)) in
  Alcotest.(check string) "restarted" "inactive" (life_name reg 1);
  let reg = step reg 1 Kernel.Begin in
  let _, outs = ok (Kernel.apply reg 1 Kernel.Iter) in
  match outs with
  | [ Kernel.Released (_, i) ] -> Alcotest.(check string) "a new episode, a new key" "k@2" i.ikey
  | _ -> Alcotest.fail "expected one released intent"

let test_insert_checks () =
  let reg = insert Kernel.empty 1 db in
  Alcotest.(check bool) "second provider of db refused" true
    (Result.is_error (Kernel.orchestrate reg (Kernel.Insert (2, comp "db2" ~provides:[ "db" ] [], None))));
  Alcotest.(check bool) "undeclared key refused (confinement)" true
    (Result.is_error
       (Kernel.orchestrate reg (Kernel.Insert (3, comp "bad" [ it [ Kernel.Op ("db", Kernel.Add 1) ] ], None))));
  Alcotest.(check bool) "duplicate fid refused" true
    (Result.is_error (Kernel.orchestrate reg (Kernel.Insert (1, api, None))))

(* ---- Simulator campaigns ------------------------------------------------------------ *)

let campaign ?(cfg = Sim.default) ~runs ~scenario () =
  let a = Assertions.create () in
  Sim.declare_witnesses a;
  for seed = 1 to runs do
    ignore (Sim.run ~assertions:a cfg (scenario seed) (Oracle.splitmix seed))
  done;
  Assertions.verdict a

let failures v = List.filter_map (function Assertions.Fail s -> Some s | _ -> None) v.Assertions.lines

let test_chain_campaign () =
  let v = campaign ~runs:300 ~scenario:(fun _ -> Scenario.chain) () in
  Alcotest.(check (list string)) "no failures" [] (failures v);
  Alcotest.(check bool) "every witness reached" true
    (List.for_all (function Assertions.Warn _ -> false | _ -> true) v.lines)

let test_random_campaign () =
  let v =
    campaign ~runs:400
      ~scenario:(fun seed -> Scenario.random (Oracle.splitmix (seed * 7919)) ~fibers:5)
      ()
  in
  Alcotest.(check (list string)) "no failures" [] (failures v)

let test_blind_retry_caught () =
  let v =
    campaign ~cfg:{ Sim.default with mutant = Sim.Blind_retry } ~runs:300
      ~scenario:(fun _ -> Scenario.chain) ()
  in
  Alcotest.(check bool) "the mutant violates at-most-once" true
    (List.exists (fun s -> Astring_contains.contains s "at most once") (failures v))

let test_empty_campaign_fails () =
  let a = Assertions.create () in
  Alcotest.(check bool) "no runs is not a pass" true (Assertions.verdict a).failed

let test_replay () =
  let o, answers = Oracle.recording (Oracle.splitmix 42) in
  let r1 = Sim.run Sim.default Scenario.chain o in
  let r2 = Sim.run Sim.default Scenario.chain (Oracle.replay (answers ())) in
  Alcotest.(check (list string)) "a recorded run replays exactly"
    (List.map (fun (s : Itf.step) -> s.action) r1.steps)
    (List.map (fun (s : Itf.step) -> s.action) r2.steps)

(* Confluence (Cordis Theorem 80 / its quiescence result): with commuting
   operations on shared keys, every schedule quiesces to the same tables. *)
let fanin op =
  {
    Scenario.name = "fan-in";
    shapes =
      [
        Scenario.shape 1 ~provides:[ "acc" ] ~retirable:false;
        Scenario.shape 2 ~requires:[ "acc" ] ~iters:2 ~op ~retirable:false;
        Scenario.shape 3 ~requires:[ "acc" ] ~iters:2 ~op ~retirable:false;
      ];
    max_restarts = 3;
    period = 40;
  }

let outcomes sc =
  List.init 60 (fun seed -> Sim.quiescent_bindings sc (Oracle.splitmix seed))
  |> List.sort_uniq compare

let test_confluence () =
  match outcomes (fanin `Add) with
  | [ (true, _) ] -> ()
  | l -> Alcotest.failf "Add: %d distinct quiescent states" (List.length l)

let test_confluence_needs_commutativity () =
  Alcotest.(check bool) "Put on a shared key: schedules disagree (no commutativity witness)" true
    (List.length (outcomes (fanin `Put)) > 1)

let () =
  Alcotest.run "cordis"
    [
      ( "order",
        [
          Alcotest.test_case "restart_ok" `Quick test_restart_ok;
          Alcotest.test_case "restart_plan" `Quick test_restart_plan;
          Alcotest.test_case "intensity" `Quick test_intensity;
        ] );
      ( "kernel",
        [
          Alcotest.test_case "providers activate first" `Quick test_activation_order;
          Alcotest.test_case "the guard" `Quick test_guard;
          Alcotest.test_case "Uncertain blocks unload; compensation" `Quick test_uncertain_blocks_unload;
          Alcotest.test_case "failed receipt, restart, new episode" `Quick test_failed_receipt;
          Alcotest.test_case "insert checks" `Quick test_insert_checks;
        ] );
      ( "simulator",
        [
          Alcotest.test_case "chain campaign" `Quick test_chain_campaign;
          Alcotest.test_case "random campaign" `Slow test_random_campaign;
          Alcotest.test_case "blind retry is caught" `Quick test_blind_retry_caught;
          Alcotest.test_case "an empty campaign fails" `Quick test_empty_campaign_fails;
          Alcotest.test_case "record and replay" `Quick test_replay;
          Alcotest.test_case "confluence" `Quick test_confluence;
          Alcotest.test_case "confluence needs commutativity" `Quick test_confluence_needs_commutativity;
        ] );
    ]
