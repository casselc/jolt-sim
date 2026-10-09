(* The graph language's checker (docs/cordis/rfc/0002-graph-language.md,
   "Cell bodies" and "Graphs").

   Types resolve to the CEL checker's types (records and variants included),
   so every CEL expression in a definition is checked by RFC 0001's checker
   in an environment holding exactly what the definition may name. Cells
   are checked statement by statement and split into turns at each use;
   each turn's cost is estimated, as one CEL program, with size bounds from
   the parameter types. Graphs are checked for wiring (with type variables
   unified per use and width subtyping for open records), acyclicity with
   streams removed, exhaustive and well-typed routes, and invariants.

   Errors are collected per definition, each prefixed with where it is. *)

open Syntax
module C = Cordis_cel.Checker
module Ast = Cordis_cel.Ast

exception Fail of string

let fail fmt = Printf.ksprintf (fun m -> raise (Fail m)) fmt

type turn = {
  index : int;
  emits : string option;  (** the effect kind this turn waits on; None for the last *)
  pending : bool;  (** a hole in it: it does not run *)
  cost : Cordis_cel.Cost.estimate option;  (** None when pending *)
  program : Ast.expr option;  (** the turn as one lowered CEL program, when not pending *)
}

type cell_info = {
  cname : string;
  params : (string * C.t) list;
  result : C.t;
  effects : string list;
  state : (string * C.t) list;
  turns : turn list;
  holes : string list;
}

type asset_info = {
  aname : string;
  acell : string;
  aty : C.t;
  args : string list;
  deps : string list;  (** the assets and inputs it reads, directly *)
  guards : (string * string) list;  (** (scrutinee, case): computes only when the scrutinee has the case *)
  fresh : fresh;
}

type graph_info = {
  gname : string;
  inputs : (string * C.t) list;
  streams : (string * C.t) list;
  assets : asset_info list;  (** in dependency order *)
  appends : (string * string * string) list;  (** (scrutinee, case, stream) *)
  effect_row : string list;
}

type checked = { cells : cell_info list; graphs : graph_info list; types : (string * C.t) list }

