(* Protobuf messages generated from their descriptors, in the bound registry
   (docs/cordis/rfc/0004-descriptor-native-types.md, "Testing"): each field
   set or not, at most one member per oneof, required fields always set,
   values at their types' boundaries in a fair share of cases, the well-
   known types in their CEL forms, recursion to a given depth. Every value
   is one protobuf can hold, so the codec's agreement with protobuf and the
   graph checker's soundness are tested over the same messages.

   Not generated: NaN (it equals nothing, so a round trip cannot be
   compared), and extensions. *)

open Typed_gen
module V = Cordis_cel.Value
module P = Cordis_cel.Proto

(* NullValue elements of repeated and map fields. protobuf's Python JSON
   reader refuses the null it prints for them (protobuf 7.36), so the
   agreement test turns them off; the codec's examples cover them. *)
let null_value_elements = ref true

let int32 d =
  if d.pick 4 = 0 then choose d [ 2147483647L; -2147483648L; 0L; -1L ] else Int64.of_int (d.pick 2001 - 1000)

let int64 d =
  if d.pick 3 = 0 then choose d [ Int64.max_int; Int64.min_int; 9007199254740993L; -1L; 0L ]
  else Int64.of_int (d.pick 2001 - 1000)

let uint32 d = if d.pick 4 = 0 then choose d [ 4294967295L; 0L ] else Int64.of_int (d.pick 2001)
let uint64 d = if d.pick 3 = 0 then choose d [ -1L (* 2^64 - 1 *); Int64.min_int (* 2^63 *); 0L ] else Int64.of_int (d.pick 2001)

let double d =
  if d.pick 4 = 0 then choose d [ Float.infinity; Float.neg_infinity; 1e300; 5e-324; -0.0; 0.1 ]
  else float_of_int (d.pick 2001 - 1000) /. 16.

let float32 d =
  P.to_float32 (if d.pick 4 = 0 then choose d [ Float.infinity; Float.neg_infinity; 3.4028234663852886e38; 1e-45; 0.1 ] else double d)

let string d = choose d [ ""; "a"; "héllo"; "tab\there"; "quote\"d"; "back\\slash"; "nul\000"; "\xf0\x9f\x98\x80"; "LGTM ok" ]
let bytes d = String.init (d.pick 6) (fun _ -> Char.chr (d.pick 256))

let timestamp d =
  let s = if d.pick 4 = 0 then choose d [ -62135596800L; 253402300799L; 0L ] else Int64.of_int (1700000000 + d.pick 100000000) in
  V.Timestamp (s, if d.pick 2 = 0 then 0 else choose d [ 1; 500_000_000; 999_999_999; 123_000 ])

let duration d =
  let s = if d.pick 4 = 0 then choose d [ 315576000000L; -315576000000L; 0L ] else Int64.of_int (d.pick 20001 - 10000) in
  let n = if d.pick 2 = 0 then 0 else choose d [ 1; 500_000_000; 999_999_999 ] in
  V.Duration (s, if s < 0L then -n else n)

(* google.protobuf.Value's CEL forms: JSON *)
let rec json d depth : V.t =
  match d.pick (if depth <= 0 then 4 else 6) with
  | 0 -> V.Null
  | 1 -> V.Bool (d.pick 2 = 0)
  | 2 -> V.Double (float_of_int (d.pick 2001 - 1000) /. 8.)
  | 3 -> V.String (string d)
  | 4 -> V.List (Array.init (d.pick 3) (fun _ -> json d (depth - 1)))
  | _ -> struct_ d (depth - 1)

and struct_ d depth = V.make_map (List.sort_uniq compare (List.init (d.pick 3) (fun _ -> V.String (choose d [ "a"; "b"; "k"; "héllo" ]))) |> List.map (fun k -> (k, json d depth)))

let field_mask d =
  let path () = String.concat "." (List.init (1 + d.pick 2) (fun _ -> choose d [ "a"; "foo_bar"; "x_y_z"; "name" ])) in
  P.make "google.protobuf.FieldMask" [ ("paths", V.List (Array.init (d.pick 3) (fun _ -> V.String (path ())))) ]

let wrapper d t : V.t =
  match P.wrapper_scalar t with
  | Some "int32" -> V.Int (int32 d)
  | Some "int64" -> V.Int (int64 d)
  | Some "uint32" -> V.Uint (uint32 d)
  | Some "uint64" -> V.Uint (uint64 d)
  | Some "float" -> V.Double (float32 d)
  | Some "double" -> V.Double (double d)
  | Some "bool" -> V.Bool (d.pick 2 = 0)
  | Some "bytes" -> V.Bytes (bytes d)
  | _ -> V.String (string d)

(* A message of [name], or None where the depth runs out. [root] is the
   type an Any may hold besides the well-known ones. *)
let rec message ?(depth = 3) d name : V.t option =
  match name, P.find_message name with
  | _, None -> None
  (* the well-known types, in their CEL forms (an Any's content is chosen
     where its field is) *)
  | "google.protobuf.Timestamp", _ -> Some (timestamp d)
  | "google.protobuf.Duration", _ -> Some (duration d)
  | t, _ when P.wrapper_scalar t <> None -> Some (wrapper d t)
  | "google.protobuf.Struct", _ -> Some (struct_ d 2)
  | "google.protobuf.Value", _ -> Some (json d 2)
  | "google.protobuf.ListValue", _ -> Some (V.List (Array.init (d.pick 3) (fun _ -> json d 1)))
  | "google.protobuf.FieldMask", _ -> Some (field_mask d)
  | "google.protobuf.Any", _ -> None
  | _, Some m -> message_of ~depth d name m

and message_of ~depth d name (m : P.message) : V.t option =
  match () with
  | () when depth < 0 -> if List.exists (fun (f : P.field) -> f.required) m.fields then None else Some (P.make name [])
  | () ->
      (* how full this message is: nothing, some fields, or every one *)
      let density = d.pick 4 in
      let keep () = match density with 0 -> false | 3 -> true | k -> d.pick 3 < k in
      let chosen = Hashtbl.create 4 in
      let rec element ?(collection = false) (f : P.field) : V.t option =
        match f.ty, f.type_name with
        | ("int32" | "sint32" | "sfixed32"), _ -> Some (V.Int (int32 d))
        | ("int64" | "sint64" | "sfixed64"), _ -> Some (V.Int (int64 d))
        | ("uint32" | "fixed32"), _ -> Some (V.Uint (uint32 d))
        | ("uint64" | "fixed64"), _ -> Some (V.Uint (uint64 d))
        | "double", _ -> Some (V.Double (double d))
        | "float", _ -> Some (V.Double (float32 d))
        | "bool", _ -> Some (V.Bool (d.pick 2 = 0))
        | "string", _ -> Some (V.String (string d))
        | "bytes", _ -> Some (V.Bytes (bytes d))
        | "enum", Some "google.protobuf.NullValue" when collection && not !null_value_elements -> None
        | "enum", Some t -> (
            match Hashtbl.find_opt (P.enums ()) t with
            | Some vs -> Some (V.Int (Int64.of_int (snd (choose d vs))))
            | None -> None)
        | "message", Some "google.protobuf.Any" -> any f
        | "message", Some t -> message ~depth:(depth - 1) d t
        | _ -> None
      and any _ : V.t option =
        match d.pick 9 with
        | 0 -> Some (V.Int (int64 d))
        | 1 -> Some (V.Uint (uint64 d))
        | 2 -> Some (V.String (string d))
        | 3 -> Some (V.Bytes (bytes d))
        | 4 -> Some (timestamp d)
        | 5 -> Some (duration d)
        | 6 -> Some (struct_ d 1)
        | 7 -> Some (field_mask d)
        | _ -> message ~depth:(depth - 2) d name
      in
      let assigns =
        List.filter_map
          (fun (f : P.field) ->
            let taken = match f.oneof with Some o -> Hashtbl.mem chosen o | None -> false in
            if taken || not (f.required || keep ()) then None
            else
              let v =
                match f.map with
                | Some (k, vf) ->
                    let keys = List.sort_uniq compare (List.filter_map (fun _ -> element k) (List.init (d.pick 4) Fun.id)) in
                    Some (V.make_map (List.filter_map (fun key -> Option.map (fun v -> (key, v)) (element ~collection:true vf)) keys))
                | None ->
                    if f.repeated then
                      (* null elements of message types would be pruned *)
                      Some (V.List (Array.of_list (List.filter_map (fun _ -> element ~collection:true f) (List.init (d.pick 4) Fun.id))))
                    else element f
              in
              match v with
              | None -> None
              | Some v ->
                  Option.iter (fun o -> Hashtbl.replace chosen o ()) f.oneof;
                  Some (f.name, v))
          m.fields
      in
      if List.exists (fun (f : P.field) -> f.required && not (List.mem_assoc f.name assigns)) m.fields then None
      else
        match P.make name assigns with
        | V.Error e -> failwith ("generating " ^ name ^ ": " ^ e)
        | v -> Some v

(* Structural equality: the same constructors, doubles bit for bit, maps
   in any order. CEL's equality would let 1 equal 1.0. *)
let rec same (a : V.t) (b : V.t) =
  match a, b with
  | V.Double x, V.Double y -> Int64.bits_of_float x = Int64.bits_of_float y
  | V.List xs, V.List ys -> Array.length xs = Array.length ys && Array.for_all2 same xs ys
  | V.Map xs, V.Map ys ->
      List.length xs = List.length ys && List.for_all (fun (k, v) -> List.exists (fun (k', v') -> same k k' && same v v') ys) xs
  | V.Message x, V.Message y ->
      x.tname = y.tname && List.length x.fields = List.length y.fields
      && List.for_all2 (fun (n, v) (n', v') -> n = n' && same v v') x.fields y.fields
  | V.Int x, V.Int y | V.Uint x, V.Uint y -> x = y
  | V.Enum x, V.Enum y -> x.tname = y.tname && x.num = y.num
  | _ -> a = b
