(* Protobuf messages as CEL values, driven by descriptors (no generated
   code): the runtime half of RFC 0003's protobuf importer.

   Descriptors are loaded from JSON (cordis/cel/conformance/descriptors.json
   is the conformance suite's; the importer will produce the same shape from
   `buf build` descriptor sets). A message value stores only its present
   fields, in CEL form; reading an absent field gives its default. The
   well-known types map onto CEL values at every boundary, as cel-spec says:
   the wrappers become nullable scalars, Value / Struct / ListValue become
   JSON-like values, Timestamp and Duration become CEL's, and Any is
   unpacked. Enum values are ints unless strong enums are on. *)

open Value

(* ---- descriptors ------------------------------------------------------------------ *)

type field = {
  name : string;
  json_name : string;
  number : int;
  ty : string;  (** "int32", "message", "enum", ... *)
  repeated : bool;
  type_name : string option;
  oneof : string option;
  presence : bool;  (** explicit presence: has() reports whether it was set *)
  default : Yojson.Safe.t;
  map : (field * field) option;  (** key and value fields of a map field *)
  extendee : string option;
  full_name : string option;  (** extensions are addressed by full name *)
}

type message = { full_name : string; syntax : string; fields : field list }

(* ---- registries (docs/cordis/rfc/0004-descriptor-native-types.md) ---------------------- *)

(* A registry: the messages, enums and extensions a revision's descriptor
   sets define. Every check and evaluation runs with one bound (for its
   dynamic extent, per domain), so a candidate revision's descriptors are
   never seen by the live one. The default registry is the one [load]
   fills, for the conformance suite and for callers that bind none. *)
type registry = {
  messages : (string, message) Hashtbl.t;
  enums : (string, (string * int) list) Hashtbl.t;
  extensions : (string, field) Hashtbl.t;
  mutable strong : bool;  (** enum values as typed enums rather than ints *)
}

let create () =
  { messages = Hashtbl.create 64; enums = Hashtbl.create 16; extensions = Hashtbl.create 16; strong = false }

let default = create ()
let key = Domain.DLS.new_key (fun () -> default)
let current () = Domain.DLS.get key

let with_registry r f =
  let saved = current () in
  Domain.DLS.set key r;
  Fun.protect ~finally:(fun () -> Domain.DLS.set key saved) f

let messages () = (current ()).messages
let enums () = (current ()).enums
let extensions () = (current ()).extensions
let strong_enums () = (current ()).strong
let set_strong_enums b = (current ()).strong <- b

module J = Yojson.Safe.Util

let rec field_of_json (j : Yojson.Safe.t) : field =
  let str k = J.to_string (J.member k j) and opt k = J.to_string_option (J.member k j) in
  {
    name = str "name";
    json_name = (match opt "json_name" with Some n -> n | None -> str "name");
    number = J.to_int (J.member "number" j);
    ty = str "type";
    repeated = J.to_bool (J.member "repeated" j);
    type_name = opt "type_name";
    oneof = opt "oneof";
    presence = J.to_bool (J.member "presence" j);
    default = J.member "default" j;
    map =
      (match J.member "map" j with
       | `Null -> None
       | m -> Some (field_of_json (J.member "key" m), field_of_json (J.member "value" m)));
    extendee = opt "extendee";
    full_name = opt "full_name";
  }

let load_json (j : Yojson.Safe.t) =
  List.iter
    (fun (name, m) ->
      Hashtbl.replace (messages ()) name
        { full_name = name; syntax = J.to_string (J.member "syntax" m);
          fields = List.map field_of_json (J.to_list (J.member "fields" m)) })
    (J.to_assoc (J.member "messages" j));
  List.iter
    (fun (name, e) ->
      Hashtbl.replace (enums ()) name
        (List.map (function `List [ `String n; `Int v ] -> (n, v) | _ -> ("", 0)) (J.to_list (J.member "values" e))))
    (J.to_assoc (J.member "enums" j));
  List.iter
    (fun x -> let f = field_of_json x in Hashtbl.replace (extensions ()) (Option.get f.full_name) f)
    (J.to_list (J.member "extensions" j))

let load path = load_json (Yojson.Safe.from_file path)
let find_message name = Hashtbl.find_opt (messages ()) name

let find_field (m : message) name =
  match List.find_opt (fun f -> f.name = name) m.fields with
  | Some f -> Some f
  | None -> (
      match Hashtbl.find_opt (extensions ()) name with
      | Some f when f.extendee = Some m.full_name -> Some f
      | _ -> None)

(* ---- registries from FileDescriptorSets ---------------------------------------------- *)

