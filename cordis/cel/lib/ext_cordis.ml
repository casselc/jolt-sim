(* The harness extensions (docs/cordis/rfc/0001-cel.md, "Extensions"), each
   an expansion into core CEL.

   xs.fold(acc, init, x, step) folds step over xs with the accumulator acc,
   starting from init; xs.fold(acc, init, i, x, step) also binds the index
   (or, over a map, the key and value). fold_while takes a condition before
   the step and stops at the first iteration where it is false. Both are a
   comprehension whose accumulator is acc, so the evaluator, checker and
   cost estimator see nothing new; the iteration count is size(xs).

   As in every CEL comprehension, a loop condition that is not false (an
   error included) lets the iteration proceed.

   The runtime half of match (the syntax is in parser.ml): a variant value
   is a map {'tag': 'Tag', 'value': payload}, with no 'value' for a case
   that carries none; an optional matches Some(x) and None. *)

open Ast

let fold ~mk ~target ~acc ~init ~x ?y ~cond ~step () =
  let var (e : expr) = match e.kind with Ident n -> Some n | _ -> None in
  match target, var acc, var x, Option.map var y with
  | Some range, Some acc, Some x, (None | Some (Some _)) ->
      let iter_var2 = Option.join (Option.map var y) in
      Some
        (mk
           (Comprehension
              { iter_var = x; iter_var2; range; accu_var = acc; accu_init = init; loop_cond = cond;
                loop_step = step; result = mk (Ident acc) }))
  | _ -> raise (Parser.Parse_error "fold: the accumulator and iteration variables must be identifiers")

let tag_of (v : Value.t) =
  match v with
  | Value.Map m -> (match Value.map_find m (Value.String "tag") with Some (Value.String t) -> Some t | _ -> None)
  | Value.Optional (Some _) -> Some "Some"
  | Value.Optional None -> Some "None"
  | _ -> None

let register () =
  Registry.register_macro "fold" (fun ~mk ~target args ->
      let always = mk (Const (Value.Bool true)) in
      match args with
      | [ acc; init; x; step ] -> fold ~mk ~target ~acc ~init ~x ~cond:always ~step ()
      | [ acc; init; x; y; step ] -> fold ~mk ~target ~acc ~init ~x ~y ~cond:always ~step ()
      | _ -> None);
  Registry.register_macro "fold_while" (fun ~mk ~target args ->
      match args with
      | [ acc; init; x; cond; step ] -> fold ~mk ~target ~acc ~init ~x ~cond ~step ()
      | [ acc; init; x; y; cond; step ] -> fold ~mk ~target ~acc ~init ~x ~y ~cond ~step ()
      | _ -> None);
  (* @strict(a, ..., body): body, unless an argument is an error, which is
     the result; inlined library calls use it so a call stays strict *)
  Registry.register "@strict" (fun args -> match List.rev args with body :: _ -> Some body | [] -> None);
  Registry.register "@match_is" (function
    | [ v; Value.String t ] -> (
        match tag_of v with
        | Some t' -> Some (Value.Bool (t = t'))
        | None -> Some (Value.Error "match: the value is not a variant or an optional"))
    | _ -> None);
  Registry.register "@match_value" (function
    | [ Value.Optional (Some x); Value.String "Some" ] -> Some x
    | [ Value.Map m; Value.String t ] -> (
        match Value.map_find m (Value.String "value") with
        | Some x -> Some x
        | None -> Some (Value.Error (Printf.sprintf "match: case '%s' carries no value" t)))
    | _ -> None);
  Registry.register "@match_fail" (function
    | [ v; _ ] ->
        Some
          (Value.Error
             (match tag_of v with
              | Some t -> Printf.sprintf "match: no case for '%s'" t
              | None -> "match: the value is not a variant or an optional"))
    | _ -> None)
