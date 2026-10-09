(* The boundary codec for imported protobuf types (docs/cordis/rfc/0003-
   types-importers-scaffolds.md, "The boundary codec"): values as the graph
   language holds them (records and variants as maps, option fields as
   CEL optionals) to protobuf's JSON form and back.

   Reading fills what protobuf JSON may leave out (an implicit-presence
   field's default, an empty list or map, an absent option) so a record
   always has every field, and refuses what the types refuse: an unknown
   member, two members of one oneof, an unknown enum name or number. *)

open Protobuf
module V = Cordis_cel.Value
module J = Yojson.Safe

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt

let cel fn args = Cordis_cel.Eval.call_function fn args

(* ---- to JSON ----------------------------------------------------------------------------------- *)

let rec dyn_json (v : V.t) : J.t =
  match v with
  | V.Null -> `Null
  | V.Bool b -> `Bool b
  | V.Int i -> `Float (Int64.to_float i)
  | V.Uint u -> `Float (Int64.to_float u)
  | V.Double f -> `Float f
  | V.String s -> `String s
  | V.List xs -> `List (Array.to_list (Array.map dyn_json xs))
  | V.Map m -> `Assoc (List.map (fun (k, x) -> ((match k with V.String s -> s | k -> V.to_string k), dyn_json x)) m)
  | v -> bad "not a JSON value: %s" (V.to_string v)

let key_text (k : V.t) =
  match k with
  | V.String s -> s
  | V.Int i -> Int64.to_string i
  | V.Uint u -> Printf.sprintf "%Lu" u
  | V.Bool b -> string_of_bool b
  | k -> bad "a map key %s" (V.to_string k)

let rec to_json (imp : import) (t : t) (v : V.t) : J.t =
  let tj = to_json imp in
  match t, v with
  | (I32 | U32), V.Int i -> `Int (Int64.to_int i)
  | U32, V.Uint u -> `Int (Int64.to_int u)
  | I64, V.Int i -> `String (Int64.to_string i)
  | U64, V.Uint u -> `String (Printf.sprintf "%Lu" u)
  | (F32 | F64), V.Double f ->
      if Float.is_nan f then `String "NaN"
      else if f = Float.infinity then `String "Infinity"
      else if f = Float.neg_infinity then `String "-Infinity"
      else `Float f
  | Bool, V.Bool b -> `Bool b
  | Str, V.String s -> `String s
  | Bytes, V.Bytes b -> `String (Cordis_cel.Ext_encoders.encode b)
  | (Ts | Dur), v -> (match cel "string" [ v ] with V.String s -> `String s | e -> bad "%s" (V.to_string e))
  | (Dyn | Struct | ListV | Rec _), v -> dyn_json v
  | Empty, _ -> `Assoc []
  | (Wrap t | Opt t), V.Optional (Some x) -> tj t x
  | (Wrap _ | Opt _), V.Optional None -> `Null
  | Lst t, V.List xs -> `List (Array.to_list (Array.map (tj t) xs))
  | Mp (_, vt), V.Map m -> `Assoc (List.map (fun (k, x) -> (key_text k, tj vt x)) m)
  | Named n, V.Map m -> (
      let field name = V.map_find m (V.String name) in
      match List.assoc_opt n imp.defs with
      | Some (Enum _) -> (match field "tag" with Some (V.String c) -> `String c | _ -> bad "%s: no tag" n)
      | Some (Message { fields; oneofs; _ }) ->
          `Assoc
            (List.concat_map
               (fun f ->
                 match field f.name with
                 | None -> bad "%s: no field %s" n f.name
                 | Some x -> (
                     match List.assoc_opt f.name oneofs, x with
                     | Some vname, V.Optional (Some (V.Map c)) -> (
                         (* a oneof: the chosen member's field *)
                         match List.assoc_opt vname imp.defs, V.map_find c (V.String "tag"), V.map_find c (V.String "value") with
                         | Some (Oneof { cases; _ }), Some (V.String tag), Some payload ->
                             let member = List.assoc tag cases in
                             [ (member.name, tj member.ty payload) ]
                         | _ -> bad "%s.%s: a bad oneof value" n f.name)
                     | Some _, _ -> []
                     | None, V.Optional None -> []
                     | None, x -> [ (f.name, tj f.ty x) ]))
               fields)
      | _ -> bad "%s is not a message or an enum" n)
  | _, v -> bad "%s does not fit" (V.to_string v)

(* ---- from JSON --------------------------------------------------------------------------------- *)

let rec json_dyn (j : J.t) : V.t =
  match j with
  | `Null -> V.Null
  | `Bool b -> V.Bool b
  | `Int i -> V.Double (float_of_int i)
  | `Intlit s -> V.Double (float_of_string s)
  | `Float f -> V.Double f
  | `String s -> V.String s
  | `List xs -> V.List (Array.of_list (List.map json_dyn xs))
  | `Assoc kvs -> V.make_map (List.map (fun (k, x) -> (V.String k, json_dyn x)) kvs)

let int_of (j : J.t) =
  match j with
  | `Int i -> Int64.of_int i
  | `Intlit s | `String s -> (match Int64.of_string_opt s with Some i -> i | None -> bad "not an integer: %s" s)
  | `Float f when Float.is_integer f -> Int64.of_float f
  | _ -> bad "not an integer: %s" (J.to_string j)

let uint_of (j : J.t) =
  match j with
  | `Int i when i >= 0 -> Int64.of_int i
  | `Intlit s | `String s -> (match Int64.of_string_opt ("0u" ^ s) with Some i -> i | None -> bad "not an unsigned integer: %s" s)
  | `Float f when Float.is_integer f && f >= 0. -> Int64.of_float f
  | _ -> bad "not an unsigned integer: %s" (J.to_string j)

let in_range lo hi i = if Int64.compare i lo < 0 || Int64.compare i hi > 0 then bad "%Ld is out of range" i else i

let rec of_json (imp : import) (t : t) (j : J.t) : V.t =
  let oj = of_json imp in
  match t, j with
  | I32, j -> V.Int (in_range (-2147483648L) 2147483647L (int_of j))
  | I64, j -> V.Int (int_of j)
  | U32, j -> V.Uint (let u = uint_of j in if Int64.compare u 0L < 0 || Int64.compare u 4294967295L > 0 then bad "%Lu is out of range" u else u)
  | U64, j -> V.Uint (uint_of j)
  | (F32 | F64), `String "NaN" -> V.Double Float.nan
  | (F32 | F64), `String "Infinity" -> V.Double Float.infinity
  | (F32 | F64), `String "-Infinity" -> V.Double Float.neg_infinity
  | (F32 | F64), (`Int _ | `Intlit _ | `Float _) -> V.Double (match j with `Int i -> float_of_int i | `Intlit s -> float_of_string s | `Float f -> f | _ -> 0.)
  | Bool, `Bool b -> V.Bool b
  | Str, `String s -> V.String s
  | Bytes, `String s -> (
      match Cordis_cel.Ext_encoders.decode s with
      | Ok b -> V.Bytes b
      | Error _ -> bad "not base64: %s" s)
  | Ts, `String s -> (match cel "timestamp" [ V.String s ] with V.Timestamp _ as v -> v | e -> bad "%s" (V.to_string e))
  | Dur, `String s -> (match cel "duration" [ V.String s ] with V.Duration _ as v -> v | e -> bad "%s" (V.to_string e))
  | (Dyn | Rec _), j -> json_dyn j
  | Struct, (`Assoc _ as j) -> json_dyn j
  | ListV, (`List _ as j) -> json_dyn j
  | Empty, `Assoc [] -> V.make_map []
  | (Wrap _ | Opt _), `Null -> V.Optional None
  | (Wrap t | Opt t), j -> V.Optional (Some (oj t j))
  | Lst t, `List xs -> V.List (Array.of_list (List.map (oj t) xs))
  | Mp (kt, vt), `Assoc kvs ->
      let key k =
        match kt with
        | Str -> V.String k
        | I32 | I64 -> oj kt (`String k)
        | U32 | U64 -> oj kt (`String k)
        | Bool -> (match k with "true" -> V.Bool true | "false" -> V.Bool false | _ -> bad "a bool key %s" k)
        | _ -> bad "a map key type"
      in
      V.make_map (List.map (fun (k, x) -> (key k, oj vt x)) kvs)
  | Named n, j -> (
      match List.assoc_opt n imp.defs, j with
      | Some (Enum { values; _ }), `String s ->
          if List.mem_assoc s values then V.make_map [ (V.String "tag", V.String s) ] else bad "%s has no value %s" n s
      | Some (Enum { values; _ }), (`Int _ | `Intlit _) ->
          let num = Int64.to_int (int_of j) in
          (match List.find_opt (fun (_, v) -> v = num) values with
           | Some (s, _) -> V.make_map [ (V.String "tag", V.String s) ]
           | None -> bad "%s has no value numbered %d" n num)
      | Some (Message { fields; oneofs; _ }), `Assoc kvs ->
          (* members by proto name or JSON name *)
          let names = Hashtbl.create 8 in
          List.iter
            (fun f ->
              match List.assoc_opt f.name oneofs with
              | Some vname -> (
                  match List.assoc_opt vname imp.defs with
                  | Some (Oneof { cases; _ }) ->
                      List.iter (fun (c, m) -> Hashtbl.replace names m.name (`Case (f.name, vname, c, m)); Hashtbl.replace names m.json (`Case (f.name, vname, c, m))) cases
                  | _ -> ())
              | None -> Hashtbl.replace names f.name (`Field f); Hashtbl.replace names f.json (`Field f))
            fields;
          let given = Hashtbl.create 8 in
          List.iter
            (fun (k, x) ->
              match Hashtbl.find_opt names k with
              | None -> bad "%s has no field %s" n k
              | Some (`Field f) -> if x <> `Null then Hashtbl.replace given f.name (oj f.ty x)
              | Some (`Case (o, _, c, m)) ->
                  if x <> `Null then begin
                    if Hashtbl.mem given o then bad "%s: oneof %s has two members" n o;
                    Hashtbl.replace given o (V.Optional (Some (V.make_map [ (V.String "tag", V.String c); (V.String "value", oj m.ty x) ])))
                  end)
            kvs;
          (* what protobuf JSON may omit *)
          let default (t : t) : V.t =
            match t with
            | I32 | I64 -> V.Int 0L
            | U32 | U64 -> V.Uint 0L
            | F32 | F64 -> V.Double 0.
            | Bool -> V.Bool false
            | Str -> V.String ""
            | Bytes -> V.Bytes ""
            | Wrap _ | Opt _ -> V.Optional None
            | Lst _ -> V.List [||]
            | Mp _ -> V.make_map []
            | Named m -> (
                match List.assoc_opt m imp.defs with
                | Some (Enum { values; _ }) ->
                    let first = match List.find_opt (fun (_, v) -> v = 0) values with Some (s, _) -> s | None -> fst (List.hd values) in
                    V.make_map [ (V.String "tag", V.String first) ]
                | _ -> bad "%s.%s is required" n m)
            | Dyn | Rec _ -> V.Null
            | t -> bad "%s: a field of type %s has no default" n (match t with Ts -> "timestamp" | Dur -> "duration" | _ -> "?")
          in
          V.make_map
            (List.map
               (fun f ->
                 ( V.String f.name,
                   match Hashtbl.find_opt given f.name with
                   | Some v -> v
                   | None -> if f.required then bad "%s.%s is required" n f.name else default f.ty ))
               fields)
      | _ -> bad "%s: %s does not fit" n (J.to_string j))
  | _, j -> bad "%s does not fit" (J.to_string j)

let to_json_string imp name v = J.to_string (to_json imp (Named name) v)

let of_json_string imp name s =
  match of_json imp (Named name) (J.from_string s) with
  | v -> Ok v
  | exception Bad m -> Error m
  | exception Yojson.Json_error m -> Error m