let type_names =
  [| ""; "double"; "float"; "int64"; "uint64"; "int32"; "fixed64"; "fixed32"; "bool"; "string"; "group";
     "message"; "bytes"; "uint32"; "enum"; "sfixed32"; "sfixed64"; "sint32"; "sint64" |]

let strip_dot s = if String.length s > 0 && s.[0] = '.' then String.sub s 1 (String.length s - 1) else s

(* A C-escaped default for bytes, as protoc writes it. *)
let unescape_c s =
  let b = Buffer.create (String.length s) and i = ref 0 and n = String.length s in
  while !i < n do
    if s.[!i] = '\\' && !i + 1 < n then begin
      let c = s.[!i + 1] in
      (match c with
       | 'n' -> Buffer.add_char b '\n'; i := !i + 2
       | 'r' -> Buffer.add_char b '\r'; i := !i + 2
       | 't' -> Buffer.add_char b '\t'; i := !i + 2
       | '0' .. '7' ->
           let j = ref (!i + 1) and v = ref 0 in
           while !j < n && !j < !i + 4 && s.[!j] >= '0' && s.[!j] <= '7' do v := (!v * 8) + Char.code s.[!j] - 48; incr j done;
           Buffer.add_char b (Char.chr (!v land 255)); i := !j
       | 'x' ->
           let j = ref (!i + 2) and v = ref 0 in
           let hex c = match c with '0' .. '9' -> Some (Char.code c - 48) | 'a' .. 'f' -> Some (Char.code c - 87) | 'A' .. 'F' -> Some (Char.code c - 55) | _ -> None in
           while !j < n && !j < !i + 4 && hex s.[!j] <> None do v := (!v * 16) + Option.get (hex s.[!j]); incr j done;
           Buffer.add_char b (Char.chr (!v land 255)); i := !j
       | c -> Buffer.add_char b c; i := !i + 2)
    end
    else (Buffer.add_char b s.[!i]; incr i)
  done;
  Buffer.contents b

(* protoc's default_value text, as the JSON loader's default *)
let default_json ty text : Yojson.Safe.t =
  if text = "" then `Null
  else
    match ty with
    | "bool" -> `Bool (text = "true")
    | "string" -> `String text
    | "bytes" -> `String (unescape_c text)
    | "enum" -> `String text
    | "double" | "float" ->
        `Assoc [ ("double", match text with "inf" -> `String "Infinity" | "-inf" -> `String "-Infinity" | "nan" -> `String "NaN" | t -> `Float (float_of_string t)) ]
    | _ -> (match int_of_string_opt text with Some n -> `Int n | None -> `Intlit text)

(* Add a FileDescriptorSet's definitions to a registry. A full name already
   there must be defined identically. *)
let add_descriptor_set (r : registry) (bytes : string) =
  match Descriptor_set.set_of_string bytes with
  | Result.Error m -> Result.Error m
  | Result.Ok files -> (
      let module D = Descriptor_set in
      let entries = Hashtbl.create 16 in
      (* map entry messages, by full name, to read map fields *)
      List.iter
        (fun (f : D.file) ->
          let rec go scope (m : D.message) =
            let full = scope ^ m.mname in
            Hashtbl.replace entries full m;
            List.iter (go (full ^ ".")) m.nested
          in
          List.iter (go (if f.package = "" then "" else f.package ^ ".")) f.messages)
        files;
      let field_of syntax (m : D.message option) ?full_name (fd : D.field) : field =
        let ty = if fd.ty >= 1 && fd.ty < Array.length type_names then type_names.(fd.ty) else "message" in
        let repeated = fd.label = D.Repeated in
        let oneof = match m, fd.oneof_index with Some m, Some i -> List.nth_opt m.oneofs i | _ -> None in
        let plain (fd : D.field) : field =
          let ty = type_names.(fd.ty) in
          { name = fd.name; json_name = (if fd.json_name = "" then fd.name else fd.json_name); number = fd.number; ty;
            repeated = false; type_name = (if fd.type_name = "" then None else Some (strip_dot fd.type_name));
            oneof = None; presence = fd.ty = 11 || syntax <> "proto3"; default = `Null; map = None; extendee = None;
            full_name = None }
        in
        let map =
          match Hashtbl.find_opt entries (strip_dot fd.type_name) with
          | Some e when e.map_entry && repeated ->
              let k = List.find (fun (x : D.field) -> x.number = 1) e.fields and v = List.find (fun (x : D.field) -> x.number = 2) e.fields in
              Some (plain k, plain v)
          | _ -> None
        in
        {
          name = fd.name;
          json_name = (if fd.json_name = "" then fd.name else fd.json_name);
          number = fd.number;
          ty;
          repeated;
          type_name = (if fd.type_name = "" then None else Some (strip_dot fd.type_name));
          oneof;
          presence = (not repeated) && (fd.ty = 11 || oneof <> None || fd.proto3_optional || syntax <> "proto3");
          default = (if repeated then `Null else default_json ty fd.default_value);
          map;
          extendee = (if fd.extendee = "" then None else Some (strip_dot fd.extendee));
          full_name;
        }
      in
      let conflicts = ref [] in
      let put tbl name v = match Hashtbl.find_opt tbl name with Some old when old <> v -> conflicts := name :: !conflicts | _ -> Hashtbl.replace tbl name v in
      List.iter
        (fun (f : D.file) ->
          let syntax = f.syntax in
          let rec go scope (m : D.message) =
            let full = scope ^ m.mname in
            put r.messages full { full_name = full; syntax; fields = List.map (field_of syntax (Some m)) m.fields };
            List.iter (fun (e : D.enum) -> put r.enums (full ^ "." ^ e.ename) e.values) m.enums;
            List.iter (fun (x : D.field) -> let fn = full ^ "." ^ x.name in put r.extensions fn (field_of syntax None ~full_name:fn x)) m.extensions;
            List.iter (go (full ^ ".")) m.nested
          in
          let scope = if f.package = "" then "" else f.package ^ "." in
          List.iter (go scope) f.messages;
          List.iter (fun (e : D.enum) -> put r.enums (scope ^ e.ename) e.values) f.fenums;
          List.iter (fun (x : D.field) -> let fn = scope ^ x.name in put r.extensions fn (field_of syntax None ~full_name:fn x)) f.fextensions)
        files;
      match !conflicts with
      | [] -> Result.Ok ()
      | names -> Result.Error ("defined differently by two descriptor sets: " ^ String.concat ", " (List.sort_uniq compare names)))

