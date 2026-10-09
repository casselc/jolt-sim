(* Records and variants into the CEL maps that represent them at runtime
   (docs/cordis/rfc/0002-graph-language.md, "Values in CEL"), after
   checking: Review{passed: p, notes: n} becomes {'passed': p, 'notes': n},
   Verdict.Pass(r) becomes {'tag': 'Pass', 'value': r}, and Verdict.Skipped
   becomes {'tag': 'Skipped'}. The checker saw the original, so the types
   are already known; the evaluator and the estimator see plain CEL. *)

open Cordis_cel.Ast

let lower ~records ~variants (e : expr) =
  let next = ref (Cordis_cel.Library.max_id e) in
  let mk kind = incr next; { id = !next; kind } in
  let str s = mk (Const (Cordis_cel.Value.String s)) in
  let rec go locals (e : expr) =
    match e.kind with
    | Struct_lit { tname; fields } when List.mem tname records ->
        { e with kind = Map_lit (List.map (fun (f, x, _) -> (str f, go locals x)) fields) }
    | Call { fn; target = Some { kind = Ident n; _ }; args = [ x ] } when List.mem n variants && not (List.mem n locals) ->
        { e with kind = Map_lit [ (str "tag", str fn); (str "value", go locals x) ] }
    | Select { operand = { kind = Ident n; _ }; field; test_only = false } when List.mem n variants && not (List.mem n locals) ->
        { e with kind = Map_lit [ (str "tag", str field) ] }
    | Comprehension c ->
        let loop = c.accu_var :: c.iter_var :: Option.to_list c.iter_var2 @ locals in
        { e with
          kind =
            Comprehension
              { c with range = go locals c.range; accu_init = go locals c.accu_init; loop_cond = go loop c.loop_cond;
                loop_step = go loop c.loop_step; result = go (c.accu_var :: locals) c.result } }
    | _ -> Cordis_cel.Library.map_children (go locals) e
  in
  go [] e
