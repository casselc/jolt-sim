(* Extension functions and types (docs/cordis/rfc/0001-cel.md, "Scope").

   A library registers overloads by function name; a call tries the core
   standard library first, then each registered overload in registration
   order, and the first that returns [Some] answers. A namespaced function
   such as [math.greatest] is registered under its full name, and the
   evaluator resolves [math.greatest(x)] to it when [math] is not a
   variable. Libraries register in [Extensions.load]. *)

type overload = Value.t list -> Value.t option
(** [None]: these arguments are not this overload's; [Some v]: the result,
    which may be [Value.Error]. *)

let functions : (string, overload list) Hashtbl.t = Hashtbl.create 64
let type_names : (string, Value.ty) Hashtbl.t = Hashtbl.create 8

let register name (f : overload) =
  let existing = Option.value (Hashtbl.find_opt functions name) ~default:[] in
  Hashtbl.replace functions name (existing @ [ f ])

let register_type name ty = Hashtbl.replace type_names name ty
let mem name = Hashtbl.mem functions name
let find_type name = Hashtbl.find_opt type_names name

let call name args =
  match Hashtbl.find_opt functions name with
  | None -> None
  | Some fs -> List.find_map (fun f -> f args) fs