(* A registry with the well-known types and the given sets. *)
let of_descriptor_sets ?(strong = false) (sets : string list) =
  let r = create () in
  r.strong <- strong;
  List.fold_left (fun acc set -> Result.bind acc (fun () -> add_descriptor_set r set)) (add_descriptor_set r Wkt_set.data) sets
  |> Result.map (fun () -> r)

(* An enum value by qualified name: pkg.Enum.VALUE or pkg.Msg.Enum.VALUE. *)
let enum_constant name =
  match String.rindex_opt name '.' with
  | None -> None
  | Some i -> (
      let enum = String.sub name 0 i and v = String.sub name (i + 1) (String.length name - i - 1) in
      match Hashtbl.find_opt (enums ()) enum with
      | Some vs -> Option.map (fun n -> if strong_enums () then Enum { tname = enum; num = n } else Int (Int64.of_int n)) (List.assoc_opt v vs)
      | None -> None)

(* ---- protobuf wire primitives --------------------------------------------------------- *)

exception Bad_wire of string

let varint s pos =
  let rec go acc shift =
    if !pos >= String.length s then raise (Bad_wire "truncated varint");
    let b = Char.code s.[!pos] in
    incr pos;
    let acc = Int64.logor acc (Int64.shift_left (Int64.of_int (b land 0x7f)) shift) in
    if b land 0x80 <> 0 then go acc (shift + 7) else acc
  in
  go 0L 0

let fixed s pos n =
  if !pos + n > String.length s then raise (Bad_wire "truncated fixed");
  let v = ref 0L in
  for k = n - 1 downto 0 do v := Int64.logor (Int64.shift_left !v 8) (Int64.of_int (Char.code s.[!pos + k])) done;
  pos := !pos + n;
  !v

let wire_bytes s pos =
  let n = Int64.to_int (varint s pos) in
  if !pos + n > String.length s then raise (Bad_wire "truncated bytes");
  let b = String.sub s !pos n in
  pos := !pos + n;
  b

let zigzag v = Int64.logxor (Int64.shift_right_logical v 1) (Int64.neg (Int64.logand v 1L))

