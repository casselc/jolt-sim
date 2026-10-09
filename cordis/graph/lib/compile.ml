(* Compilation of a checked graph to the kernel's components
   (docs/cordis/rfc/0002-graph-language.md, "Compilation to the kernel"),
   and a run of them under the kernel's rules.

   | Graph form          | Kernel                                                  |
   | input x / stream s  | a component providing x (s), from outside               |
   | asset a = c(x, y)   | a component requiring {x, y}, providing a and a's state  |
   | a turn of c         | an iteration; a turn ending in use k emits an intent k   |
   | the last turn       | binds a and writes the state keys (with state.f := e)    |
   | route s <~ e        | an extra iteration emitting route.append, an acquisition |

   The kernel models values as ints and has no conditionals, so the shape
   is what compiles: which keys a fiber needs and provides, how many turns
   it runs, and what it may emit. A route's guard and an asset's freshness
   ride along as metadata for the turn runtime (phase 3). *)

open Cordis_kernel
module K = Kernel

type compiled = {
  components : (K.fid * K.component) list;
  fid_of : (string * K.fid) list;  (** input, stream or asset name -> its fiber *)
  cell_of : (K.fid * string) list;  (** an asset's fiber -> its cell *)
}

let state_key asset field = asset ^ ".state." ^ field

let compile (cells : Check.cell_info list) (g : Check.graph_info) =
  let names = List.map fst g.inputs @ List.map fst g.streams @ List.map (fun (a : Check.asset_info) -> a.aname) g.assets in
  let fid_of = List.mapi (fun i n -> (n, i + 1)) names in
  let source name =
    { K.name = "source:" ^ name; requires = K.Keys.empty; provides = K.Keys.singleton name;
      iters = [ { K.stages = [ K.Provide (name, 0) ]; emit = None } ] }
  in
  let asset (a : Check.asset_info) =
    let ci = List.find (fun (c : Check.cell_info) -> c.cname = a.acell) cells in
    let state_keys = List.map (fun (f, _) -> state_key a.aname f) ci.state in
    let n = List.length ci.turns in
    let turn (t : Check.turn) =
      if t.index < n - 1 then
        { K.stages = [];
          emit = Option.map (fun k -> { K.kind = k; ikey = Printf.sprintf "%s:%d" a.aname t.index; compensate = None }) t.emits }
      else
        { K.stages = K.Provide (a.aname, 0) :: List.map (fun k -> K.Provide (k, 0)) state_keys; emit = None }
    in
    let appends =
      List.filter_map (fun (s, _, stream) -> if s = a.aname then Some stream else None) g.appends |> List.sort_uniq compare
    in
    let append stream =
      { K.stages = []; emit = Some { K.kind = "route.append"; ikey = Printf.sprintf "%s->%s" a.aname stream; compensate = None } }
    in
    { K.name = "asset:" ^ a.aname; requires = K.Keys.of_list a.args;
      provides = K.Keys.of_list (a.aname :: state_keys);
      iters = List.map turn ci.turns @ List.map append appends }
  in
  let components =
    List.map (fun (n, _) -> (List.assoc n fid_of, source n)) (g.inputs @ g.streams)
    @ List.map (fun (a : Check.asset_info) -> (List.assoc a.aname fid_of, asset a)) g.assets
  in
  List.iter
    (fun (_, c) -> match K.check_component c with Ok () -> () | Error m -> failwith (Printf.sprintf "compiled %s: %s" c.K.name m))
    components;
  { components; fid_of;
    cell_of = List.map (fun (a : Check.asset_info) -> (List.assoc a.aname fid_of, a.acell)) g.assets }

(* ---- a run --------------------------------------------------------------------------------------- *)

type event = Step of K.fid * K.rule | Released of K.fid * K.intent

type run = { trace : event list; registry : K.registry; steps : int }

(* Insert every component, then take enabled steps, the next one chosen by
   [pick n] among n, answering each released intent with a Definite
   receipt, until quiet (or [limit] steps). *)
let simulate ?(limit = 10_000) ~(pick : int -> int) (c : compiled) =
  let reg =
    List.fold_left
      (fun reg (fid, comp) ->
        match K.orchestrate reg (K.Insert (fid, comp, None)) with Ok r -> r | Error m -> failwith ("insert: " ^ m))
      K.empty c.components
  in
  let rec go reg trace steps =
    if steps >= limit then { trace = List.rev trace; registry = reg; steps }
    else
      (* a fiber awaiting a receipt gets it first: the outbox answers *)
      let awaiting =
        K.Fmap.fold
          (fun fid (f : K.fiber) acc -> match f.life with K.Reloading { awaiting = Some _; _ } -> fid :: acc | _ -> acc)
          reg []
      in
      match awaiting with
      | fid :: _ -> (
          match K.orchestrate reg (K.Receive (fid, K.Definite)) with
          | Ok reg -> go reg trace (steps + 1)
          | Error m -> failwith ("receive: " ^ m))
      | [] -> (
          match K.enabled reg with
          | [] -> { trace = List.rev trace; registry = reg; steps }
          | en ->
              let fid, rule = List.nth en (pick (List.length en)) in
              match K.apply reg fid rule with
              | Error m -> failwith ("apply: " ^ m)
              | Ok (reg, outs) ->
                  let released = List.filter_map (function K.Released (f, i) -> Some (Released (f, i)) | K.Compensated _ -> None) outs in
                  go reg (List.rev_append released (Step (fid, rule) :: trace)) (steps + 1))
  in
  go reg [] 0

(* ---- what a run must show ------------------------------------------------------------------------ *)

(* Quiet, every asset provided, each before-invariant held (every fiber of
   the later cell began only after a fiber of the earlier cell finished),
   and only intents the cells' effect rows allow (plus route.append). *)
let verify (cells : Check.cell_info list) (g : Check.graph_info) (c : compiled) (r : run) =
  let problems = ref [] in
  let say fmt = Printf.ksprintf (fun m -> problems := m :: !problems) fmt in
  if not (K.quiet r.registry) then say "not quiet after %d steps" r.steps;
  List.iter
    (fun (a : Check.asset_info) -> if K.provider r.registry a.aname = None then say "asset %s is not provided" a.aname)
    g.assets;
  let index_of pred = let rec go i = function [] -> None | e :: rest -> if pred e then Some i else go (i + 1) rest in go 0 r.trace in
  List.iter
    (fun (c1, c2) ->
      let fids name = List.filter_map (fun (f, cell) -> if cell = name then Some f else None) c.cell_of in
      List.iter
        (fun f2 ->
          match index_of (function Step (f, K.Begin) -> f = f2 | _ -> false) with
          | None -> say "%s's fiber %d never began" c2 f2
          | Some b ->
              let finished_before = List.exists (fun f1 -> match index_of (function Step (f, K.Finish) -> f = f1 | _ -> false) with Some e -> e < b | None -> false) (fids c1) in
              if not finished_before then say "invariant %s before %s: fiber %d began before any %s finished" c1 c2 f2 c1)
        (fids c2))
    g.befores;
  List.iter
    (function
      | Released (f, i) -> (
          match List.assoc_opt f c.cell_of with
          | Some cell ->
              let ci = List.find (fun (x : Check.cell_info) -> x.cname = cell) cells in
              if i.K.kind <> "route.append" && not (List.mem i.K.kind ci.effects) then
                say "fiber %d emitted %s, outside %s's effect row" f i.K.kind cell
          | None -> say "a source emitted %s" i.K.kind)
      | Step _ -> ())
    r.trace;
  List.rev !problems
