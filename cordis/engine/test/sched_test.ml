(* Fairness across graphs (docs/cordis/rfc/0006-turn-runtime.md, decision
   4; quint/schedule.qnt): the deficit round robin scheduler, alone and over
   several review loops sharing one loop.

   Alone: graphs whose work comes and goes and whose steps cost what is
   drawn; a graph with work is never passed over more than Sched.bound
   picks (schedule.qnt's boundedWait), and deficits stay in range.
   Over engines: several instances of the review loop, each with an input
   of its own, share one scheduler, with the host's events (syncs, sends,
   deliveries, lost and duplicated receipts) interleaved at drawn points;
   each rests where it would alone (schedule.qnt's sameRest: the schedule
   across graphs is one more schedule) and none waits past the bound. *)

open Cordis_engine
open Loopback
module V = Cordis_cel.Value
module E = Engine

let settings =
  let cases = match Sys.getenv_opt "ENGINE_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  let seed = match Sys.getenv_opt "ENGINE_PROPS_SEED" with Some s -> int_of_string s | None -> 41 in
  Hegel.Settings.create ~test_cases:cases ~seed ()

(* the waits after one pick: a graph with work that was not picked waits one more *)
let wait waited ~has_work picked =
  Array.iteri (fun g w -> waited.(g) <- (if Some g = picked || not (has_work g) then 0 else w + 1)) waited