(* One wire value of a scalar field, as CEL. *)
let wire_scalar (f : field) wire s pos =
  match f.ty, wire with
  | ("int32" | "int64" | "enum"), 0 ->
      let v = varint s pos in
      let v = if f.ty = "int64" then v else Int64.of_int32 (Int64.to_int32 v) in
      if f.ty = "enum" && strong_enums () then Enum { tname = Option.get f.type_name; num = Int64.to_int v } else Int v
  | ("uint32" | "uint64"), 0 -> Uint (if f.ty = "uint32" then Int64.logand (varint s pos) 0xFFFFFFFFL else varint s pos)
  | ("sint32" | "sint64"), 0 -> Int (zigzag (varint s pos))
  | "bool", 0 -> Bool (varint s pos <> 0L)
  | "fixed32", 5 -> Uint (fixed s pos 4)
  | "sfixed32", 5 -> Int (Int64.of_int32 (Int64.to_int32 (fixed s pos 4)))
  | "float", 5 -> Double (Int32.float_of_bits (Int64.to_int32 (fixed s pos 4)))
  | "fixed64", 1 -> Uint (fixed s pos 8)
  | "sfixed64", 1 -> Int (fixed s pos 8)
  | "double", 1 -> Double (Int64.float_of_bits (fixed s pos 8))
  | "string", 2 -> String (wire_bytes s pos)
  | "bytes", 2 -> Bytes (wire_bytes s pos)
  | _ -> raise (Bad_wire ("wire type mismatch for " ^ f.name))

(* An enum value from an int (in int32 range) or a name, for strong enums'
   constructors: pkg.Enum(2), pkg.Enum('NAME'). *)
let enum_exists name = Hashtbl.mem (enums ()) name

let make_enum name (v : Value.t) =
  let mk n = if strong_enums () then Enum { tname = name; num = n } else Int (Int64.of_int n) in
  match v, Hashtbl.find_opt (enums ()) name with
  | Int i, Some _ ->
      if Int64.compare i (-2147483648L) < 0 || Int64.compare i 2147483647L > 0 then Error "range error"
      else mk (Int64.to_int i)
  | String s, Some vs -> (match List.assoc_opt s vs with Some n -> mk n | None -> Error ("invalid enum value: " ^ s))
  | _ -> no_overload name

(* ---- well-known types -------------------------------------------------------------- *)

let wrapper_scalar = function
  | "google.protobuf.BoolValue" -> Some "bool"
  | "google.protobuf.BytesValue" -> Some "bytes"
  | "google.protobuf.DoubleValue" -> Some "double"
  | "google.protobuf.FloatValue" -> Some "float"
  | "google.protobuf.Int32Value" -> Some "int32"
  | "google.protobuf.Int64Value" -> Some "int64"
  | "google.protobuf.StringValue" -> Some "string"
  | "google.protobuf.UInt32Value" -> Some "uint32"
  | "google.protobuf.UInt64Value" -> Some "uint64"
  | _ -> None

(* The CEL value of a well-known message built from its fields, or the
   message itself for an ordinary type. *)
let rec unwrap tname (fields : (string * Value.t) list) =
  let get k = List.assoc_opt k fields in
  match tname with
  | t when wrapper_scalar t <> None -> (
      match get "value", wrapper_scalar t with
      | Some v, _ -> v
      | None, Some s -> zero_scalar s
      | None, None -> Null)
  | "google.protobuf.Timestamp" ->
      let s = match get "seconds" with Some (Int s) -> s | _ -> 0L in
      let n = match get "nanos" with Some (Int n) -> Int64.to_int n | _ -> 0 in
      (match Ctime.check_ts (s, n) with Ok (s, n) -> Timestamp (s, n) | Error e -> Error e)
  | "google.protobuf.Duration" ->
      let s = match get "seconds" with Some (Int s) -> s | _ -> 0L in
      let n = match get "nanos" with Some (Int n) -> Int64.to_int n | _ -> 0 in
      (match Ctime.check_dur (s, n) with Ok (s, n) -> Duration (s, n) | Error e -> Error e)
  | "google.protobuf.Value" -> (
      match fields with
      | [] -> Null
      | [ ("null_value", _) ] -> Null
      | [ (("number_value" | "string_value" | "bool_value" | "struct_value" | "list_value"), v) ] -> v
      | _ -> Error "invalid google.protobuf.Value")
  | "google.protobuf.Struct" -> (match get "fields" with Some m -> m | None -> Map [])
  | "google.protobuf.ListValue" -> (match get "values" with Some l -> l | None -> List [||])
  | "google.protobuf.Any" -> (
      (* an Any that cannot be unpacked stays a raw message, compared
         bytewise, as cel-go does *)
      let raw = Message { tname; fields = List.sort (fun (a, _) (b, _) -> compare a b) fields } in
      match get "type_url", get "value" with
      | Some (String url), Some (Bytes b) -> (match decode_any url b with Error _ -> raw | v -> v)
      | Some (String url), None -> (match decode_any url "" with Error _ -> raw | v -> v)
      | _ -> raw)
  | _ -> Message { tname; fields = List.sort (fun (a, _) (b, _) -> compare a b) fields }

