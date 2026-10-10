(* FileDescriptorSets built by hand, for tests that need descriptors they
   can change one detail of at a time (no protoc). One file per set. *)

type field = {
  fname : string;
  num : int;
  ty : int;  (** descriptor.proto's FieldDescriptorProto.Type: 3 int64, 5 int32, 8 bool, 9 string, 11 message, 14 enum, ... *)
  label : int;  (** 1 optional, 2 required, 3 repeated *)
  tref : string option;  (** a message or enum type, by full name *)
  optional : bool;  (** proto3 optional *)
  oneof : int option;
  json : string option;
}

type message = { mname : string; fields : field list; oneofs : string list }
type enum = { ename : string; values : (string * int) list }
type file = { pkg : string; syntax : string; messages : message list; enums : enum list }

let field ?(label = 1) ?tref ?(optional = false) ?oneof ?json fname num ty = { fname; num; ty; label; tref; optional; oneof; json }

let varint n =
  let b = Buffer.create 4 in
  let rec go n = if n < 0x80 then Buffer.add_char b (Char.chr n) else (Buffer.add_char b (Char.chr (n land 0x7f lor 0x80)); go (n lsr 7)) in
  go n;
  Buffer.contents b

let ld num s = varint ((num lsl 3) lor 2) ^ varint (String.length s) ^ s
let vi num n = varint (num lsl 3) ^ varint n

let encode_field f =
  ld 1 f.fname ^ vi 3 f.num ^ vi 4 f.label ^ vi 5 f.ty
  ^ (match f.tref with Some t -> ld 6 ("." ^ t) | None -> "")
  ^ (match f.oneof with Some i -> vi 9 i | None -> "")
  ^ (match f.json with Some j -> ld 10 j | None -> ld 10 f.fname)
  ^ if f.optional then vi 17 1 else ""

let encode_message m =
  ld 1 m.mname ^ String.concat "" (List.map (fun f -> ld 2 (encode_field f)) m.fields)
  ^ String.concat "" (List.map (fun o -> ld 8 (ld 1 o)) m.oneofs)

let encode_enum e = ld 1 e.ename ^ String.concat "" (List.map (fun (n, v) -> ld 2 (ld 1 n ^ vi 2 v)) e.values)

let encode (f : file) =
  ld 1
    (ld 1 (f.pkg ^ ".proto") ^ ld 2 f.pkg
    ^ String.concat "" (List.map (fun m -> ld 4 (encode_message m)) f.messages)
    ^ String.concat "" (List.map (fun e -> ld 5 (encode_enum e)) f.enums)
    ^ ld 12 f.syntax)

let registry (f : file) =
  match Cordis_cel.Proto.of_descriptor_sets [ encode f ] with Ok r -> r | Error m -> failwith m
