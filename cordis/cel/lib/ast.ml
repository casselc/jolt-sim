(* The CEL abstract syntax, after macro expansion: every macro is already a
   comprehension, so the evaluator, checker and cost estimator see only
   these forms (docs/cordis/rfc/0001-cel.md, "Extensions"). *)

type expr = { id : int; kind : kind }

and kind =
  | Const of Value.t
  | Ident of string
  | Select of { operand : expr; field : string; test_only : bool }
      (** [test_only] is [has(operand.field)] *)
  | Call of { fn : string; target : expr option; args : expr list }
  | List_lit of expr list
  | Map_lit of (expr * expr) list
  | Comprehension of {
      iter_var : string;
      iter_var2 : string option;  (** two-variable comprehensions *)
      range : expr;
      accu_var : string;
      accu_init : expr;
      loop_cond : expr;
      loop_step : expr;
      result : expr;
    }

(* The function names operators are called by, as in cel-spec. *)
let op_and = "_&&_"
let op_or = "_||_"
let op_not = "!_"
let op_neg = "-_"
let op_cond = "_?_:_"
let op_index = "_[_]"
let op_in = "@in"
let op_not_strictly_false = "@not_strictly_false"

(* Optional syntax, encoded as reserved calls so the tree keeps its shape:
   x.?f, x[?k], and the ?e marker on a list element or map key. *)
let op_opt_select = "_?._"
let op_opt_index = "_[?_]"
let op_opt_elem = "@optional_elem"
