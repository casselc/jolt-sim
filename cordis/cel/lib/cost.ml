(* The static cost estimator (docs/cordis/rfc/0001-cel.md, "Cost", step 4).

   An abstract interpretation of the evaluator's cost counter over size
   shapes. Every value is approximated by a shape: the bounds [lo, hi] on
   its size as cel-go's actualSize measures it (code points of a string,
   bytes, list or map entries, 1 for anything else), and the shapes of its
   elements (list elements, map values, message fields, an optional's
   content) and of its map keys. Each expression gets a cost interval and a
   shape; the walk below charges exactly what eval.ml charges, at the same
   nodes, so the estimate's maximum bounds the actual cost of every
   evaluation whose inputs fit their declared bounds.

   Input bounds: every string, bytes, list and map reachable from a
   variable has size at most that variable's bound ([sizes], else
   [default_size]). Computed values are bounded by the computation, not by
   the input bound, so [x + x] has twice x's size.

   Comprehensions are the delicate case. The accumulator's shape after n
   iterations is found from the loop step's effect on its size, f(h): sizes
   compose only by sums and maxima, so f(h) = max(h + B, C) when f grows at
   most linearly, and then n iterations reach at most max(h0, f(0)) + n * B.
   A step that more than doubles its input (acc + acc) makes the size
   unbounded, as does an accumulator whose element shapes keep changing.

   The minimum is the cost of an evaluation that raises no error: an error
   ends evaluation early, so an absorbed error (false && (1/0 > 0)) can cost
   less. Soundness is claimed, and tested, for the maximum. *)

open Ast

(* ---- saturating arithmetic: max_int is "unbounded" ------------------------------------ *)

let inf = max_int
let ( +! ) a b = if a >= inf - b then inf else a + b
let ( *! ) a b = if a = 0 || b = 0 then 0 else if a >= inf / b then inf else a * b

(* ceil(n * f) as eval.ml computes it, saturating *)
let scaled f n =
  if n >= inf then inf
  else if n > 1 lsl 50 then int_of_float (float_of_int n *. f) + 1
  else int_of_float (Float.ceil (float_of_int n *. f))

let traversal = scaled 0.1

(* ---- shapes ----------------------------------------------------------------------------- *)

type shape = { lo : int; hi : int; elem : shape Lazy.t; key : shape Lazy.t }

let rec unknown = { lo = 0; hi = inf; elem = lazy unknown; key = lazy unknown }
let scalar = { lo = 1; hi = 1; elem = lazy unknown; key = lazy unknown }

(* the shape of the elements of an empty container: the identity of join *)
let rec bottom = { lo = inf; hi = 0; elem = lazy bottom; key = lazy bottom }

let rec join a b =
  if a == b then a
  else if a == bottom then b
  else if b == bottom then a
  else
    { lo = min a.lo b.lo;
      hi = max a.hi b.hi;
      elem = lazy (join (Lazy.force a.elem) (Lazy.force b.elem));
      key = lazy (join (Lazy.force a.key) (Lazy.force b.key)) }

let elem s = Lazy.force s.elem
let key s = Lazy.force s.key
let lo s = if s.lo > s.hi then 0 else s.lo

let sized lo hi = { lo; hi; elem = lazy unknown; key = lazy unknown }

(* Every size at most d, at every depth. *)
let bounded d =
  let rec s = { lo = 0; hi = d; elem = lazy s; key = lazy s } in
  s

(* Equal to a depth: for detecting that a comprehension's accumulator has
   stopped changing shape. *)
let rec same depth a b =
  a == b
  || (a.lo = b.lo && a.hi = b.hi
     && (depth = 0 || (same (depth - 1) (elem a) (elem b) && same (depth - 1) (key a) (key b))))

(* The shape of an input of a declared type, every size bounded by d. *)
let rec of_type d (t : Checker.t) =
  match t with
  | Checker.Bool | Checker.Int | Checker.Uint | Checker.Double | Checker.Null | Checker.Timestamp | Checker.Duration
  | Checker.Type _ ->
      scalar
  | Checker.String | Checker.Bytes -> sized 0 d
  | Checker.List e -> { lo = 0; hi = d; elem = lazy (of_type d e); key = lazy scalar }
  | Checker.Map (k, v) -> { lo = 0; hi = d; elem = lazy (of_type d v); key = lazy (of_type d k) }
  | Checker.Opt t -> { scalar with elem = lazy (of_type d t) }
  | Checker.Wrapper t -> { (of_type d t) with lo = 0 }
  | _ -> bounded d

