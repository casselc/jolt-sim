(* The deterministic simulator: a shell around the pure kernel in which the
   oracle makes every choice. Each step it lists what could happen next — a
   lifecycle rule some fiber has enabled, the outbox answering a released
   intent, a reconcile of an Uncertain fiber, a supervisor's restart, a
   scripted retirement — and asks the oracle to pick one. Faults (failed and
   lost receipts, and whether a lost intent took effect) are oracle draws as
   well, so a run is a function of the scenario and the oracle's answers. *)

open Cordis_kernel

type faults = { fail_pct : int; lost_pct : int; lost_took_pct : int }

let no_faults = { fail_pct = 0; lost_pct = 0; lost_took_pct = 0 }
let default_faults = { fail_pct = 10; lost_pct = 20; lost_took_pct = 50 }

type mutant =
  | Design
  | Blind_retry  (** resend an Uncertain intent instead of reconciling it *)

type config = {
  faults : faults;
  mutant : mutant;
  retire : bool;  (** offer scripted retirements of retirable fibers *)
  max_steps : int;
}

let default = { faults = default_faults; mutant = Design; retire = true; max_steps = 400 }

type action =
  | Step of Kernel.fid * Kernel.rule
  | Deliver of Kernel.fid
  | Resolve of Kernel.fid
  | Supervise of Kernel.fid
  | Retire of Kernel.fid

type outcome = {
  registry : Kernel.registry;
  steps : Itf.step list;  (** the trace, initial state first *)
  quiesced : bool;  (** no action was left before max_steps *)
  outputs : Kernel.output list;  (** everything the kernel handed the outbox, in order *)
}

(* The world on the far side of the outbox. *)
type world = {
  mutable applied : (string * int) list;  (** intent key -> times it took effect *)
  mutable hidden : (int * bool) list;  (** fiber -> did its last lost intent take effect *)
  mutable restarts : (int * int list) list;  (** fiber -> restart times *)
  mutable given_up : int list;  (** Failed fibers the supervisor gave up on *)
}

let bump w ikey =
  let c = try List.assoc ikey w.applied with Not_found -> 0 in
  w.applied <- (ikey, c + 1) :: List.remove_assoc ikey w.applied

(* applied, keyed as the model keys it: iid * 1000 + episode *)
let world_itf (sc : Scenario.t) w =
  let applied =
    List.map
      (fun (k, c) ->
        Scanf.sscanf k "%d:%d@%d" (fun fid i ep -> ((Scenario.iid fid i * 1000) + ep, c)))
      w.applied
    |> List.sort compare
  in
  let hidden =
    List.map
      (fun (s : Scenario.shape) -> (s.fid, try List.assoc s.fid w.hidden with Not_found -> false))
      sc.shapes
  in
  { Itf.applied; hidden }

let ok = function Ok x -> x | Error e -> failwith e

let pending_intent reg n =
  match (Kernel.Fmap.find n reg).Kernel.life with
  | Kernel.Reloading { awaiting = Some i; _ } -> Some i
  | Kernel.Uncertain { pending; _ } -> Some pending
  | _ -> None

let actions cfg (sc : Scenario.t) reg w (o : Oracle.t) =
  let steps = List.map (fun (n, r) -> Step (n, r)) (Kernel.enabled reg) in
  (* Retirements are offered once the registry is quiet, and otherwise only
     on a 1-in-10 draw, so that runs reach steady state before most of them
     (a retirement offered every step ends most runs at once). *)
  let retire_now =
    cfg.retire
    && List.exists
         (fun (s : Scenario.shape) -> s.retirable && not (Kernel.Fmap.find s.fid reg).retired)
         sc.shapes
    && (Kernel.quiet reg || o.int 10 = 0)
  in
  let per_fiber =
    List.concat_map
      (fun (s : Scenario.shape) ->
        let f = Kernel.Fmap.find s.fid reg in
        let outbox =
          match f.life with
          | Kernel.Reloading { awaiting = Some _; _ } -> [ Deliver s.fid ]
          | Kernel.Uncertain _ -> [ Resolve s.fid ]
          | Kernel.Failed when not (f.retired || List.mem s.fid w.given_up) -> [ Supervise s.fid ]
          | _ -> []
        in
        let retire = if retire_now && s.retirable && not f.retired then [ Retire s.fid ] else [] in
        outbox @ retire)
      sc.shapes
  in
  steps @ per_fiber

let committed_of_life = function
  | Kernel.Reloading { committed; _ }
  | Kernel.Active { committed; _ }
  | Kernel.Uncertain { committed; _ }
  | Kernel.Unloading { committed; _ } ->
      Some committed
  | _ -> None

(* Properties checked in every state, in the Antithesis vocabulary. *)
let check (a : Assertions.t) (sc : Scenario.t) reg w =
  let open Kernel in
  let installed n = Kernel.installed reg n in
  (* Cordis Theorem 70 (ordering): a provider outlives its consumers *)
  Fmap.iter
    (fun _ f ->
      match committed_of_life f.life with
      | Some v -> Assertions.always a (Kmap.for_all (fun _ m -> installed m) v)
                    "guard: an installed fiber's committed view names only installed providers"
      | None -> ())
    reg;
  Assertions.always a
    (List.for_all (fun (_, c) -> c <= 1) w.applied)
    "at most once: no emission takes effect twice";
  Fmap.iter
    (fun n f ->
      if not (installed n) then
        Assertions.always a (Kmap.is_empty f.table)
          "terminal recovery: a fiber that is not installed holds no bindings";
      (match f.life with
       | Active _ ->
           (* with no installed consumer, a provider's values are as if no
              consumer had ever run (Cordis Theorem 68, per binding) *)
           let consumed k =
             Fmap.exists
               (fun m g ->
                 m <> n
                 && match committed_of_life g.life with
                    | Some v -> Kmap.find_opt k v = Some n
                    | None -> false)
               reg
           in
           Kmap.iter
             (fun k v ->
               if not (consumed k) then
                 Assertions.always a
                   (v = Scenario.provided_value n)
                   "terminal recovery: an unconsumed binding holds its provided value")
             f.table
       | _ -> ());
      Assertions.sometimes a
        (match f.life with Uncertain _ -> true | _ -> false)
        "a fiber is Uncertain";
      Assertions.sometimes a
        (match f.life with Unloading _ -> relied reg n | _ -> false)
        "the guard holds a provider in Unloading";
      Assertions.sometimes a (f.life = Failed) "a fiber failed")
    reg;
  ignore sc

let declare_witnesses a =
  List.iter (Assertions.declare_witness a)
    [
      "a fiber is Uncertain";
      "the guard holds a provider in Unloading";
      "a fiber failed";
      "reconcile found the lost intent took effect";
      "reconcile found the lost intent did not take effect";
      "a supervisor restarted a fiber";
      "a compensation was issued";
      "the run quiesced with every retirable fiber retired";
    ]

let run ?(assertions = Assertions.create ()) cfg (sc : Scenario.t) (o : Oracle.t) =
  let a = assertions in
  let w = { applied = []; hidden = []; restarts = []; given_up = [] } in
  let reg =
    List.fold_left
      (fun reg (s : Scenario.shape) ->
        ok (Kernel.orchestrate reg (Kernel.Insert (s.fid, Scenario.component s, None))))
      Kernel.empty sc.shapes
  in
  Assertions.setup_complete a;
  let record action picks reg = { Itf.action; picks; state = Itf.vars sc reg (world_itf sc w) } in
  let outputs = ref [] in
  let rec loop i reg trace =
    check a sc reg w;
    match actions cfg sc reg w o with
    | [] -> (reg, List.rev trace, true)
    | _ when i >= cfg.max_steps -> (reg, List.rev trace, false)
    | acts ->
        let act = Oracle.choose o acts in
        let reg, step =
          match act with
          | Step (n, r) ->
              let reg, outs = ok (Kernel.apply reg n r) in
              List.iter
                (function
                  | Kernel.Compensated _ as c ->
                      Assertions.reachable a "a compensation was issued";
                      outputs := c :: !outputs
                  | Kernel.Released _ as r -> outputs := r :: !outputs)
                outs;
              (reg, Some (record (Kernel.rule_name r) [ ("n", Itf.int n) ] reg))
          | Deliver n ->
              let i = Option.get (pending_intent reg n) in
              let f = cfg.faults in
              let roll = o.int 100 in
              let (r : Kernel.receipt), took =
                if roll < f.fail_pct then (Kernel.Failed, false)
                else if roll < f.fail_pct + f.lost_pct then (Kernel.Lost, Oracle.chance o f.lost_took_pct)
                else (Kernel.Definite, true)
              in
              if took then bump w i.ikey;
              if r = Kernel.Lost then w.hidden <- (n, took) :: List.remove_assoc n w.hidden;
              let reg = ok (Kernel.orchestrate reg (Kernel.Receive (n, r))) in
              let name = match r with Kernel.Definite -> "definite" | Kernel.Failed -> "failed" | Kernel.Lost -> "lost" in
              ( reg,
                Some
                  (record "deliver"
                     [ ("n", Itf.int n); ("r", `String name); ("took", `Bool (r = Kernel.Lost && took)) ]
                     reg) )
          | Resolve n -> (
              let i = Option.get (pending_intent reg n) in
              match cfg.mutant with
              | Design ->
                  let took = try List.assoc n w.hidden with Not_found -> false in
                  Assertions.reachable a
                    (if took then "reconcile found the lost intent took effect"
                     else "reconcile found the lost intent did not take effect");
                  let reg = ok (Kernel.orchestrate reg (Kernel.Reconcile (n, took))) in
                  (reg, Some (record "reconcile" [ ("n", Itf.int n); ("took", `Bool took) ] reg))
              | Blind_retry ->
                  let took = Oracle.chance o 70 in
                  if took then bump w i.ikey;
                  let reg = ok (Kernel.orchestrate reg (Kernel.Reconcile (n, true))) in
                  (reg, Some (record "reconcile" [ ("n", Itf.int n); ("took", `Bool took) ] reg)))
          | Supervise n -> (
              let s = List.find (fun (s : Scenario.shape) -> s.fid = n) sc.shapes in
              let times = try List.assoc n w.restarts with Not_found -> [] in
              let verdict =
                if Order.restart_ok s.restart (Order.Crash "failed receipt") then
                  Order.intensity ~times ~now:i ~period:sc.period ~max_r:sc.max_restarts
                else Order.Exceed times
              in
              match verdict with
              | Order.Allow times ->
                  w.restarts <- (n, times) :: List.remove_assoc n w.restarts;
                  Assertions.reachable a "a supervisor restarted a fiber";
                  let reg = ok (Kernel.orchestrate reg (Kernel.Restart n)) in
                  (reg, Some (record "restart" [ ("n", Itf.int n) ] reg))
              | Order.Exceed _ ->
                  (* give up: the fiber stays Failed, and is offered no more *)
                  Assertions.reachable a "a supervisor gave up on a fiber";
                  w.given_up <- n :: w.given_up;
                  (reg, None))
          | Retire n ->
              let reg = ok (Kernel.orchestrate reg (Kernel.Retire n)) in
              (reg, Some (record "retire" [ ("n", Itf.int n) ] reg))
        in
        loop (i + 1) reg (match step with Some s -> s :: trace | None -> trace)
  in
  let init = record "init" [] reg in
  let reg, steps, quiesced = loop 0 reg [ init ] in
  Assertions.always a quiesced "progress: the run quiesces (no step left) within its bound";
  if quiesced then begin
    Assertions.always a (Kernel.quiet reg) "progress: a run with no step left is quiet";
    let all_retired =
      List.for_all
        (fun (s : Scenario.shape) -> (not s.retirable) || (Kernel.Fmap.find s.fid reg).retired)
        sc.shapes
    in
    Assertions.sometimes a all_retired "the run quiesced with every retirable fiber retired"
  end;
  Assertions.end_run a;
  { registry = reg; steps; quiesced; outputs = List.rev !outputs }

(* Run a scenario to quiescence with no faults and no retirements, and
   return every binding: confluence says this is the same for every
   schedule when the operations on shared keys commute. *)
let quiescent_bindings sc o =
  let r = run { default with faults = no_faults; retire = false } sc o in
  (r.quiesced, Kernel.bindings r.registry)

let write_itf ~spec ?(guard = true) ?(reconcile = true) ~path ~description sc (r : outcome) =
  Itf.write path
    (Itf.trace ~spec ~module_:"lifecycle"
       ~constants:(Scenario.quint_constants ~guard ~reconcile sc)
       ~invariants:[ "safety" ] ~actor:sc.Scenario.name ~description r.steps)
