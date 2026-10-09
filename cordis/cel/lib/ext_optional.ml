(* Optional values (cel-go's optional types library): optional.of,
   optional.ofNonZeroValue, optional.none, hasValue, value, or, orValue,
   and the optMap and optFlatMap macros, expanded as cel-go expands them:

     o.optMap(x, e)     -> o.hasValue() ? optional.of(cel.bind(x, o.value(), e)) : optional.none()
     o.optFlatMap(x, e) -> o.hasValue() ? cel.bind(x, o.value(), e) : optional.none() *)

open Value

(* The zero value of each type, for ofNonZeroValue. *)
let is_zero = function
  | Null | Bool false -> true
  | Int 0L | Uint 0L -> true
  | Double d -> d = 0.0
  | String "" | Bytes "" -> true
  | List [||] | Map [] -> true
  | Timestamp (0L, 0) | Duration (0L, 0) -> true
  | Optional None -> true
  | _ -> false

let register () =
  let r = Registry.register in
  r "optional.of" (function [ v ] -> Some (Optional (Some v)) | _ -> None);
  r "optional.none" (function [] -> Some (Optional None) | _ -> None);
  r "optional.ofNonZeroValue" (function [ v ] -> Some (Optional (if is_zero v then None else Some v)) | _ -> None);
  r "hasValue" (function [ Optional o ] -> Some (Bool (o <> None)) | _ -> None);
  r "value" (function
    | [ Optional (Some v) ] -> Some v
    | [ Optional None ] -> Some (Error "optional.none() dereference")
    | _ -> None);
  r "or" (function [ (Optional (Some _) as o); Optional _ ] -> Some o | [ Optional None; (Optional _ as o) ] -> Some o | _ -> None);
  r "orValue" (function [ Optional (Some v); _ ] -> Some v | [ Optional None; d ] -> Some d | _ -> None);
  let opt_macro ~flat ~mk ~target args =
    match target, args with
    | Some o, [ { Ast.kind = Ast.Ident x; _ }; e ] ->
        let call fn target args = mk (Ast.Call { fn; target; args }) in
        let bound = Ext_cel.bind ~mk x (call "value" (Some o) []) e in
        Some
          (call Ast.op_cond None
             [ call "hasValue" (Some o) [];
               (if flat then bound else call "optional.of" None [ bound ]);
               call "optional.none" None [] ])
    | _ -> None
  in
  Registry.register_macro "optMap" (opt_macro ~flat:false);
  Registry.register_macro "optFlatMap" (opt_macro ~flat:true)
