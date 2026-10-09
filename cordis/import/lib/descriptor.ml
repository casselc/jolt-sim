(* A FileDescriptorSet, decoded from protobuf's wire format: just the parts
   of descriptor.proto the importer reads (docs/cordis/rfc/0003-types-
   importers-scaffolds.md, "The protobuf importer"). Field numbers are
   descriptor.proto's. *)

type label = Optional | Required | Repeated

type field = {
  name : string;
  number : int;
  label : label;
  ty : int;  (** FieldDescriptorProto.Type: 1 double ... 18 sint64 *)
  type_name : string;  (** for messages and enums: ".pkg.Name" *)
  json_name : string;
  default_value : string;  (** proto2 [default = ...], as text; "" when none *)
  oneof_index : int option;
  proto3_optional : bool;
}

type message = {
  mname : string;
  fields : field list;
  nested : message list;
  enums : enum list;
  oneofs : string list;
  map_entry : bool;
}

and enum = { ename : string; values : (string * int) list; allow_alias : bool }

type file = { fname : string; package : string; syntax : string; messages : message list; fenums : enum list }

exception Bad of string

(* ---- the wire --------------------------------------------------------------------------------- *)

let varint s pos =
  let rec go shift acc =
    if !pos >= String.length s then raise (Bad "truncated varint");
    let b = Char.code s.[!pos] in
    incr pos;
    let acc = Int64.logor acc (Int64.shift_left (Int64.of_int (b land 0x7f)) shift) in
    if b land 0x80 <> 0 then go (shift + 7) acc else acc
  in
  go 0 0L

(* The fields of one message: (number, wire type, payload) where the
   payload is the varint's value or the bytes' content. *)
type wire = V of int64 | B of string | Skip

let fields s =
  let pos = ref 0 and out = ref [] in
  while !pos < String.length s do
    let key = Int64.to_int (varint s pos) in
    let num = key lsr 3 and wt = key land 7 in
    let v =
      match wt with
      | 0 -> V (varint s pos)
      | 1 -> pos := !pos + 8; Skip
      | 2 ->
          let n = Int64.to_int (varint s pos) in
          if !pos + n > String.length s then raise (Bad "truncated bytes");
          let b = String.sub s !pos n in
          pos := !pos + n;
          B b
      | 5 -> pos := !pos + 4; Skip
      | _ -> raise (Bad (Printf.sprintf "wire type %d" wt))
    in
    out := (num, v) :: !out
  done;
  List.rev !out

let str n fs = List.fold_left (fun acc (k, v) -> match v with B b when k = n -> b | _ -> acc) "" fs
let int n fs = List.fold_left (fun acc (k, v) -> match v with V i when k = n -> Some (Int64.to_int i) | _ -> acc) None fs
let all n fs = List.filter_map (fun (k, v) -> match v with B b when k = n -> Some b | _ -> None) fs

(* ---- descriptor.proto ------------------------------------------------------------------------- *)

let field_of b =
  let fs = fields b in
  {
    name = str 1 fs;
    number = Option.value (int 3 fs) ~default:0;
    label = (match int 4 fs with Some 2 -> Required | Some 3 -> Repeated | _ -> Optional);
    ty = Option.value (int 5 fs) ~default:0;
    type_name = str 6 fs;
    json_name = str 10 fs;
    default_value = str 7 fs;
    oneof_index = int 9 fs;
    proto3_optional = int 17 fs = Some 1;
  }

let enum_of b =
  let fs = fields b in
  let opts = fields (str 3 fs) in
  {
    ename = str 1 fs;
    values = List.map (fun v -> let vs = fields v in (str 1 vs, Option.value (int 2 vs) ~default:0)) (all 2 fs);
    allow_alias = int 2 opts = Some 1;
  }

let rec message_of b =
  let fs = fields b in
  let opts = fields (str 7 fs) in
  {
    mname = str 1 fs;
    fields = List.map field_of (all 2 fs);
    nested = List.map message_of (all 3 fs);
    enums = List.map enum_of (all 4 fs);
    oneofs = List.map (fun o -> str 1 (fields o)) (all 8 fs);
    map_entry = int 7 opts = Some 1;
  }

let file_of b =
  let fs = fields b in
  {
    fname = str 1 fs;
    package = str 2 fs;
    syntax = (match str 12 fs with "" -> "proto2" | s -> s);
    messages = List.map message_of (all 4 fs);
    fenums = List.map enum_of (all 5 fs);
  }

(* A FileDescriptorSet: its files, in order. *)
let set_of_string s =
  match List.map file_of (all 1 (fields s)) with
  | files -> Ok files
  | exception Bad m -> Error ("descriptor set: " ^ m)