(* schedule.qnt's own instance: graph 0 never rests and costs 3, the others cost 1, quantum 2 *)
let model_instance () =
  let work = [| true; true; false |] and waited = Array.make 3 0 and worst = ref 0 and carried = ref false in
  let s = ref (Sched.create ~quantum:2 3) in
  for i = 0 to 299 do
    if i mod 7 = 3 then work.(2) <- true;
    let cost g = if work.(g) then Some (if g = 0 then 3 else 1) else None in
    let p, s' = Sched.pick !s ~cost in
    wait waited ~has_work:(fun g -> work.(g)) p;
    s := s';
    (match p with Some g when Sched.deficit s' g > 0 -> carried := true | _ -> ());
    (match p with Some g when g > 0 && i mod 5 = 0 -> work.(g) <- false | _ -> ());
    if i mod 11 = 0 then work.(1) <- true;
    worst := Array.fold_left max !worst waited
  done;
  (* with each graph's cost fixed the wait is schedule.qnt's BOUND, 8; Sched.bound also covers costs that
     vary from step to step, where a deficit carried from a dear step pays for several cheap ones *)
  Alcotest.(check bool) "Sched.bound covers BOUND" true (Sched.bound ~quantum:2 ~max_cost:3 3 >= 8);
  Alcotest.(check bool) (Printf.sprintf "waits within 8 (worst %d)" !worst) true (!worst <= 8);
  Alcotest.(check bool) "a deficit was carried" true !carried

(* strict priority, schedule.qnt's mutant, starves graph 1 behind a graph 0 that never rests *)
let strict_starves () =
  let waited = Array.make 2 0 in
  for _ = 1 to 50 do wait waited ~has_work:(fun _ -> true) (Some 0) done;
  Alcotest.(check bool) "strict priority passes graph 1 over past the bound" true (waited.(1) > Sched.bound ~quantum:2 ~max_cost:3 2)

let alone () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:engine:sched" (fun tc ->
      let int lo hi = Hegel.draw tc (Hegel.Generators.integers ~min_value:lo ~max_value:hi ()) in
      let n = int 1 5 and quantum = int 1 6 and max_cost = int 1 8 in
      let work = Array.init n (fun _ -> int 0 1 = 1) and costs = Array.init n (fun _ -> int 1 max_cost) in
      let waited = Array.make n 0 and s = ref (Sched.create ~quantum n) in
      let bound = Sched.bound ~quantum ~max_cost n in
      for _ = 1 to 200 do
        let cost g = if work.(g) then Some costs.(g) else None in
        let p, s' = Sched.pick !s ~cost in
        Hegel.require tc ~msg:"a graph had work and none was picked" (p <> None || Array.for_all not work);
        wait waited ~has_work:(fun g -> work.(g)) p;
        s := s';
        Array.iteri
          (fun g w -> Hegel.require tc ~msg:(Printf.sprintf "graph %d waited %d picks, over the bound %d" g w bound) (w <= bound))
          waited;
        for g = 0 to n - 1 do
          let d = Sched.deficit !s g in
          Hegel.require tc ~msg:(Printf.sprintf "graph %d's deficit %d out of range" g d) (d >= 0 && d < quantum + max_cost)
        done;
        (* the picked graph's step may end its work or change its next cost; work arrives anywhere *)
        (match p with
         | Some g ->
             if int 0 3 = 0 then work.(g) <- false;
             costs.(g) <- int 1 max_cost
         | None -> ());
        if int 0 3 = 0 then work.(int 0 (n - 1)) <- true
      done)

(* ---- over engines ---------------------------------------------------------------------------- *)

let input i = diff (Printf.sprintf "src/m%d.ml" i) (Printf.sprintf "let x = %d" i)

let solo i =
  let h = start ~input:(input i) () in
  ignore (run ~pick:(fun _ -> 0) h);
  snapshot h

let engines () =
  let references = Array.init 4 solo in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:engine:sched-engines" (fun tc ->
      let draw n = if n <= 1 then 0 else Hegel.draw tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let n = 2 + draw 3 and quantum = 1 + draw 400 in
      let losses = ref (draw 3) and dups = ref (draw 4) in
      let faults =
        { lose = (fun () -> !losses > 0 && draw 3 = 0 && (decr losses; true)); dup = (fun () -> !dups > 0 && draw 3 = 0 && (decr dups; true)) }
      in
      let hosts = Array.init n (fun i -> start ~input:(input i) ()) in
      (* each graph's next step, drawn once and kept until it is taken or no longer ready *)
      let heads = Array.make n None in
      let head g =
        let r = E.ready hosts.(g).t in
        match heads.(g) with
        | Some a when List.mem a r -> Some a
        | _ ->
            let a = match r with [] -> None | r -> Some (List.nth r (draw (List.length r))) in
            heads.(g) <- a;
            a
      in
      let s = ref (Sched.create ~quantum n) and waited = Array.make n 0 and worst = ref 0 and max_cost = ref 1 in
      let events g = List.filter (function Act _ | Change_input | Crash -> false | _ -> true) (choices hosts.(g) ~change:false) in
      let rec go k =
        if k > 20000 then failwith "no rest"
        else
          let pending = List.concat_map (fun g -> List.map (fun c -> (g, c)) (events g)) (List.init n Fun.id) in
          let any_ready = Array.exists (fun h -> E.ready h.t <> []) hosts in
          if pending = [] && not any_ready then ()
          else if pending <> [] && ((not any_ready) || draw 2 = 0) then begin
            let g, c = List.nth pending (draw (List.length pending)) in
            take hosts.(g) faults ~next_input:(fun () -> V.Null) c;
            go (k + 1)
          end
          else begin
            let cost g =
              Option.map (fun a -> let c = E.step_cost hosts.(g).t a in max_cost := max !max_cost c; c) (head g)
            in
            let p, s' = Sched.pick !s ~cost in
            wait waited ~has_work:(fun g -> head g <> None) p;
            worst := Array.fold_left max !worst waited;
            s := s';
            (match p with
             | Some g ->
                 let a = Option.get (head g) in
                 heads.(g) <- None;
                 take hosts.(g) faults ~next_input:(fun () -> V.Null) (Act a)
             | None -> failwith "a graph was ready and none was picked");
            go (k + 1)
          end
      in
      go 0;
      Array.iteri
        (fun i h ->
          Hegel.require tc ~msg:(Printf.sprintf "instance %d: %s" i (String.concat "; " (List.map (fun (a, m) -> a ^ ": " ^ m) (E.failures h.t))))
            (E.failures h.t = []);
          Hegel.require tc ~msg:(Printf.sprintf "instance %d rests other than alone" i) (snapshot h = references.(i));
          Hegel.require tc ~msg:(Printf.sprintf "instance %d: spend: %s" i (String.concat "; " h.spend_errors)) (h.spend_errors = []))
        hosts;
      let bound = Sched.bound ~quantum ~max_cost:!max_cost n in
      Hegel.require tc ~msg:(Printf.sprintf "a graph waited %d picks, over the bound %d" !worst bound) (!worst <= bound))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "engine-sched"
    [ ( "examples",
        [ Alcotest.test_case "schedule.qnt's instance: waits within BOUND, a deficit carried" `Quick model_instance;
          Alcotest.test_case "strict priority starves (the mutant)" `Quick strict_starves ] );
      ( "properties",
        [ Alcotest.test_case "alone: bounded wait, deficits in range" `Quick alone;
          Alcotest.test_case "over review loops: each rests as alone, bounded wait" `Quick engines ] ) ]
