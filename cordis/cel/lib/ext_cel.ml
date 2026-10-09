(* cel.bind and cel.block (cel-spec's bindings and block extensions), as
   macros over core CEL.

   cel.bind(v, init, e) binds v to init in e: a comprehension over an empty
   list whose accumulator is v, as cel-go expands it.

   cel.block([e0, e1, ...], e) names common subexpressions: cel.index(i) is
   e_i, and each slot may use the earlier ones. It expands to nested binds of
   reserved names, and cel.index(i) and cel.iterVar(i, j) (the j-th iteration
   variable of the i-th nested macro) become those reserved identifiers, so
   they can also stand where a macro expects a variable name. *)

open Ast

let index_name i = Printf.sprintf "@index%d" i
let iter_var_name i j = Printf.sprintf "@it:%d:%d" i j

let int_arg (e : expr) =
  match e.kind with Const (Value.Int i) -> Some (Int64.to_int i) | _ -> None

let bind ~mk name init body =
  mk
    (Comprehension
       { iter_var = "#unused"; iter_var2 = None; range = mk (List_lit []); accu_var = name; accu_init = init;
         loop_cond = mk (Const (Value.Bool false)); loop_step = mk (Ident name); result = body })

let register () =
  Registry.register_macro "cel.bind" (fun ~mk ~target:_ args ->
      match args with
      | [ { kind = Ident v; _ }; init; body ] -> Some (bind ~mk v init body)
      | _ -> None);
  Registry.register_macro "cel.index" (fun ~mk ~target:_ args ->
      match args with [ i ] -> Option.map (fun i -> mk (Ident (index_name i))) (int_arg i) | _ -> None);
  Registry.register_macro "cel.iterVar" (fun ~mk ~target:_ args ->
      match args with
      | [ i; j ] -> (
          match int_arg i, int_arg j with Some i, Some j -> Some (mk (Ident (iter_var_name i j))) | _ -> None)
      | _ -> None);
  Registry.register_macro "cel.block" (fun ~mk ~target:_ args ->
      match args with
      | [ { kind = List_lit slots; _ }; body ] ->
          Some (List.fold_right (fun (i, e) acc -> bind ~mk (index_name i) e acc) (List.mapi (fun i e -> (i, e)) slots) body)
      | _ -> None)
