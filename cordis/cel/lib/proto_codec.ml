(* The boundary codec for protobuf messages (docs/cordis/rfc/0004-descriptor-
   native-types.md, "The boundary codec"): protobuf binary and JSON, both
   ways, read and written from the bound registry. A message's value is the
   one CEL gives it (Proto.make): only set fields stored, the well-known
   types in their CEL forms.

   Reading is strict, for a trust boundary: an unknown field (by number or
   by name), two members of one oneof, an enum number or name the enum does
   not declare, a 32-bit value out of range, a missing proto2 required
   field, a string that is not UTF-8, a timestamp or duration out of range,
   and anything malformed are refused. Extensions are not read (they are
   unknown fields here). Where protobuf's own reading has a rule rather
   than an error it is kept: a repeated singular field's last value wins
   (a message's occurrences merge), packed and unpacked repeated scalars
   are both read.

   Writing is canonical: fields in number order; proto3 repeated scalars
   packed; JSON with proto field names, 64-bit integers as strings, and
   every field with no presence printed (protobuf's
   always_print_fields_with_no_presence). An Any's content is written under
   the type its CEL value has: a message under its own, a timestamp or
   duration under theirs, an int as an Int64Value, a uint as a UInt64Value,
   a double as a DoubleValue, a map as a Struct, a list as a ListValue,
   null as a Value. *)

open Value
module P = Proto

exception Refused of string

let refuse fmt = Printf.ksprintf (fun m -> raise (Refused m)) fmt
let guard f = try Result.Ok (f ()) with Refused m -> Result.Error m | P.Bad_wire m -> Result.Error m

let message_def name = match P.find_message name with Some m -> m | None -> refuse "unknown message type %s" name

let is_message (f : P.field) = f.ty = "message"
let tname (f : P.field) = Option.get f.type_name

let ints32 = [ "int32"; "sint32"; "sfixed32" ]
let uints32 = [ "uint32"; "fixed32" ]
let in_int32 i = Int64.compare i (-2147483648L) >= 0 && Int64.compare i 2147483647L <= 0
let in_uint32 u = Int64.unsigned_compare u 4294967295L <= 0

let enum_values t = match Hashtbl.find_opt (P.enums ()) t with Some vs -> vs | None -> refuse "unknown enum type %s" t

let enum_number (v : Value.t) =
  match v with Int i -> Int64.to_int i | Enum { num; _ } -> num | _ -> refuse "not an enum value: %s" (to_string v)

let enum_value t n =
  if not (List.exists (fun (_, k) -> k = n) (enum_values t)) then refuse "%s has no value numbered %d" t n;
  if P.strong_enums () then Enum { tname = t; num = n } else Int (Int64.of_int n)

(* the well-known types protobuf's JSON gives a special form *)
let json_special t =
  P.wrapper_scalar t <> None
  || List.mem t
       [ "google.protobuf.Timestamp"; "google.protobuf.Duration"; "google.protobuf.FieldMask"; "google.protobuf.Struct";
         "google.protobuf.Value"; "google.protobuf.ListValue"; "google.protobuf.Any" ]

(* map entries in key order: ints and uints by value, strings bytewise,
   false before true; writing is canonical *)
let sorted (m : (Value.t * Value.t) list) =
  let key = function
    | Int i -> `I i
    | Uint u -> `U u
    | Bool b -> `B b
    | String s -> `S s
    | k -> refuse "a map key %s" (to_string k)
  in
  List.sort
    (fun (a, _) (b, _) ->
      match key a, key b with
      | `U x, `U y -> Int64.unsigned_compare x y
      | x, y -> compare x y)
    m

let type_url t = "type.googleapis.com/" ^ t

let url_type url =
  match String.rindex_opt url '/' with Some i -> String.sub url (i + 1) (String.length url - i - 1) | None -> url

(* The type an Any's content is written under, from its CEL value. *)
let any_type (v : Value.t) =
  match v with
  | Message { tname; _ } -> tname
  | Timestamp _ -> "google.protobuf.Timestamp"
  | Duration _ -> "google.protobuf.Duration"
  | Int _ -> "google.protobuf.Int64Value"
  | Uint _ -> "google.protobuf.UInt64Value"
  | Double _ -> "google.protobuf.DoubleValue"
  | String _ -> "google.protobuf.StringValue"
  | Bytes _ -> "google.protobuf.BytesValue"
  | Bool _ -> "google.protobuf.BoolValue"
  | Map _ -> "google.protobuf.Struct"
  | List _ -> "google.protobuf.ListValue"
  | Null -> "google.protobuf.Value"
  | _ -> refuse "an Any cannot hold %s" (to_string v)

(* Fields of a message value, the well-known types' CEL forms taken back to
   their fields. *)
let rec fields_of t (v : Value.t) : (string * Value.t) list =
  let nz k x = if x = 0L then [] else [ (k, Int x) ] in
  match t, v with
  | _, Message { tname; fields } when tname = t -> fields
  | t, _ when P.wrapper_scalar t <> None -> (
      match v with
      | Null -> []
      | _ -> if Value.equal v (P.zero_scalar (Option.get (P.wrapper_scalar t))) && not (is_neg_zero v) then [] else [ ("value", v) ])
  | "google.protobuf.Timestamp", Timestamp (s, n) | "google.protobuf.Duration", Duration (s, n) ->
      nz "seconds" s @ nz "nanos" (Int64.of_int n)
  | "google.protobuf.Struct", Map m -> if m = [] then [] else [ ("fields", v) ]
  | "google.protobuf.ListValue", List xs -> if xs = [||] then [] else [ ("values", v) ]
  | "google.protobuf.Value", _ -> (
      match v with
      | Null -> [ ("null_value", Int 0L) ]
      | Double _ -> [ ("number_value", v) ]
      | String _ -> [ ("string_value", v) ]
      | Bool _ -> [ ("bool_value", v) ]
      | Map _ -> [ ("struct_value", v) ]
      | List _ -> [ ("list_value", v) ]
      | _ -> refuse "a google.protobuf.Value cannot hold %s" (to_string v))
  | "google.protobuf.Any", _ ->
      let inner = any_type v in
      [ ("type_url", String (type_url inner)); ("value", Bytes (encode inner v)) ]
  | _ -> refuse "not a %s: %s" t (to_string v)

and is_neg_zero = function Double d -> d = 0.0 && 1.0 /. d < 0.0 | _ -> false

(* ---- binary: writing ------------------------------------------------------------------- *)

and encode t (v : Value.t) =
  let b = Buffer.create 64 in
  write_message b t v;
  Buffer.contents b

and write_message b t v =
  let m = message_def t in
  let fields = fields_of t v in
  List.iter
    (fun (f : P.field) -> match List.assoc_opt f.name fields with Some x -> write_field b m f x | None -> ())
    (List.sort (fun (a : P.field) c -> compare a.number c.number) m.fields)

and put_varint b (v : int64) =
  let rec go v =
    if Int64.unsigned_compare v 0x80L < 0 then Buffer.add_char b (Char.chr (Int64.to_int v))
    else begin
      Buffer.add_char b (Char.chr (Int64.to_int (Int64.logand v 0x7fL) lor 0x80));
      go (Int64.shift_right_logical v 7)
    end
  in
  go v

and put_fixed b n (v : int64) =
  for k = 0 to n - 1 do Buffer.add_char b (Char.chr (Int64.to_int (Int64.logand (Int64.shift_right_logical v (8 * k)) 0xffL))) done

and put_tag b num wire = put_varint b (Int64.of_int ((num lsl 3) lor wire))

and put_ld b s =
  put_varint b (Int64.of_int (String.length s));
  Buffer.add_string b s

and wire_type (f : P.field) =
  match f.ty with
  | "double" | "fixed64" | "sfixed64" -> 1
  | "float" | "fixed32" | "sfixed32" -> 5
  | "string" | "bytes" | "message" -> 2
  | _ -> 0

(* a scalar's payload, without its tag *)
and put_scalar b (f : P.field) (v : Value.t) =
  let i () = match v with Int i -> i | _ -> refuse "field %s: expected an int, got %s" f.name (to_string v) in
  let u () = match v with Uint u -> u | _ -> refuse "field %s: expected a uint, got %s" f.name (to_string v) in
  let d () = match v with Double d -> d | _ -> refuse "field %s: expected a double, got %s" f.name (to_string v) in
  match f.ty with
  | "int32" | "int64" -> put_varint b (i ())
  | "uint32" | "uint64" -> put_varint b (u ())
  | "enum" -> put_varint b (Int64.of_int (enum_number v))
  | "sint32" | "sint64" ->
      let x = i () in
      put_varint b (Int64.logxor (Int64.shift_left x 1) (Int64.shift_right x 63))
  | "bool" -> put_varint b (match v with Bool true -> 1L | Bool false -> 0L | _ -> refuse "field %s: expected a bool" f.name)
  | "fixed32" -> put_fixed b 4 (u ())
  | "sfixed32" -> put_fixed b 4 (i ())
  | "float" -> put_fixed b 4 (Int64.of_int32 (Int32.bits_of_float (d ())))
  | "fixed64" -> put_fixed b 8 (u ())
  | "sfixed64" -> put_fixed b 8 (i ())
  | "double" -> put_fixed b 8 (Int64.bits_of_float (d ()))
  | "string" -> put_ld b (match v with String s -> s | _ -> refuse "field %s: expected a string" f.name)
  | "bytes" -> put_ld b (match v with Bytes s -> s | _ -> refuse "field %s: expected bytes" f.name)
  | ty -> refuse "field %s: unsupported type %s" f.name ty

(* one element of a field, with its tag *)
and put_element b (f : P.field) v =
  if is_message f then
    if f.group then begin
      put_tag b f.number 3;
      write_message b (tname f) v;
      put_tag b f.number 4
    end
    else begin
      put_tag b f.number 2;
      put_ld b (encode (tname f) v)
    end
  else begin
    put_tag b f.number (wire_type f);
    put_scalar b f v
  end

and write_field b (m : P.message) (f : P.field) v =
  match f.map, v with
  | Some (kf, vf), Map entries ->
      List.iter
        (fun (k, x) ->
          let e = Buffer.create 16 in
          put_element e { kf with number = 1 } k;
          put_element e { vf with number = 2 } x;
          put_tag b f.number 2;
          put_ld b (Buffer.contents e))
        (sorted entries)
  | Some _, _ -> refuse "field %s: expected a map" f.name
  | None, List xs when f.repeated ->
      if m.syntax = "proto3" && wire_type f <> 2 && xs <> [||] then begin
        let body = Buffer.create 16 in
        Array.iter (put_scalar body f) xs;
        put_tag b f.number 2;
        put_ld b (Buffer.contents body)
      end
      else Array.iter (put_element b f) xs
  | None, _ when f.repeated -> refuse "field %s: expected a list" f.name
  | None, _ -> put_element b f v

(* ---- binary: reading ------------------------------------------------------------------- *)

let utf8 what s = if String.is_valid_utf_8 s then s else refuse "%s: not UTF-8" what

(* A scalar from the wire, checked. *)
let read_scalar (f : P.field) wire s pos =
  let expect w = if wire <> w then refuse "field %s: wire type %d, expected %d" f.name wire w in
  match f.ty with
  | "int32" ->
      expect 0;
      let v = P.varint s pos in
      if not (in_int32 v) then refuse "field %s: %Ld is out of range for int32" f.name v;
      Int v
  | "int64" -> expect 0; Int (P.varint s pos)
  | "uint32" ->
      expect 0;
      let v = P.varint s pos in
      if not (in_uint32 v) then refuse "field %s: %Lu is out of range for uint32" f.name v;
      Uint v
  | "uint64" -> expect 0; Uint (P.varint s pos)
  | "sint32" ->
      expect 0;
      let raw = P.varint s pos in
      if not (in_uint32 raw) then refuse "field %s: out of range for sint32" f.name;
      Int (P.zigzag raw)
  | "sint64" -> expect 0; Int (P.zigzag (P.varint s pos))
  | "bool" -> expect 0; Bool (P.varint s pos <> 0L)
  | "enum" ->
      expect 0;
      let v = P.varint s pos in
      if not (in_int32 v) then refuse "field %s: enum number %Ld is out of range" f.name v;
      enum_value (tname f) (Int64.to_int v)
  | "fixed32" -> expect 5; Uint (P.fixed s pos 4)
  | "sfixed32" -> expect 5; Int (Int64.of_int32 (Int64.to_int32 (P.fixed s pos 4)))
  | "float" -> expect 5; Double (Int32.float_of_bits (Int64.to_int32 (P.fixed s pos 4)))
  | "fixed64" -> expect 1; Uint (P.fixed s pos 8)
  | "sfixed64" -> expect 1; Int (P.fixed s pos 8)
  | "double" -> expect 1; Double (Int64.float_of_bits (P.fixed s pos 8))
  | "string" -> expect 2; String (utf8 ("field " ^ f.name) (P.wire_bytes s pos))
  | "bytes" -> expect 2; Bytes (P.wire_bytes s pos)
  | ty -> refuse "field %s: unsupported type %s" f.name ty

(* Skip a group's body, up to its end tag; return the body. *)
let group_body s pos num =
  let start = !pos in
  let rec skip depth =
    if !pos >= String.length s then refuse "an unterminated group";
    let here = !pos in
    let key = Int64.to_int (P.varint s pos) in
    let n = key lsr 3 and w = key land 7 in
    match w with
    | 0 -> ignore (P.varint s pos); skip depth
    | 1 -> ignore (P.fixed s pos 8); skip depth
    | 2 -> ignore (P.wire_bytes s pos); skip depth
    | 5 -> ignore (P.fixed s pos 4); skip depth
    | 3 -> skip (n :: depth)
    | 4 -> (
        match depth with
        | [] -> if n = num then String.sub s start (here - start) else refuse "a group ended by the wrong tag"
        | d :: rest -> if d = n then skip rest else refuse "a group ended by the wrong tag")
    | _ -> refuse "wire type %d" w
  in
  skip []

let rec decode t s = decode_message (message_def t) s

and decode_message (m : P.message) s =
  let t = m.full_name in
  let pos = ref 0 in
  (* per field, in arrival order: elements (repeated, map entries), or the
     raw occurrences of a singular field (messages merge, scalars: last wins) *)
  let seen : (string, Value.t list) Hashtbl.t = Hashtbl.create 8 in
  let raw : (string, Buffer.t) Hashtbl.t = Hashtbl.create 4 in
  let oneof_member : (string, string) Hashtbl.t = Hashtbl.create 2 in
  let add name v = Hashtbl.replace seen name (v :: Option.value (Hashtbl.find_opt seen name) ~default:[]) in
  while !pos < String.length s do
    let key = P.varint s pos in
    let num = Int64.to_int (Int64.shift_right_logical key 3) and wire = Int64.to_int (Int64.logand key 7L) in
    match List.find_opt (fun (f : P.field) -> f.number = num) m.fields with
    | None -> refuse "%s has no field numbered %d" t num
    | Some f ->
        Option.iter
          (fun o ->
            match Hashtbl.find_opt oneof_member o with
            | Some other when other <> f.name -> refuse "two members of oneof %s: %s and %s" o other f.name
            | _ -> Hashtbl.replace oneof_member o f.name)
          f.oneof;
        (match f.map with
         | Some (kf, vf) ->
             if wire <> 2 then refuse "field %s: wire type %d, expected 2" f.name wire;
             let entry = { P.full_name = t ^ "." ^ f.name ^ "Entry"; syntax = "proto3";
                           fields = [ { kf with name = "key"; number = 1; presence = false; oneof = None };
                                      { vf with name = "value"; number = 2; presence = false; oneof = None } ] } in
             let es = decode_message entry (P.wire_bytes s pos) in
             let k = match List.assoc_opt "key" es with Some k -> k | None -> P.default_value kf in
             let v = match List.assoc_opt "value" es with Some v -> v | None -> P.default_value vf in
             add f.name (List [| k; v |])
         | None when is_message f ->
             let body =
               if f.group then (if wire <> 3 then refuse "field %s: wire type %d, expected 3" f.name wire; group_body s pos f.number)
               else (if wire <> 2 then refuse "field %s: wire type %d, expected 2" f.name wire; P.wire_bytes s pos)
             in
             if f.repeated then add f.name (decode_value (tname f) body)
             else begin
               let buf = match Hashtbl.find_opt raw f.name with Some b -> b | None -> let b = Buffer.create 16 in Hashtbl.replace raw f.name b; b in
               Buffer.add_string buf body
             end
         | None when f.repeated && wire = 2 && wire_type f <> 2 ->
             (* packed *)
             let body = P.wire_bytes s pos in
             let p = ref 0 in
             while !p < String.length body do add f.name (read_scalar f (wire_type f) body p) done
         | None -> add f.name (read_scalar f wire s pos))
  done;
  let assigns =
    List.filter_map
      (fun (f : P.field) ->
        match Hashtbl.find_opt raw f.name with
        | Some b -> Some (f.name, decode_value (tname f) (Buffer.contents b))
        | None -> (
            match Hashtbl.find_opt seen f.name with
            | None -> if f.required then refuse "%s: required field %s is missing" t f.name else None
            | Some vs ->
                let vs = List.rev vs in
                if f.map <> None then Some (f.name, Value.make_map (List.map (function List [| k; v |] -> (k, v) | _ -> (Null, Null)) vs))
                else if f.repeated then Some (f.name, List (Array.of_list vs))
                else Some (f.name, List.nth vs (List.length vs - 1))))
      m.fields
  in
  assigns

(* A message's CEL value from its bytes. *)
and decode_value t s =
  let fields = decode t s in
  build t fields

(* The CEL value of type t with the given fields, as Proto.make builds it;
   the well-known types become their CEL forms. *)
and build t fields : Value.t =
  match t with
  | "google.protobuf.Any" -> (
      match List.assoc_opt "type_url" fields with
      | Some (String url) ->
          let inner = url_type url in
          let body = match List.assoc_opt "value" fields with Some (Bytes b) -> b | _ -> "" in
          decode_value inner body
      | _ -> refuse "an Any without a type URL")
  | "google.protobuf.Value" when fields = [] -> Null
  | "google.protobuf.Value" -> (
      match fields with
      | [ ("null_value", _) ] -> Null
      | [ (_, v) ] -> v
      | _ -> refuse "a google.protobuf.Value with two kinds")
  | "google.protobuf.Timestamp" | "google.protobuf.Duration" -> (
      let get k = match List.assoc_opt k fields with Some (Int x) -> x | _ -> 0L in
      let s = get "seconds" and n = Int64.to_int (get "nanos") in
      if t = "google.protobuf.Timestamp" then begin
        if n < 0 || n > 999_999_999 then refuse "a Timestamp's nanos out of range";
        match Ctime.check_ts (s, n) with Result.Ok (s, n) -> Timestamp (s, n) | Result.Error _ -> refuse "a Timestamp out of range"
      end
      else begin
        if abs n > 999_999_999 || (s > 0L && n < 0) || (s < 0L && n > 0) then refuse "a Duration's nanos out of range";
        match Ctime.check_dur (s, n) with Result.Ok (s, n) -> Duration (s, n) | Result.Error _ -> refuse "a Duration out of range"
      end)
  | _ -> (
      match P.make t fields with
      | Error e -> refuse "%s: %s" t e
      | v -> v)

let decode t s = guard (fun () -> decode_value t s)
let encode t v = guard (fun () -> encode t v)

(* ---- JSON: writing --------------------------------------------------------------------- *)

let camel path =
  let b = Buffer.create (String.length path) in
  let up = ref false in
  String.iter
    (fun c ->
      if c = '_' then up := true
      else begin
        Buffer.add_char b (if !up then Char.uppercase_ascii c else c);
        up := false
      end)
    path;
  Buffer.contents b

let snake path =
  let b = Buffer.create (String.length path + 4) in
  String.iter
    (fun c ->
      if c = '_' then refuse "a FieldMask path in JSON has an underscore: %s" path
      else if Char.uppercase_ascii c = c && Char.lowercase_ascii c <> c then (Buffer.add_char b '_'; Buffer.add_char b (Char.lowercase_ascii c))
      else Buffer.add_char b c)
    path;
  Buffer.contents b

let float_json what d : Yojson.Safe.t =
  if Float.is_nan d then `String "NaN"
  else if d = Float.infinity then `String "Infinity"
  else if d = Float.neg_infinity then `String "-Infinity"
  else (ignore what; `Float d)

let rec scalar_json (f : P.field) (v : Value.t) : Yojson.Safe.t =
  match f.ty, v with
  | ("int32" | "sint32" | "sfixed32"), Int i -> `Int (Int64.to_int i)
  | ("int64" | "sint64" | "sfixed64"), Int i -> `String (Int64.to_string i)
  | ("uint32" | "fixed32"), Uint u -> `Int (Int64.to_int u)
  | ("uint64" | "fixed64"), Uint u -> `String (Printf.sprintf "%Lu" u)
  | ("double" | "float"), Double d -> float_json f.name d
  | "bool", Bool b -> `Bool b
  | "string", String s -> `String s
  | "bytes", Bytes s -> `String (Ext_encoders.encode s)
  | "enum", _ ->
      let n = enum_number v in
      let t = tname f in
      if t = "google.protobuf.NullValue" then `Null
      else (match List.find_opt (fun (_, k) -> k = n) (enum_values t) with Some (name, _) -> `String name | None -> `Int n)
  | "message", _ -> to_json (tname f) v
  | ty, _ -> refuse "field %s: %s is not a %s" f.name (to_string v) ty

and key_json (f : P.field) (k : Value.t) =
  match scalar_json f k with
  | `String s -> s
  | `Int i -> string_of_int i
  | `Bool b -> string_of_bool b
  | _ -> refuse "a map key of type %s" f.ty

and field_json (f : P.field) (v : Value.t) : Yojson.Safe.t =
  match f.map, v with
  | Some (kf, vf), Map m -> `Assoc (List.map (fun (k, x) -> (key_json kf k, scalar_json vf x)) (sorted m))
  | None, List xs when f.repeated -> `List (Array.to_list (Array.map (scalar_json f) xs))
  | _ -> scalar_json f v

and json_value (v : Value.t) : Yojson.Safe.t =
  match v with
  | Null -> `Null
  | Bool b -> `Bool b
  | Double d -> if not (Float.is_finite d) then refuse "a google.protobuf.Value cannot hold %s" (to_string v) else `Float d
  | String s -> `String s
  | List xs -> `List (Array.to_list (Array.map json_value xs))
  | Map m -> `Assoc (List.map (function String k, x -> (k, json_value x) | k, _ -> refuse "a Struct key %s" (to_string k)) (sorted m))
  | _ -> refuse "a google.protobuf.Value cannot hold %s" (to_string v)

and to_json t (v : Value.t) : Yojson.Safe.t =
  match t, v with
  | t, _ when P.wrapper_scalar t <> None ->
      let s = Option.get (P.wrapper_scalar t) in
      scalar_json { (List.hd (message_def t).fields) with ty = s } v
  | "google.protobuf.Timestamp", Timestamp (s, n) -> `String (Ctime.format_timestamp (s, n))
  | "google.protobuf.Duration", Duration (s, n) -> `String (Ctime.format_duration (s, n))
  | "google.protobuf.Struct", Map _ | "google.protobuf.ListValue", List _ | "google.protobuf.Value", _ -> json_value v
  | "google.protobuf.FieldMask", Message { fields; _ } ->
      let paths = match List.assoc_opt "paths" fields with Some (List ps) -> Array.to_list ps | _ -> [] in
      `String
        (String.concat ","
           (List.map
              (function
                | String p ->
                    let c = camel p in
                    if snake c <> p then refuse "a FieldMask path that has no JSON form: %s" p;
                    c
                | x -> refuse "a FieldMask path %s" (to_string x))
              paths))
  | "google.protobuf.Any", _ ->
      let inner = any_type v in
      let at = ("@type", `String (type_url inner)) in
      if json_special inner then `Assoc [ at; ("value", to_json inner v) ]
      else (match to_json inner v with `Assoc kv -> `Assoc (at :: kv) | _ -> refuse "an Any's content")
  | _, Message { tname; fields } when tname = t ->
      let m = message_def t in
      `Assoc
        (List.filter_map
           (fun (f : P.field) ->
             match List.assoc_opt f.name fields with
             | Some x -> Some (f.name, field_json f x)
             | None when (not f.presence) && f.oneof = None -> Some (f.name, field_json f (P.default_value f))
             | None -> None)
           (List.sort (fun (a : P.field) c -> compare a.number c.number) m.fields))
  | _ -> refuse "not a %s: %s" t (to_string v)

(* ---- JSON: reading --------------------------------------------------------------------- *)

let int_of_json what lo hi (j : Yojson.Safe.t) =
  let of_string s =
    match Int64.of_string_opt s with
    | Some i when not (String.contains s '_' || String.contains s 'x' || String.contains s 'o' || String.contains s 'b') -> Some i
    | _ -> (
        match float_of_string_opt s with
        | Some f when Float.is_integer f && Float.abs f < 9.2e18 && String.trim s = s -> Some (Int64.of_float f)
        | _ -> None)
  in
  let i =
    match j with
    | `Int i -> Int64.of_int i
    | `Intlit s -> (match Int64.of_string_opt s with Some i -> i | None -> refuse "%s: %s is out of range" what s)
    | `Float f when Float.is_integer f && Float.abs f < 9.2e18 -> Int64.of_float f
    | `String s -> (match of_string s with Some i -> i | None -> refuse "%s: not an integer: %S" what s)
    | _ -> refuse "%s: not an integer: %s" what (Yojson.Safe.to_string j)
  in
  if Int64.compare i lo < 0 || Int64.compare i hi > 0 then refuse "%s: %Ld is out of range" what i;
  i

let uint_of_json what max (j : Yojson.Safe.t) =
  let s =
    match j with
    | `Int i -> if i < 0 then refuse "%s: %d is out of range" what i else string_of_int i
    | `Intlit s | `String s -> s
    | `Float f when Float.is_integer f && f >= 0.0 && f < 1.8e19 -> Printf.sprintf "%.0f" f
    | _ -> refuse "%s: not an unsigned integer: %s" what (Yojson.Safe.to_string j)
  in
  if s = "" || not (String.for_all (fun c -> c >= '0' && c <= '9') s) then refuse "%s: not an unsigned integer: %S" what s;
  match Int64.of_string_opt ("0u" ^ s) with
  | Some u when Int64.unsigned_compare u max <= 0 -> u
  | _ -> refuse "%s: %s is out of range" what s

let float_of_json what single (j : Yojson.Safe.t) =
  let d =
    match j with
    | `Float f -> f
    | `Int i -> float_of_int i
    | `Intlit s -> float_of_string s
    | `String "NaN" -> Float.nan
    | `String "Infinity" -> Float.infinity
    | `String "-Infinity" -> Float.neg_infinity
    | `String s -> (
        match float_of_string_opt s with
        | Some f when s <> "" && String.trim s = s && Float.is_finite f -> f
        | _ -> refuse "%s: not a number: %S" what s)
    | _ -> refuse "%s: not a number: %s" what (Yojson.Safe.to_string j)
  in
  (* out of range: a finite double that rounds to an infinite float *)
  if single && Float.is_finite d && not (Float.is_finite (P.to_float32 d)) then refuse "%s: %g is out of range for float" what d;
  if single then P.to_float32 d else d

let bytes_of_json what (j : Yojson.Safe.t) =
  match j with
  | `String s ->
      let s = String.map (function '-' -> '+' | '_' -> '/' | c -> c) s in
      (match Ext_encoders.decode s with Result.Ok b -> b | Result.Error e -> refuse "%s: %s" what e)
  | _ -> refuse "%s: bytes must be a base64 string" what

let rec scalar_of_json (f : P.field) (j : Yojson.Safe.t) : Value.t =
  let what = "field " ^ f.name in
  match f.ty with
  | "int32" | "sint32" | "sfixed32" -> Int (int_of_json what (-2147483648L) 2147483647L j)
  | "int64" | "sint64" | "sfixed64" -> Int (int_of_json what Int64.min_int Int64.max_int j)
  | "uint32" | "fixed32" -> Uint (uint_of_json what 4294967295L j)
  | "uint64" | "fixed64" -> Uint (uint_of_json what (-1L) j)
  | "double" -> Double (float_of_json what false j)
  | "float" -> Double (float_of_json what true j)
  | "bool" -> (match j with `Bool b -> Bool b | _ -> refuse "%s: not a bool" what)
  | "string" -> (match j with `String s -> String (utf8 what s) | _ -> refuse "%s: not a string" what)
  | "bytes" -> Bytes (bytes_of_json what j)
  | "enum" -> (
      let t = tname f in
      match j with
      | `Null when t = "google.protobuf.NullValue" -> enum_value t 0
      | `String name -> (
          match List.assoc_opt name (enum_values t) with Some n -> enum_value t n | None -> refuse "%s: %s has no value %s" what t name)
      | `Int n -> enum_value t n
      | _ -> refuse "%s: not an enum value" what)
  | "message" -> of_json (tname f) j
  | ty -> refuse "%s: unsupported type %s" what ty

and key_of_json (f : P.field) (k : string) : Value.t =
  match f.ty with
  | "bool" -> (match k with "true" -> Bool true | "false" -> Bool false | _ -> refuse "a bool map key %S" k)
  | "string" -> String k
  | _ -> scalar_of_json f (`String k)

and json_to_value (j : Yojson.Safe.t) : Value.t =
  match j with
  | `Null -> Null
  | `Bool b -> Bool b
  | `Int i -> Double (float_of_int i)
  | `Intlit s -> Double (float_of_string s)
  | `Float f -> Double f
  | `String s -> String (utf8 "a Value" s)
  | `List xs -> List (Array.of_list (List.map json_to_value xs))
  | `Assoc kv -> obj (List.map (fun (k, x) -> (k, json_to_value x)) kv)

and obj kv =
  let rec dup = function [] -> () | (k, _) :: rest -> if List.mem_assoc k rest then refuse "duplicate key %S" k else dup rest in
  dup kv;
  Value.make_map (List.map (fun (k, v) -> (String k, v)) kv)

and of_json t (j : Yojson.Safe.t) : Value.t =
  match t, j with
  | t, _ when P.wrapper_scalar t <> None ->
      if j = `Null then refuse "%s: null" t;
      scalar_of_json { (List.hd (message_def t).fields) with ty = Option.get (P.wrapper_scalar t) } j
  | "google.protobuf.Timestamp", `String s -> (
      (match String.index_opt s '.' with
       | Some i ->
           let k = ref (i + 1) in
           while !k < String.length s && s.[!k] >= '0' && s.[!k] <= '9' do incr k done;
           if !k - i - 1 = 0 || !k - i - 1 > 9 then refuse "a Timestamp's fraction: %S" s
       | None -> ());
      match Ctime.parse_timestamp s with Result.Ok (s, n) -> Timestamp (s, n) | Result.Error _ -> refuse "not a Timestamp: %S" s)
  | "google.protobuf.Duration", `String s -> duration_of_json s
  | "google.protobuf.Value", _ -> json_to_value j
  | "google.protobuf.Struct", `Assoc _ -> json_to_value j
  | "google.protobuf.ListValue", `List _ -> json_to_value j
  | "google.protobuf.FieldMask", `String s ->
      let paths = if s = "" then [] else List.map (fun p -> String (snake p)) (String.split_on_char ',' s) in
      build t (if paths = [] then [] else [ ("paths", List (Array.of_list paths)) ])
  | "google.protobuf.Any", `Assoc kv -> (
      match List.assoc_opt "@type" kv with
      | Some (`String url) ->
          let inner = url_type url in
          ignore (message_def inner);
          if json_special inner then
            match List.remove_assoc "@type" kv with
            | [ ("value", x) ] -> of_json inner x
            | _ -> refuse "an Any holding %s must have exactly a \"value\"" inner
          else of_json inner (`Assoc (List.remove_assoc "@type" kv))
      | _ -> refuse "an Any without @type")
  | _, `Assoc kv ->
      let m = message_def t in
      let taken = Hashtbl.create 8 and oneof_member = Hashtbl.create 2 in
      let assigns =
        List.filter_map
          (fun (k, x) ->
            match List.find_opt (fun (f : P.field) -> f.name = k || f.json_name = k) m.fields with
            | None -> refuse "%s has no field %s" t k
            | Some f ->
                if Hashtbl.mem taken f.name then refuse "%s: field %s given twice" t f.name;
                Hashtbl.replace taken f.name ();
                (* null is absent, except where null is a value *)
                let null_is_value = (not f.repeated) && f.map = None && (f.type_name = Some "google.protobuf.Value" || f.type_name = Some "google.protobuf.NullValue") in
                if x = `Null && not null_is_value then None
                else begin
                  Option.iter
                    (fun o ->
                      match Hashtbl.find_opt oneof_member o with
                      | Some other -> refuse "two members of oneof %s: %s and %s" o other f.name
                      | None -> Hashtbl.replace oneof_member o f.name)
                    f.oneof;
                  let v =
                    match f.map, x with
                    | Some (kf, vf), `Assoc es ->
                        let rec dup = function [] -> () | (k, _) :: rest -> if List.mem_assoc k rest then refuse "field %s: duplicate key %S" f.name k else dup rest in
                        dup es;
                        Value.make_map (List.map (fun (k, e) -> (key_of_json kf k, element_of_json vf e)) es)
                    | Some _, _ -> refuse "field %s: not an object" f.name
                    | None, `List xs when f.repeated -> List (Array.of_list (List.map (element_of_json f) xs))
                    | None, _ when f.repeated -> refuse "field %s: not a list" f.name
                    | None, _ -> scalar_of_json f x
                  in
                  Some (f.name, v)
                end)
          kv
      in
      List.iter
        (fun (f : P.field) -> if f.required && not (List.mem_assoc f.name assigns) then refuse "%s: required field %s is missing" t f.name)
        m.fields;
      build t assigns
  | _ -> refuse "not a %s: %s" t (Yojson.Safe.to_string j)

and element_of_json (f : P.field) (j : Yojson.Safe.t) =
  if j = `Null && f.type_name <> Some "google.protobuf.Value" && f.type_name <> Some "google.protobuf.NullValue" then
    refuse "field %s: null element" f.name;
  scalar_of_json f j

(* protobuf's Duration JSON: [-]seconds[.fraction]s, at most 9 fraction digits *)
and duration_of_json s =
  let n = String.length s in
  let bad () = refuse "not a Duration: %S" s in
  if n < 2 || s.[n - 1] <> 's' then bad ();
  let body = String.sub s 0 (n - 1) in
  let neg = String.length body > 0 && body.[0] = '-' in
  let body = if neg then String.sub body 1 (String.length body - 1) else body in
  let ip, fp = match String.index_opt body '.' with Some i -> (String.sub body 0 i, String.sub body (i + 1) (String.length body - i - 1)) | None -> (body, "") in
  let digits x = x <> "" && String.for_all (fun c -> c >= '0' && c <= '9') x in
  if not (digits ip) || (String.contains body '.' && not (digits fp)) || String.length fp > 9 then bad ();
  let secs = match Int64.of_string_opt ip with Some x -> x | None -> bad () in
  let nanos = if fp = "" then 0 else int_of_string (fp ^ String.make (9 - String.length fp) '0') in
  let secs, nanos = if neg then (Int64.neg secs, -nanos) else (secs, nanos) in
  match Ctime.check_dur (secs, nanos) with Result.Ok (s, n) -> Duration (s, n) | Result.Error _ -> refuse "a Duration out of range: %S" s

let to_json t v = guard (fun () -> to_json t v)
let of_json t j = guard (fun () -> of_json t j)
let to_json_string t v = Result.map Yojson.Safe.to_string (to_json t v)

let of_json_string t s =
  match Yojson.Safe.from_string s with
  | j -> of_json t j
  | exception Yojson.Json_error m -> Result.Error ("not JSON: " ^ m)