let of_value (v : Value.t) =
  match v with
  | Value.String s -> let n = Eval.utf8_length s in sized n n
  | Value.Bytes b -> let n = String.length b in sized n n
  | _ -> scalar

(* Types whose values all have size 1. *)
let scalar_type (t : Checker.t option) =
  match t with
  | Some (Checker.Bool | Checker.Int | Checker.Uint | Checker.Double | Checker.Null | Checker.Timestamp
         | Checker.Duration | Checker.Type _) ->
      true
  | Some (Checker.Wrapper (Checker.Bool | Checker.Int | Checker.Uint | Checker.Double)) -> true
  | _ -> false

(* ---- the walk ------------------------------------------------------------------------------ *)

type ctx = {
  types : int -> Checker.t option;  (** the checker's type of each node *)
  inputs : string -> shape option;  (** declared variables *)
}

type est = { cmin : int; cmax : int; shape : shape }

let const_cost c shape = { cmin = c; cmax = c; shape }

(* An attribute (identifier, selection, index) chosen as a conditional's
   branch: eval.ml does not charge its base attribute cost. *)
let attribute_branch (b : expr) =
  match b.kind with
  | Ident _ | Select { test_only = false; _ } -> true
  | Call { fn; target = None; args = [ _; _ ] } when fn = op_index -> true
  | _ -> false

(* The cost of a call's own work, beyond its arguments, as [Eval.call_cost]
   charges it: an interval over the arguments' shapes. When the overload is
   chosen by runtime type (dyn arguments), the bounds cover the size-based
   formula and the flat 1 both. *)
let call_cost ?(types = []) fn (args : shape list) =
  (* Does the size-based formula apply? Some true when the checker fixed the
     arguments' types to the formula's overload, Some false when it fixed
     them to another (which costs 1), None when a dyn argument leaves the
     overload to runtime, and both are possible. *)
  let ty i = match List.nth_opt types i with Some (Some (Checker.Dyn | Checker.Var _)) | Some None | None -> None | Some (Some t) -> Some t in
  let is t i = Option.map (fun u -> u = t) (ty i) in
  let both_text () =
    match ty 0, ty 1 with
    | Some Checker.String, Some Checker.String | Some Checker.Bytes, Some Checker.Bytes -> Some true
    | Some _, Some _ -> Some false
    | _ -> None
  in
  let applies =
    match fn with
    | "_<_" | "_<=_" | "_>_" | "_>=_" | "_+_" -> both_text ()
    | "startsWith" | "endsWith" | "contains" | "matches" -> both_text ()
    | "string" -> is Checker.Bytes 0
    | "bytes" | "strings.quote" -> is Checker.String 0
    | "format" -> is Checker.String 0
    | "@in" -> (match ty 1 with Some (Checker.List _) -> Some true | Some _ -> Some false | None -> None)
    | _ -> None
  in
  let either lo_f hi_f =
    match applies with Some true -> (lo_f, hi_f) | Some false -> (1, 1) | None -> (min 1 lo_f, max 1 hi_f)
  in
  match fn, args with
  | ("_==_" | "_!=_"), [ l; r ] -> (traversal (min (lo l) (lo r)), traversal (min l.hi r.hi))
  | ("_<_" | "_<=_" | "_>_" | "_>=_"), [ l; r ] ->
      either (traversal (min (lo l) (lo r))) (traversal (min l.hi r.hi))
  | "_+_", [ l; r ] -> either (traversal (lo l + lo r)) (traversal (l.hi +! r.hi))
  | ("startsWith" | "endsWith"), [ _; p ] -> either (traversal (lo p)) (traversal p.hi)
  | ("bytes" | "string" | "strings.quote"), [ x ] -> either (traversal (lo x)) (traversal x.hi)
  | "format", [ f; _ ] -> either (traversal (lo f)) (traversal f.hi)
  | "@in", [ _; l ] -> either (lo l) l.hi
  | "matches", [ x; re ] ->
      either
        (scaled 0.1 (1 + lo x) * scaled 0.25 (lo re))
        (scaled 0.1 (1 +! x.hi) *! scaled 0.25 re.hi)
  | "contains", [ x; sub ] -> either (traversal (lo x) * traversal (lo sub)) (traversal x.hi *! traversal sub.hi)
  | _ -> (1, 1)

(* The shape of a call's result, where its function says more than its
   type does. *)
let call_shape ctx fn (arg_ids : int list) (args : shape list) =
  let ty i = ctx.types (List.nth arg_ids i) in
  match fn, args with
  | "_+_", [ l; r ] ->
      { lo = lo l + lo r; hi = l.hi +! r.hi; elem = lazy (join (elem l) (elem r)); key = lazy (join (key l) (key r)) }
  | "_[_]", [ c; _ ] -> elem c
  | "value", [ o ] -> elem o
  | "@match_value", [ v; _ ] -> elem v
  | ("_[?_]" | "_?._"), [ c; _ ] -> { scalar with elem = c.elem }
  | "orValue", [ o; x ] -> join (elem o) x
  | "optional.of", [ x ] -> { scalar with elem = lazy x }
  | "optional.ofNonZeroValue", [ x ] -> { scalar with elem = lazy x }
  | "dyn", [ x ] -> x
  | "string", [ x ] -> (
      match ty 0 with
      | Some Checker.String -> x
      | Some Checker.Bytes -> sized 0 x.hi
      | Some (Checker.Int | Checker.Uint) -> sized 1 20
      | Some Checker.Double -> sized 1 24
      | Some Checker.Bool -> sized 4 5
      | Some Checker.Timestamp -> sized 20 30
      | Some Checker.Duration -> sized 2 24
      (* a dyn argument: a string or bytes no longer than itself, or a
         rendered scalar (at most 30, a timestamp's length) *)
      | _ -> sized 0 (max x.hi 30))
  | "bytes", [ x ] -> (
      match ty 0 with
      | Some Checker.Bytes -> x
      | Some Checker.String -> sized (lo x) (x.hi *! 4)
      | _ -> unknown)
  | ("lowerAscii" | "upperAscii" | "trim" | "substring"), x :: _ -> sized 0 x.hi
  | "charAt", _ -> sized 0 1
  | _ -> unknown

let rec walk ctx (locals : (string * shape) list) (e : expr) : est =
  let r = walk_kind ctx locals e in
  if scalar_type (ctx.types e.id) then { r with shape = scalar } else r

and walk_kind ctx locals (e : expr) : est =
  match e.kind with
  | Const v -> const_cost 0 (of_value v)
  | Ident n -> (
      match List.assoc_opt n locals with
      | Some s -> const_cost 1 s
      | None -> const_cost 1 (Option.value (ctx.inputs n) ~default:unknown))
  | Select { operand; field = _; test_only } -> (
      let base = if Eval.is_attribute_root operand then 1 else 2 in
      match Eval.ident_path e with
      | Some (root :: _ as path) when (not test_only) && not (List.mem_assoc root locals) ->
          (* a qualified name: the longest declared prefix, then fields *)
          let n = List.length path in
          let rec prefix k =
            if k = 0 then unknown
            else
              match ctx.inputs (String.concat "." (List.filteri (fun i _ -> i < k) path)) with
              | Some s -> List.fold_left (fun s _ -> elem s) s (List.filteri (fun i _ -> i >= k) path)
              | None -> prefix (k - 1)
          in
          const_cost (base + n - 1) (prefix n)
      | _ ->
          let o = walk ctx locals operand in
          { cmin = o.cmin + base; cmax = o.cmax +! base; shape = (if test_only then scalar else elem o.shape) })
  | List_lit es ->
      let parts =
        List.map
          (fun (x : expr) ->
            match x.kind with
            | Call { fn; args = [ y ]; _ } when fn = op_opt_elem ->
                let r = walk ctx locals y in
                (r, elem r.shape, true)
            | _ -> let r = walk ctx locals x in (r, r.shape, false))
          es
      in
      let n = List.length es and opt = List.length (List.filter (fun (_, _, o) -> o) parts) in
      { cmin = List.fold_left (fun a (r, _, _) -> a + r.cmin) 10 parts;
        cmax = List.fold_left (fun a (r, _, _) -> a +! r.cmax) 10 parts;
        shape =
          { lo = n - opt; hi = n;
            elem = lazy (List.fold_left (fun a (_, s, _) -> join a s) bottom parts);
            key = lazy scalar } }
  | Map_lit kvs ->
      let parts =
        List.map
          (fun ((k : expr), v) ->
            match k.kind with
            | Call { fn; args = [ k ]; _ } when fn = op_opt_elem ->
                let kr = walk ctx locals k and vr = walk ctx locals v in
                (kr.cmin + vr.cmin, kr.cmax +! vr.cmax, kr.shape, elem vr.shape, true)
            | _ ->
                let kr = walk ctx locals k and vr = walk ctx locals v in
                (kr.cmin + vr.cmin, kr.cmax +! vr.cmax, kr.shape, vr.shape, false))
          kvs
      in
      let n = List.length kvs and opt = List.length (List.filter (fun (_, _, _, _, o) -> o) parts) in
      { cmin = List.fold_left (fun a (m, _, _, _, _) -> a + m) 30 parts;
        cmax = List.fold_left (fun a (_, m, _, _, _) -> a +! m) 30 parts;
        shape =
          { lo = (if n - opt > 0 then 1 else 0); hi = n;
            elem = lazy (List.fold_left (fun a (_, _, _, s, _) -> join a s) bottom parts);
            key = lazy (List.fold_left (fun a (_, _, s, _, _) -> join a s) bottom parts) } }
  | Struct_lit { fields; _ } ->
      let parts = List.map (fun (_, x, opt) -> let r = walk ctx locals x in (r, if opt then elem r.shape else r.shape)) fields in
      (* unset fields read their defaults, assumed within the input bound *)
      let default_fields = bounded (match ctx.inputs "" with Some s -> s.hi | None -> inf) in
      { cmin = List.fold_left (fun a (r, _) -> a + r.cmin) 40 parts;
        cmax = List.fold_left (fun a (r, _) -> a +! r.cmax) 40 parts;
        shape = { scalar with elem = lazy (List.fold_left (fun a (_, s) -> join a s) default_fields parts) } }
  | Call { fn; target = None; args = [ l; r ] } when fn = op_and || fn = op_or ->
      let l = walk ctx locals l and r = walk ctx locals r in
      { cmin = l.cmin; cmax = l.cmax +! r.cmax; shape = scalar }
  | Call { fn; target = None; args = [ c; t; f ] } when fn = op_cond ->
      let c = walk ctx locals c in
      let branch (b : expr) =
        let r = walk ctx locals b in
        if attribute_branch b then { r with cmin = max 0 (r.cmin - 1); cmax = (if r.cmax = inf then inf else r.cmax - 1) }
        else r
      in
      let t = branch t and f = branch f in
      { cmin = c.cmin + min t.cmin f.cmin; cmax = c.cmax +! max t.cmax f.cmax; shape = join t.shape f.shape }
  | Call { fn; target = None; args = [ x ] } when fn = op_not_strictly_false ->
      let x = walk ctx locals x in
      { cmin = x.cmin + 1; cmax = x.cmax +! 1; shape = scalar }
  | Call { fn; target = Some t; args } -> (
      (* a namespaced function (math.greatest) does not evaluate its target *)
      match Eval.ident_path t with
      | Some (root :: _ as p)
        when (not (List.mem_assoc root locals)) && ctx.inputs root = None
             && Registry.mem (String.concat "." (p @ [ fn ])) ->
          strict_call ctx locals (String.concat "." (p @ [ fn ])) args
      | _ -> strict_call ctx locals fn (t :: args))
  | Call { fn; target = None; args } -> strict_call ctx locals fn args
  | Comprehension { iter_var; iter_var2; range; accu_var; accu_init; loop_cond; loop_step; result } ->
      let r = walk ctx locals range in
      let n_lo = lo r.shape and n_hi = r.shape.hi in
      let iter_locals =
        match iter_var2 with
        | None -> [ (iter_var, join (elem r.shape) (key r.shape)) ]
        | Some v2 -> [ (iter_var, join scalar (key r.shape)); (v2, elem r.shape) ]
      in
      let init = walk ctx locals accu_init in
      let with_accu a = iter_locals @ ((accu_var, a) :: locals) in
      let step a = (walk ctx (with_accu a) loop_step).shape in
      let accu = accumulator step init.shape n_hi in
      let cond = walk ctx (with_accu accu) loop_cond in
      let st = walk ctx (with_accu accu) loop_step in
      let res = walk ctx ((accu_var, accu) :: locals) result in
      let always = match loop_cond.kind with Const (Value.Bool true) -> true | _ -> false in
      let iter_min = if always then n_lo * (cond.cmin + st.cmin) else if n_lo > 0 then cond.cmin else 0 in
      { cmin = r.cmin + init.cmin + iter_min + res.cmin;
        cmax = r.cmax +! init.cmax +! (n_hi *! (cond.cmax +! st.cmax)) +! res.cmax;
        shape = res.shape }

(* A strict call: the arguments, the attribute cost of an index off a
   non-attribute, and the call's own cost. *)
and strict_call ctx locals fn args =
  let rs = List.map (walk ctx locals) args in
  let shapes = List.map (fun r -> r.shape) rs in
  let attr =
    match fn, args with
    | f, operand :: _ when f = op_index && not (Eval.is_attribute_root operand) -> 1
    | _ -> 0
  in
  let cmin_own, cmax_own = call_cost ~types:(List.map (fun (a : expr) -> ctx.types a.id) args) fn shapes in
  { cmin = List.fold_left (fun a r -> a + r.cmin) (attr + cmin_own) rs;
    cmax = List.fold_left (fun a r -> a +! r.cmax) (attr +! cmax_own) rs;
    shape = call_shape ctx fn (List.map (fun (a : expr) -> a.id) args) shapes }

(* The accumulator's shape over at most n iterations of [step] from [init]. *)
and accumulator step init n =
  if n = 0 then init
  else
    (* the element and key shapes, once they stop changing *)
    let rec settle s k =
      let s' = join s (step s) in
      if same 4 s s' then Some s' else if k = 0 then None else settle s' (k - 1)
    in
    let nested =
      match settle init 4 with
      | Some s -> s
      | None -> { init with elem = lazy unknown; key = lazy unknown }
    in
    let at h = (step { nested with hi = h }).hi in
    let h = 1 lsl 40 in
    let fh = at h and f2h = at (2 * h) and f0 = at 0 in
    let hi =
      if fh >= inf || f2h >= inf || f0 >= h / 2 then inf
      else if f2h - fh > h then inf (* superlinear: acc + acc *)
      else max (max init.hi nested.hi) f0 +! (n *! max 0 (fh - h))
    in
    { nested with lo = 0; hi }

(* ---- the front door ---------------------------------------------------------------------------- *)

type estimate = { min : int; max : int }

(* Estimate an expression's cost in a checker environment: its declared
   variables are the inputs, each given a [shapes] entry, or else bounded
   at every depth by its [sizes] entry or [default_size]. *)
let estimate ?(default_size = 1000) ?(sizes = []) ?(shapes = []) (env : Checker.env) (e : expr) =
  match Checker.check_types env e with
  | Error m -> Error m
  | Ok (_, types) ->
      let inputs name =
        if name = "" then Some (bounded default_size)
        else
          (* a shape given for an input (its type's bounds, level by level)
             wins over a single size bound *)
          match List.assoc_opt name shapes with
          | Some sh -> Some sh
          | None ->
          match List.assoc_opt name env.Checker.idents with
          | Some t ->
              let d = Option.value (List.assoc_opt name sizes) ~default:default_size in
              Some (of_type (max 1 d) t)
          | None -> None
      in
      let r = walk { types; inputs } [] e in
      Ok { min = r.cmin; max = r.cmax }