type options = {
  libraries : string -> string option;  (** library text by hash *)
  default_size : int;  (** the size bound of an unbounded string, bytes, list or map *)
  budget : int;  (** a turn's cost ceiling *)
}

let default_options = { libraries = (fun _ -> None); default_size = 1024; budget = 1_000_000 }

(* ---- types ----------------------------------------------------------------------------------- *)

type env = {
  defs : (string * item) list;
  options : options;
  libs : (string * Cordis_cel.Library.t) list;
}

let rec resolve env ?(visiting = []) ?(open_ok = false) (t : ty) : C.t =
  let r = resolve env ~visiting ~open_ok:false in
  match t with
  | Scalar (n, _) -> (
      match n with
      | "int" -> C.Int | "uint" -> C.Uint | "double" -> C.Double | "string" -> C.String | "bytes" -> C.Bytes
      | "bool" -> C.Bool | "timestamp" -> C.Timestamp | "duration" -> C.Duration | _ -> C.Dyn)
  | List (t, _) -> C.List (r t)
  | Map (k, v, _) ->
      let kt = r k in
      (match kt with C.Int | C.Uint | C.String | C.Bool | C.Dyn -> () | _ -> fail "a map key must be int, uint, string or bool, not %s" (C.to_string kt));
      C.Map (kt, r v)
  | Option t -> C.Opt (r t)
  | Record (fs, open_) ->
      if open_ && not open_ok then fail "an open record { ..; .. } is allowed only as a cell parameter";
      C.Record (List.map (fun (f, t) -> (f, r t)) fs, open_)
  | Var v -> C.Opaque ("'" ^ v, [])
  | Named n -> (
      if List.mem n visiting then fail "type %s refers to itself (recursion goes through a reference, RFC 0003)" n;
      match List.assoc_opt n env.defs with
      | Some (Type_def (_, Alias t)) -> resolve env ~visiting:(n :: visiting) ~open_ok t
      | Some (Type_def (_, Variant cs)) ->
          C.Variant (List.map (fun (c, p) -> (c, Option.map (resolve env ~visiting:(n :: visiting)) p)) cs)
      | Some _ -> fail "%s is not a type" n
      | None -> fail "unknown type %s" n)

(* The size bound of a value of this type: the largest <max N> in it when
   every string, bytes, list and map in it has one, else the default. *)
let rec size_bound env ?(visiting = []) (t : ty) =
  let d = env.options.default_size in
  let sb = size_bound env ~visiting in
  match t with
  | Scalar (("string" | "bytes"), b) -> Option.value b ~default:d
  | Scalar _ | Var _ -> 1
  | List (t, b) -> max (Option.value b ~default:d) (sb t)
  | Map (k, v, b) -> max (Option.value b ~default:d) (max (sb k) (sb v))
  | Option t -> sb t
  | Record (fs, _) -> List.fold_left (fun a (_, t) -> max a (sb t)) 1 fs
  | Named n when List.mem n visiting -> d
  | Named n -> (
      match List.assoc_opt n env.defs with
      | Some (Type_def (_, Alias t)) -> size_bound env ~visiting:(n :: visiting) t
      | Some (Type_def (_, Variant cs)) ->
          List.fold_left (fun a (_, p) -> match p with Some t -> max a (size_bound env ~visiting:(n :: visiting) t) | None -> a) 7 cs
      | _ -> d)

(* The size shape of a value of this type, for the cost estimator: each
   level bounded by its own <max N> (or the default). A record is a map
   with a key per field; a variant a map of a tag and a value. *)
let rec shape env ?(visiting = []) (t : ty) : Cordis_cel.Cost.shape =
  let module K = Cordis_cel.Cost in
  let d = env.options.default_size in
  let sh = shape env ~visiting in
  let b = function Some n -> n | None -> d in
  let names ns = K.sized 0 (List.fold_left (fun a n -> max a (String.length n)) 1 ns) in
  let join_all = List.fold_left K.join K.bottom in
  match t with
  | Scalar (("string" | "bytes"), n) -> K.sized 0 (b n)
  | Scalar ("dyn", _) | Var _ -> K.bounded d
  | Scalar _ -> K.scalar
  | List (t, n) -> { K.lo = 0; hi = b n; elem = lazy (sh t); key = lazy K.scalar }
  | Map (k, v, n) -> { K.lo = 0; hi = b n; elem = lazy (sh v); key = lazy (sh k) }
  | Option t -> { K.scalar with elem = lazy (sh t) }
  | Record (fs, _) ->
      let n = List.length fs in
      { K.lo = n; hi = n; elem = lazy (join_all (List.map (fun (_, t) -> sh t) fs)); key = lazy (names (List.map fst fs)) }
  | Named n when List.mem n visiting -> K.bounded d
  | Named n -> (
      match List.assoc_opt n env.defs with
      | Some (Type_def (_, Alias t)) -> shape env ~visiting:(n :: visiting) t
      | Some (Type_def (_, Variant cs)) ->
          let payloads = List.filter_map snd cs in
          { K.lo = 1; hi = 2;
            elem = lazy (join_all (names (List.map fst cs) :: List.map (shape env ~visiting:(n :: visiting)) payloads));
            key = lazy (names [ "tag"; "value" ]) }
      | _ -> K.bounded d)

(* ---- CEL expressions ------------------------------------------------------------------------- *)

let named_types env =
  List.filter_map
    (fun (n, i) ->
      match i with
      | Type_def (_, Variant _) -> Some (n, resolve env (Named n))
      | Type_def (_, Alias t) -> (match resolve env t with C.Record _ as r -> Some (n, r) | _ -> None)
      | _ -> None)
    env.defs

let records env = List.filter_map (fun (n, t) -> match t with C.Record _ -> Some n | _ -> None) (named_types env)
let variants env = List.filter_map (fun (n, t) -> match t with C.Variant _ -> Some n | _ -> None) (named_types env)

(* The environment of a definition's CEL: named types, library signatures,
   and the given variables. *)
let cel_env env vars =
  let e = List.fold_left (fun e (n, t) -> C.declare_type e n t) (C.empty ()) (named_types env) in
  let e = List.fold_left (fun e (alias, lib) -> Cordis_cel.Library.declare ~prefix:(alias ^ ".") e lib) e env.libs in
  List.fold_left (fun e (n, t) -> C.declare_ident e n t) e vars

let check_cel cenv what = function
  | Hole _ -> C.Dyn
  | Cel { ast; text } -> (
      match C.check cenv ast with Ok t -> t | Error m -> fail "%s: %s (in %s)" what m text)

let expect_assignable what want got =
  if not (C.assignable (C.empty ()) want got) then fail "%s has type %s, not %s" what (C.to_string got) (C.to_string want)

(* Fresh, unique node ids across expressions parsed separately. *)
let renumber (e : Ast.expr) =
  let next = ref 0 in
  let rec go (e : Ast.expr) = let e = Cordis_cel.Library.map_children go e in incr next; { e with id = !next } in
  go e

(* ---- cells ----------------------------------------------------------------------------------- *)

let effect_sig env k =
  match List.assoc_opt k env.defs with
  | Some (Effect (_, req, rcpt)) -> (resolve env req, resolve env rcpt)
  | _ -> fail "unknown effect %s" k

let check_cell env (c : Syntax.cell) =
  let where fmt = Printf.ksprintf (fun m -> m) fmt in
  let names = List.map fst c.params in
  List.iter (fun n -> if List.length (List.filter (( = ) n) names) > 1 then fail "duplicate parameter %s" n) names;
  List.iter (fun n -> if n = "state" then fail "a parameter cannot be named state") names;
  let params = List.map (fun (p, t) -> (p, resolve env ~open_ok:true t)) c.params in
  let result = resolve env c.result in
  List.iter (fun k -> ignore (effect_sig env k)) c.effects;
  (* state: fields initialised by constant expressions *)
  let state = List.map (fun (f, t, _) -> (f, resolve env t)) c.state in
  List.iter
    (fun (f, _, init) ->
      let t = List.assoc f state in
      expect_assignable (where "state.%s's initial value" f) t (check_cel (cel_env env []) (where "state.%s" f) init))
    c.state;
  let state_t = C.Record (state, false) in
  (* statements in order; each use ends a turn *)
  let holes = ref [] in
  let note_hole = function Hole h -> holes := h :: !holes | Cel _ -> () in
  let vars = ref (params @ [ ("state", state_t) ]) in
  let declared = Hashtbl.create 8 in
  let declare x t =
    if Hashtbl.mem declared x || List.mem_assoc x params || x = "state" then fail "%s is already defined" x;
    Hashtbl.add declared x ();
    vars := !vars @ [ (x, t) ]
  in
  List.iter
    (function
      | Let (x, e) ->
          note_hole e;
          let t = check_cel (cel_env env !vars) (where "let %s" x) e in
          declare x t
      | Use (x, k, e) ->
          note_hole e;
          if not (List.mem k c.effects) then fail "use %s: %s is not in the cell's effect row" x k;
          let req, rcpt = effect_sig env k in
          let t = check_cel (cel_env env !vars) (where "use %s" x) e in
          expect_assignable (where "use %s's request" x) req t;
          declare x rcpt)
    c.body;
  note_hole c.final;
  let ft = check_cel (cel_env env !vars) "the result" c.final in
  (match c.final with Hole _ -> () | Cel _ -> expect_assignable "the result" result ft);
  List.iter
    (fun (f, e) ->
      note_hole e;
      match List.assoc_opt f state with
      | None -> fail "with state.%s: no such state field" f
      | Some t -> (
          let u = check_cel (cel_env env !vars) (where "state.%s" f) e in
          match e with Hole _ -> () | Cel _ -> expect_assignable (where "state.%s's update" f) t u))
    c.updates;
  (* the turns: each, as one CEL program over the inputs, the state and
     the receipts so far, re-binding the lets before its end *)
  let uses = List.filter_map (function Use (x, k, e) -> Some (x, k, e) | Let _ -> None) c.body in
  let n_turns = List.length uses + 1 in
  let recs = records env and vars_ = variants env in
  let lowered = function
    | Hole _ -> None
    | Cel { ast; _ } -> Some (Lower.lower ~records:recs ~variants:vars_ ast)
  in
  let mk kind = { Ast.id = 0; kind } in
  let turn i =
    (* statements before the turn's end, and the receipts available *)
    let rec upto acc receipts k = function
      | [] -> (List.rev acc, receipts, None)
      | Let (x, e) :: rest -> upto ((x, e) :: acc) receipts k rest
      | Use (x, kind, e) :: rest -> if k = i then (List.rev acc, receipts, Some (kind, e)) else upto acc ((x, snd (effect_sig env kind)) :: receipts) (k + 1) rest
    in
    let lets, receipts, ends = upto [] [] 0 c.body in
    let outputs = match ends with Some (_, e) -> [ e ] | None -> c.final :: List.map snd c.updates in
    let pieces = List.map lowered outputs and let_pieces = List.map (fun (x, e) -> (x, lowered e)) lets in
    let pending = List.exists Option.is_none pieces in
    let program =
      if pending then None
      else
        let out = match List.filter_map Fun.id pieces with [ e ] -> e | es -> mk (Ast.List_lit es) in
        let body =
          List.fold_right
            (fun (x, e) acc ->
              let init = match e with Some e -> e | None -> mk (Ast.Const Cordis_cel.Value.Null) in
              Cordis_cel.Ext_cel.bind ~mk x init acc)
            let_pieces out
        in
        match Cordis_cel.Library.inline env.libs (renumber body) with
        | Ok e -> Some (renumber e)
        | Error m -> fail "turn %d: %s" i m
    in
    let cost =
      Option.map
        (fun p ->
          let state_shape = shape env (Record (List.map (fun (f, t, _) -> (f, t)) c.state, false)) in
          let shapes =
            List.map (fun (n, t) -> (n, shape env t)) c.params
            @ [ ("state", state_shape) ]
            @ List.filter_map
                (function Use (x, k, _) -> (match List.assoc_opt k env.defs with Some (Effect (_, _, r)) -> Some (x, shape env r) | _ -> None) | Let _ -> None)
                c.body
          in
          let cenv = cel_env env (params @ [ ("state", state_t) ] @ List.rev receipts) in
          match Cordis_cel.Cost.estimate ~default_size:env.options.default_size ~shapes cenv p with
          | Ok e ->
              if e.max > env.options.budget then
                fail "turn %d costs up to %s, over the budget of %d" i
                  (if e.max = Cordis_cel.Cost.inf then "an unbounded amount" else string_of_int e.max)
                  env.options.budget;
              e
          | Error m -> fail "turn %d: %s" i m)
        program
    in
    { index = i; emits = Option.map fst ends; pending; cost; program }
  in
  { cname = c.name; params; result; effects = c.effects; state; turns = List.init n_turns turn; holes = List.rev !holes }

(* ---- graphs ---------------------------------------------------------------------------------- *)

(* Instantiate a cell's type variables ('a, as opaque types) with fresh
   checker variables, so each use of a generic cell is unified alone. *)
let instantiate cenv ts =
  let tbl = Hashtbl.create 4 in
  let rec go (t : C.t) : C.t =
    match t with
    | C.Opaque (v, []) when String.length v > 0 && v.[0] = '\'' -> (
        match Hashtbl.find_opt tbl v with Some x -> x | None -> let x = C.fresh cenv in Hashtbl.add tbl v x; x)
    | C.List t -> C.List (go t)
    | C.Map (k, v) -> C.Map (go k, go v)
    | C.Opt t -> C.Opt (go t)
    | C.Record (fs, o) -> C.Record (List.map (fun (f, t) -> (f, go t)) fs, o)
    | C.Variant cs -> C.Variant (List.map (fun (n, p) -> (n, Option.map go p)) cs)
    | t -> t
  in
  List.map go ts

let check_graph env ?(broken = []) cells name items =
  let decl = Hashtbl.create 16 in
  let define n what = if Hashtbl.mem decl n then fail "%s is defined twice" n else Hashtbl.add decl n what in
  List.iter
    (function
      | Input (n, _) -> define n `Input | Stream (n, _) -> define n `Stream | Asset { name; _ } -> define name `Asset
      | _ -> ())
    items;
  let inputs = List.filter_map (function Input (n, t) -> Some (n, resolve env t) | _ -> None) items in
  let streams = List.filter_map (function Stream (n, t) -> Some (n, resolve env t) | _ -> None) items in
  let asset_items = List.filter_map (function Asset a -> Some a | _ -> None) items in
  (* dependency order, with streams removed; a cycle is an error *)
  let deps_of (a : asset) = List.filter (fun x -> Hashtbl.find_opt decl x <> Some `Stream) a.args in
  let order = ref [] and state = Hashtbl.create 8 in
  let rec visit path n =
    match Hashtbl.find_opt state n with
    | Some `Done -> ()
    | Some `Active -> fail "a dependency cycle: %s (a route through a stream breaks a loop)" (String.concat " -> " (List.rev (n :: path)))
    | None -> (
        match List.find_opt (fun (a : asset) -> a.name = n) asset_items with
        | None -> ()
        | Some a ->
            Hashtbl.replace state n `Active;
            List.iter (fun x -> if not (Hashtbl.mem decl x) then fail "asset %s: unknown input %s" n x; visit (n :: path) x) a.args;
            Hashtbl.replace state n `Done;
            order := a :: !order)
  in
  List.iter (fun (a : asset) -> visit [] a.name) asset_items;
  let ordered = List.rev !order in
  (* types, in order: each argument fits its parameter *)
  let types = Hashtbl.create 16 in
  List.iter (fun (n, t) -> Hashtbl.replace types n t) inputs;
  List.iter (fun (n, t) -> Hashtbl.replace types n (C.List t)) streams;
  let assets =
    List.map
      (fun (a : asset) ->
        let ci =
          match List.find_opt (fun ci -> ci.cname = a.cell) cells with
          | Some ci -> ci
          | None ->
              if List.mem a.cell broken then fail "asset %s: cell %s has errors (above)" a.name a.cell
              else fail "asset %s: unknown cell %s" a.name a.cell
        in
        if List.length a.args <> List.length ci.params then
          fail "asset %s: %s takes %d inputs, not %d" a.name a.cell (List.length ci.params) (List.length a.args);
        let cenv = C.empty () in
        let inst = instantiate cenv (ci.result :: List.map snd ci.params) in
        let result, ps = (List.hd inst, List.tl inst) in
        List.iter2
          (fun x p ->
            let t = Hashtbl.find types x in
            if not (C.assignable cenv p t) then
              fail "asset %s: %s has type %s, but %s's parameter takes %s" a.name x (C.to_string t) a.cell
                (C.to_string (C.finalize cenv p)))
          a.args ps;
        let aty = C.finalize cenv result in
        Hashtbl.replace types a.name aty;
        { aname = a.name; acell = a.cell; aty; args = a.args; deps = deps_of a; guards = [];
          fresh = Option.value a.fresh ~default:On_change })
      ordered
  in
  let rec depends_on a b =
    match List.find_opt (fun x -> x.aname = a) assets with
    | None -> false
    | Some x -> List.mem b x.deps || List.exists (fun d -> depends_on d b) x.deps
  in
  (* routes *)
  let guards = Hashtbl.create 8 and appends = ref [] in
  let routed = Hashtbl.create 4 in
  List.iter
    (function
      | Route (s, cases) ->
          if Hashtbl.mem routed s then fail "route %s: %s is routed twice" s s;
          Hashtbl.add routed s ();
          let st = match Hashtbl.find_opt types s with Some t -> t | None -> fail "route %s: unknown asset %s" s s in
          let cs = match st with C.Variant cs -> cs | t -> fail "route %s: %s has type %s, not a variant" s s (C.to_string t) in
          let tags = List.filter_map (function Case (t, _), _ -> Some t | Wild, _ -> None) cases in
          List.iter (fun t -> if List.length (List.filter (( = ) t) tags) > 1 then fail "route %s: case %s twice" s t) tags;
          let wild = List.exists (function Wild, _ -> true | _ -> false) cases in
          (match List.filter (fun (c, _) -> not (List.mem c tags)) cs with
           | missing when missing <> [] && not wild ->
               fail "route %s is not exhaustive: no case for %s" s (String.concat ", " (List.map fst missing))
           | _ -> ());
          List.iter
            (fun (p, acts) ->
              let tag, binder =
                match p with
                | Wild -> ("_", None)
                | Case (t, b) -> (
                    match List.assoc_opt t cs with
                    | None -> fail "route %s: no case %s in its type" s t
                    | Some None -> if b <> None then fail "route %s: case %s carries no value" s t else (t, None)
                    | Some (Some pt) -> (t, match b with Some "_" | None -> None | Some x -> Some (x, pt)))
              in
              List.iter
                (function
                  | Nothing -> ()
                  | Enable a ->
                      if not (List.exists (fun x -> x.aname = a) assets) then fail "route %s: %s is not an asset" s a;
                      if not (depends_on a s) then fail "route %s: %s does not depend on %s, so the route cannot guard it" s a s;
                      Hashtbl.replace guards a ((s, tag) :: Option.value (Hashtbl.find_opt guards a) ~default:[])
                  | Append (st_name, e) -> (
                      match List.assoc_opt st_name streams with
                      | None -> fail "route %s: %s is not a stream" s st_name
                      | Some elem ->
                          let vars = (match binder with Some (x, t) -> [ (x, t) ] | None -> []) in
                          let t = check_cel (cel_env env vars) (Printf.sprintf "route %s, case %s" s tag) e in
                          (match e with Hole _ -> () | Cel _ -> expect_assignable (Printf.sprintf "route %s, case %s: the value for %s" s tag st_name) elem t);
                          appends := (s, tag, st_name) :: !appends))
                acts)
            cases
      | _ -> ())
    items;
  let assets = List.map (fun a -> { a with guards = List.rev (Option.value (Hashtbl.find_opt guards a.aname) ~default:[]) }) assets in
  (* invariants *)
  List.iter
    (function
      | Before (c1, c2) ->
          let by c = List.filter (fun a -> a.acell = c) assets in
          if by c1 = [] then fail "invariant %s before %s: no asset uses %s" c1 c2 c1;
          if by c2 = [] then fail "invariant %s before %s: no asset uses %s" c1 c2 c2;
          List.iter
            (fun b ->
              if not (List.exists (fun a -> depends_on b.aname a.aname) (by c1)) then
                fail "invariant %s before %s cannot hold: %s (%s) does not depend on any asset %s computes" c1 c2 b.aname c2 c1)
            (by c2)
      | Always_inv e | Sometimes_inv e ->
          let vars = List.map (fun a -> (a.aname, a.aty)) assets @ inputs in
          let t = check_cel (cel_env env vars) "an invariant" e in
          (match e with Hole _ -> () | Cel _ -> expect_assignable "an invariant" C.Bool t)
      | _ -> ())
    items;
  List.iter
    (function
      | Asset { name; fresh = Some (Every d); _ } -> (
          match Cordis_cel.Cel.eval (Printf.sprintf "duration('%s')" d) with
          | Ok { value = Cordis_cel.Value.Duration _; _ } -> ()
          | _ -> fail "asset %s: bad duration %s" name d)
      | _ -> ())
    items;
  let effect_row =
    List.sort_uniq compare (List.concat_map (fun a -> (List.find (fun c -> c.cname = a.acell) cells).effects) assets)
  in
  { gname = name; inputs; streams; assets; appends = List.rev !appends; effect_row }

(* ---- files ----------------------------------------------------------------------------------- *)

let check ?(options = default_options) (file : file) =
  let errors = ref [] in
  let err where m = errors := Printf.sprintf "%s: %s" where m :: !errors in
  (* names are unique across definitions *)
  let names = List.map item_name file in
  List.iter (fun n -> if List.length (List.filter (( = ) n) names) > 1 && not (List.mem (n ^ " is defined twice") !errors) then err n "defined twice") (List.sort_uniq compare names);
  let defs = List.map (fun i -> (item_name i, i)) file in
  (* libraries, by hash *)
  let libs =
    List.filter_map
      (function
        | Uses (alias, h) -> (
            match options.libraries h with
            | None -> err alias (Printf.sprintf "library %s is not available" h); None
            | Some text -> (
                match Cordis_cel.Library.load ~expect:h text with
                | Ok lib -> Some (alias, lib)
                | Error m -> err alias m; None))
        | _ -> None)
      file
  in
  let env = { defs; options; libs } in
  let guard where f = match f () with v -> Some v | exception Fail m -> err where m; None in
  let types =
    List.filter_map
      (function Type_def (n, _) -> Option.map (fun t -> (n, t)) (guard ("type " ^ n) (fun () -> resolve env (Named n))) | _ -> None)
      file
  in
  List.iter
    (function
      | Effect (k, a, b) -> ignore (guard ("effect " ^ k) (fun () -> (resolve env a, resolve env b)))
      | _ -> ())
    file;
  let cells = List.filter_map (function Cell c -> guard ("cell " ^ c.name) (fun () -> check_cell env c) | _ -> None) file in
  let graphs =
    let broken = List.filter_map (function Cell c when not (List.exists (fun ci -> ci.cname = c.name) cells) -> Some c.name | _ -> None) file in
    List.filter_map (function Graph (n, items) -> guard ("graph " ^ n) (fun () -> check_graph env ~broken cells n items) | _ -> None) file
  in
  if !errors = [] then Ok { cells; graphs; types } else Error (List.rev !errors)