and zero_scalar = function
  | "bool" -> Bool false
  | "bytes" -> Bytes ""
  | "string" -> String ""
  | "double" | "float" -> Double 0.0
  | "int32" | "int64" | "sint32" | "sint64" | "sfixed32" | "sfixed64" | "enum" -> Int 0L
  | "uint32" | "uint64" | "fixed32" | "fixed64" -> Uint 0L
  | _ -> Null

and decode_any url bytes =
  let tname = match String.rindex_opt url '/' with Some i -> String.sub url (i + 1) (String.length url - i - 1) | None -> url in
  match find_message tname with
  | None -> Error ("unknown type in Any: " ^ tname)
  | Some m -> ( match decode_message_bin m bytes with Ok fields -> unwrap tname fields | Result.Error e -> Error e)

(* ---- protobuf binary, for Any's packed value --------------------------------------- *)

and decode_message_bin (m : message) s =
  try Ok (decode_fields m s) with Bad_wire e -> Error e

and decode_fields (m : message) s =
  let pos = ref 0 in
  let acc : (string, Value.t list) Hashtbl.t = Hashtbl.create 8 in
  let add name v = Hashtbl.replace acc name (v :: Option.value (Hashtbl.find_opt acc name) ~default:[]) in
  while !pos < String.length s do
    let key = Int64.to_int (varint s pos) in
    let num = key lsr 3 and wire = key land 7 in
    match List.find_opt (fun (f : field) -> f.number = num) m.fields with
    | None -> (
        (* skip an unknown field *)
        match wire with
        | 0 -> ignore (varint s pos)
        | 1 -> ignore (fixed s pos 8)
        | 2 -> ignore (wire_bytes s pos)
        | 5 -> ignore (fixed s pos 4)
        | _ -> raise (Bad_wire "unsupported wire type"))
    | Some f -> (
        match f.map, f.ty with
        | Some (kf, vf), _ ->
            let entry = wire_bytes s pos in
            let em = { full_name = ""; syntax = "proto3"; fields = [ { kf with name = "key" }; { vf with name = "value" } ] } in
            let fs = decode_fields em entry in
            let k = match List.assoc_opt "key" fs with Some k -> k | None -> zero_scalar kf.ty in
            let v = match List.assoc_opt "value" fs with Some v -> v | None -> default_value vf in
            add f.name (List [| k; v |])
        | None, "message" ->
            let tname = Option.get f.type_name in
            let sub = Option.get (find_message tname) in
            add f.name (unwrap tname (decode_fields sub (wire_bytes s pos)))
        | None, _ when wire = 2 && f.repeated && f.ty <> "string" && f.ty <> "bytes" ->
            (* packed repeated scalars *)
            let body = wire_bytes s pos in
            let p = ref 0 in
            let w = match f.ty with "double" | "fixed64" | "sfixed64" -> 1 | "float" | "fixed32" | "sfixed32" -> 5 | _ -> 0 in
            while !p < String.length body do add f.name (wire_scalar f w body p) done
        | None, _ -> add f.name (wire_scalar f wire s pos))
  done;
  List.filter_map
    (fun (f : field) ->
      match Hashtbl.find_opt acc f.name with
      | None -> None
      | Some vs ->
          let vs = List.rev vs in
          if f.map <> None then
            Some (f.name, Value.make_map (List.map (function List [| k; v |] -> (k, v) | _ -> (Null, Null)) vs))
          else if f.repeated then Some (f.name, List (Array.of_list vs))
          else Some (f.name, List.nth vs (List.length vs - 1)))
    m.fields

(* ---- defaults and presence ------------------------------------------------------------ *)

and default_value (f : field) =
  if f.map <> None then Map []
  else if f.repeated then List [||]
  else
    match f.ty, f.type_name with
    | "message", Some t -> (
        match t with
        | _ when wrapper_scalar t <> None -> Null
        | "google.protobuf.Value" -> Null
        | "google.protobuf.Any" -> Null
        | _ -> unwrap t [])
    | "enum", Some t -> (
        let n =
          match f.default with
          | `String name -> (match Hashtbl.find_opt (enums ()) t with Some vs -> Option.value (List.assoc_opt name vs) ~default:0 | None -> 0)
          | `Int n -> n
          | _ -> (match Hashtbl.find_opt (enums ()) t with Some ((_, n) :: _) -> n | _ -> 0)
        in
        if strong_enums () then Enum { tname = t; num = n } else Int (Int64.of_int n))
    | ty, _ -> (
        match f.default with
        | `Null -> zero_scalar ty
        | `Bool b -> Bool b
        | `Int n -> if String.length ty > 0 && (ty.[0] = 'u' || ty.[0] = 'f' && ty <> "float") then Uint (Int64.of_int n) else Int (Int64.of_int n)
        | `Intlit s -> Int (Int64.of_string s)
        | `String s -> if ty = "bytes" then Bytes s else String s
        | `Assoc [ ("double", `Float d) ] -> Double d
        | `Assoc [ ("double", `Int d) ] -> Double (float_of_int d)
        | `Assoc [ ("double", `String "NaN") ] -> Double Float.nan
        | `Assoc [ ("double", `String "Infinity") ] -> Double Float.infinity
        | `Assoc [ ("double", `String "-Infinity") ] -> Double Float.neg_infinity
        | _ -> zero_scalar ty)

(* ---- assignment: CEL values into fields ------------------------------------------------- *)

let to_float32 d = Int32.float_of_bits (Int32.bits_of_float d)

let rec convert_scalar ty (v : Value.t) =
  match ty, v with
  | ("int64" | "sint64" | "sfixed64"), Int _ -> Ok v
  | ("int32" | "sint32" | "sfixed32"), Int i ->
      if Int64.compare i (-2147483648L) < 0 || Int64.compare i 2147483647L > 0 then Error "range error" else Ok v
  | ("uint64" | "fixed64"), Uint _ -> Ok v
  | ("uint32" | "fixed32"), Uint u ->
      if Int64.unsigned_compare u 4294967295L > 0 then Error "range error" else Ok v
  | "double", Double _ -> Ok v
  | "float", Double d -> Ok (Double (to_float32 d)) (* stored as a float32 *)
  | "bool", Bool _ | "string", String _ | "bytes", Bytes _ -> Ok v
  | _ -> Error (Printf.sprintf "no such overload: field of type %s from %s" ty (type_name (type_of v)))

(* A CEL value as the content of a message-typed field: the well-known
   types accept their CEL forms. *)
and convert_message tname (v : Value.t) =
  match tname, v with
  | t, Null when wrapper_scalar t <> None -> Ok Null
  | t, _ when wrapper_scalar t <> None -> convert_scalar (Option.get (wrapper_scalar t)) v
  | "google.protobuf.Timestamp", Timestamp _ | "google.protobuf.Duration", Duration _ -> Ok v
  | "google.protobuf.Value", _ -> to_json_value v
  | "google.protobuf.Struct", Map _ -> to_json_value v
  | "google.protobuf.ListValue", List _ -> to_json_value v
  | ("google.protobuf.Struct" | "google.protobuf.ListValue"), Null -> Error ("unsupported null assignment to " ^ tname)
  | "google.protobuf.Any", Null -> Ok Null
  | "google.protobuf.Any", _ -> Ok v
  | t, Message { tname; _ } when t = tname -> Ok v
  | t, Null -> ignore t; Ok Null
  | t, _ -> Error (Printf.sprintf "no such overload: field of type %s from %s" t (type_name (type_of v)))

(* Values a google.protobuf.Value can hold: JSON. Numbers become doubles. *)
and to_json_value (v : Value.t) =
  match v with
  | Null | Bool _ | String _ | Double _ -> Ok v
  (* integers beyond JSON's exact range (2^53 - 1) become strings, as in
     protobuf's JSON mapping *)
  | Int i ->
      if Int64.abs i > 9007199254740991L || i = Int64.min_int then Ok (String (Int64.to_string i))
      else Ok (Double (Int64.to_float i))
  | Uint u ->
      if Int64.unsigned_compare u 9007199254740991L > 0 then Ok (String (Printf.sprintf "%Lu" u))
      else Ok (Double (uint_to_float u))
  | Message { tname = "google.protobuf.Empty"; _ } -> Ok (Map [])
  | Message { tname = "google.protobuf.FieldMask"; fields } -> (
      match List.assoc_opt "paths" fields with
      | Some (List ps) -> Ok (String (String.concat "," (Array.to_list (Array.map (function String p -> p | _ -> "") ps))))
      | _ -> Ok (String ""))
  | Bytes b -> Ok (String (Ext_encoders.encode b))
  | Timestamp (s, n) -> Ok (String (Ctime.format_timestamp (s, n)))
  | Duration (s, n) -> Ok (String (Ctime.format_duration (s, n)))
  | List xs ->
      let rec go acc = function
        | [] -> Ok (List (Array.of_list (List.rev acc)))
        | x :: rest -> (match to_json_value x with Ok y -> go (y :: acc) rest | Error e -> Error e)
      in
      go [] (Array.to_list xs)
  | Map m ->
      let rec go acc = function
        | [] -> Ok (Value.make_map (List.rev acc))
        | (String k, x) :: rest -> (match to_json_value x with Ok y -> go ((String k, y) :: acc) rest | Error e -> Error e)
        | _ -> Error "bad key type: google.protobuf.Struct keys are strings"
      in
      go [] m
  | _ -> Error (Printf.sprintf "no such overload: google.protobuf.Value from %s" (type_name (type_of v)))

and convert_element (f : field) v =
  match f.ty, f.type_name with
  | "message", Some t -> convert_message t v
  | "enum", Some t -> (
      match v with
      | Int i -> if Int64.compare i (-2147483648L) < 0 || Int64.compare i 2147483647L > 0 then Error "range error" else Ok (if strong_enums () then Enum { tname = t; num = Int64.to_int i } else v)
      | Enum { tname; _ } when tname = t -> Ok v
      | _ -> Error "no such overload: enum field")
  | ty, _ -> convert_scalar ty v

(* In repeated and map fields, null elements of message types are dropped,
   except where null is itself a value (Any, Value). *)
let prunes_null (f : field) =
  f.ty = "message" && f.type_name <> Some "google.protobuf.Any" && f.type_name <> Some "google.protobuf.Value"

let convert_field (f : field) (v : Value.t) =
  match f.map, v with
  | Some (kf, vf), Map m ->
      let rec go acc = function
        | [] -> Ok (Value.make_map (List.rev acc))
        | (k, x) :: rest -> (
            match convert_scalar kf.ty k, convert_element vf x with
            | Ok _, Ok Null when prunes_null vf -> go acc rest (* null entries are pruned *)
            | Ok k, Ok x -> go ((k, x) :: acc) rest
            | Error e, _ | _, Error e -> Error e)
      in
      go [] m
  | Some _, _ -> Error "no such overload: map field"
  | None, List xs when f.repeated ->
      let rec go acc = function
        | [] -> Ok (List (Array.of_list (List.rev acc)))
        | x :: rest -> (
            match convert_element f x with
            | Ok Null when prunes_null f -> go acc rest (* null elements are pruned *)
            | Ok y -> go (y :: acc) rest
            | Error e -> Error e)
      in
      go [] (Array.to_list xs)
  | None, _ when f.repeated -> Error "no such overload: repeated field"
  | None, _ -> convert_element f v

(* Whether a stored value counts as set: implicit-presence fields that hold
   their default, and empty repeated fields, are absent. *)
let is_set (f : field) v =
  match v with
  | _ when f.type_name = Some "google.protobuf.Value" && not f.repeated -> true (* null is a Value *)
  | List [||] | Map [] when f.repeated || f.map <> None -> false
  | Null when f.ty = "message" -> false
  | _ -> f.presence || f.oneof <> None || not (Value.equal v (default_value f))

(* ---- construction, selection ----------------------------------------------------------- *)

let make tname (assigns : (string * Value.t) list) =
  match find_message tname with
  | None -> Error (Printf.sprintf "unknown type: %s" tname)
  | Some m ->
      let rec go acc = function
        | [] when tname = "google.protobuf.Any" && not (List.mem_assoc "type_url" acc) ->
            Result.Error "conversion error: google.protobuf.Any has no type URL"
        | [] -> Ok (unwrap tname acc)
        | (name, v) :: rest -> (
            match find_field m name with
            | None -> Error (Printf.sprintf "no such field: %s" name)
            | Some f -> (
                match convert_field f v with
                | Error e -> Error e
                | Ok v ->
                    let acc = List.remove_assoc name acc in
                    go (if is_set f v then (name, v) :: acc else acc) rest))
      in
      (match go [] assigns with Ok v -> v | Error e -> Value.Error e)

let select tname fields name ~test_only =
  match find_message tname with
  | None -> Error (Printf.sprintf "unknown type: %s" tname)
  | Some m -> (
      match find_field m name with
      | None -> Error (Printf.sprintf "no such field: %s" name)
      | Some f -> (
          match List.assoc_opt name fields, test_only with
          | Some _, true -> Bool true
          | None, true -> Bool false
          | Some v, false -> v
          | None, false -> default_value f))

(* ---- protobuf JSON (the conformance corpus's message values) -------------------------- *)

let rec of_json tname (j : Yojson.Safe.t) : Value.t =
  match tname, j with
  | t, _ when wrapper_scalar t <> None -> scalar_of_json (Option.get (wrapper_scalar t)) j
  | "google.protobuf.Timestamp", `String s -> (match Ctime.parse_timestamp s with Ok (s, n) -> Timestamp (s, n) | Error e -> Error e)
  | "google.protobuf.Duration", `String s -> (match Ctime.parse_duration s with Ok (s, n) -> Duration (s, n) | Error e -> Error e)
  | "google.protobuf.Value", _ -> json_value j
  | "google.protobuf.Struct", _ -> json_value j
  | "google.protobuf.ListValue", _ -> json_value j
  | "google.protobuf.Any", `Assoc kv -> (
      match List.assoc_opt "@type" kv with
      | Some (`String url) ->
          let inner = match String.rindex_opt url '/' with Some i -> String.sub url (i + 1) (String.length url - i - 1) | None -> url in
          let is_wkt = String.length inner > 16 && String.sub inner 0 16 = "google.protobuf." in
          if is_wkt && List.mem_assoc "value" kv then of_json inner (List.assoc "value" kv)
          else of_json inner (`Assoc (List.remove_assoc "@type" kv))
      | _ -> Error "Any without @type")
  | "google.protobuf.FieldMask", `String s ->
      Message { tname; fields = (if s = "" then [] else [ ("paths", List (Array.of_list (List.map (fun p -> String p) (String.split_on_char ',' s)))) ]) }
  | _, `Assoc kv -> (
      match find_message tname with
      | None -> Error ("unknown type: " ^ tname)
      | Some m ->
          let fields =
            List.filter_map
              (fun (k, v) ->
                match List.find_opt (fun f -> f.json_name = k || f.name = k) m.fields with
                | Some f -> Some (f.name, field_of_json_value f v)
                | None -> (match Hashtbl.find_opt (extensions ()) (String.sub k 1 (max 0 (String.length k - 2))) with
                           | Some f -> Some (Option.get f.full_name, field_of_json_value f v)
                           | None -> None))
              kv
          in
          unwrap tname (List.sort (fun (a, _) (b, _) -> compare a b) fields))
  | _ -> Error ("bad JSON for " ^ tname)

and field_of_json_value (f : field) (j : Yojson.Safe.t) =
  match f.map, j with
  | Some (kf, vf), `Assoc kv ->
      Value.make_map (List.map (fun (k, v) -> (scalar_of_json kf.ty (`String k), element_of_json vf v)) kv)
  | None, `List xs when f.repeated -> List (Array.of_list (List.map (element_of_json f) xs))
  | _ -> element_of_json f j

and element_of_json (f : field) j =
  match f.ty, f.type_name with
  | "message", Some t -> of_json t j
  | "enum", Some t -> (
      let n =
        match j with
        | `Int n -> n
        | `String name -> (match Hashtbl.find_opt (enums ()) t with Some vs -> Option.value (List.assoc_opt name vs) ~default:0 | None -> 0)
        | _ -> 0
      in
      if t = "google.protobuf.NullValue" then Null
      else if strong_enums () then Enum { tname = t; num = n } else Int (Int64.of_int n))
  | ty, _ -> scalar_of_json ty j

and scalar_of_json ty (j : Yojson.Safe.t) =
  let num = function
    | `Int i -> Some (Int64.of_int i)
    | `Intlit s | `String s -> Int64.of_string_opt s
    | `Float f -> Some (Int64.of_float f)
    | _ -> None
  in
  match ty, j with
  | ("int32" | "int64" | "sint32" | "sint64" | "sfixed32" | "sfixed64"), _ -> (match num j with Some i -> Int i | None -> Error "bad int")
  | ("uint32" | "uint64" | "fixed32" | "fixed64"), `String s -> (match Int64.of_string_opt ("0u" ^ s) with Some u -> Uint u | None -> Error "bad uint")
  | ("uint32" | "uint64" | "fixed32" | "fixed64"), _ -> (match num j with Some u -> Uint u | None -> Error "bad uint")
  | "float", (`Float _ | `Int _ | `String _) -> (
      match scalar_of_json "double" j with Double d -> Double (to_float32 d) | v -> v)
  | "double", `Float f -> Double f
  | "double", `Int i -> Double (float_of_int i)
  | "double", `String "NaN" -> Double Float.nan
  | "double", `String "Infinity" -> Double Float.infinity
  | "double", `String "-Infinity" -> Double Float.neg_infinity
  | "double", `String s -> (match float_of_string_opt s with Some f -> Double f | None -> Error "bad double")
  | "bool", `Bool b -> Bool b
  | "string", `String s -> String s
  | "bytes", `String s -> (match Ext_encoders.decode s with Ok b -> Bytes b | Result.Error e -> Error e)
  | _ -> Error ("bad JSON for " ^ ty)

and json_value (j : Yojson.Safe.t) : Value.t =
  match j with
  | `Null -> Null
  | `Bool b -> Bool b
  | `Int i -> Double (float_of_int i)
  | `Intlit s -> Double (float_of_string s)
  | `Float f -> Double f
  | `String s -> String s
  | `List xs -> List (Array.of_list (List.map json_value xs))
  | `Assoc kv -> Value.make_map (List.map (fun (k, v) -> (String k, json_value v)) kv)
