(* proto.getExt(msg, pkg.ext) and proto.hasExt(msg, pkg.ext): macros that
   select a proto2 extension field by its full name, as cel-go expands them. *)

open Ast

let rec path (e : expr) =
  match e.kind with
  | Ident n -> Some n
  | Select { operand; field; test_only = false } -> Option.map (fun p -> p ^ "." ^ field) (path operand)
  | _ -> None

let register () =
  let ext ~test_only ~mk ~target:_ args =
    match args with
    | [ msg; name ] -> Option.map (fun field -> mk (Select { operand = msg; field; test_only })) (path name)
    | _ -> None
  in
  Registry.register_macro "proto.getExt" (ext ~test_only:false);
  Registry.register_macro "proto.hasExt" (ext ~test_only:true)
