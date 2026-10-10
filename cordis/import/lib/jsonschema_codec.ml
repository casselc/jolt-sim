(* The codec for JSON Schema imports (docs/cordis/rfc/0003-types-importers-
   scaffolds.md, "Residue and the codec"): JSON to and from the values of
   the imported types, by the name map.

   Values: records as maps with every field (an absent or null one as an
   empty optional), enums as {tag}, variants as {tag, value}, dyn as plain
   JSON (numbers as doubles). Reading is strict: it refuses what the type
   refuses, then evaluates the type's residue (check_T); writing prints
   properties in schema order and omits absent ones. *)

open Jsonschema
module J = Yojson.Safe
module V = Cordis_cel.Value

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt
let def (imp : import) n = match List.assoc_opt n imp.defs with Some d -> d | None -> bad "no type %s" n

(* ---- writing --------------------------------------------------------------------------------------- *)

let rec plain (v : V.t) : J.t =
  match v with
  | V.Null -> `Null
  | V.Bool b -> `Bool b
  | V.Double d when Float.is_integer d && Float.abs d < 9007199254740992. -> `Int (int_of_float d)
  | V.Double d when Float.is_finite d -> `Float d
  | V.Int i -> `Intlit (Int64.to_string i)
  | V.String s -> `String s
  | V.List xs -> `List (Array.to_list (Array.map plain xs))
  | V.Map m -> `Assoc (List.map (function V.String k, x -> (k, plain x) | k, _ -> bad "a JSON key %s" (V.to_string k)) m)
  | v -> bad "no JSON for %s" (V.to_string v)

let field_of (m : (V.t * V.t) list) name = match V.map_find m (V.String name) with Some x -> x | None -> bad "no field %s" name

let rec to_json (imp : import) (t : t) (v : V.t) : J.t =
  match t, v with
  | Str _, V.String s -> `String s
  | Int, V.Int i -> if Int64.abs i < 9007199254740992L then `Int (Int64.to_int i) else `Intlit (Int64.to_string i)
  | Num, V.Double d -> if Float.is_finite d then `Float d else bad "%s has no JSON form" (V.to_string v)
  | Bool, V.Bool b -> `Bool b
  | (Dyn | Rec _ | Checked _), v -> plain v
  | Opt t, V.Optional (Some x) -> to_json imp t x
  | Opt _, V.Optional None -> `Null
  | Lst (t, _), V.List xs -> `List (Array.to_list (Array.map (to_json imp t) xs))
  | Mp (t, _), V.Map m -> `Assoc (List.map (function V.String k, x -> (k, to_json imp t x) | k, _ -> bad "a key %s" (V.to_string k)) m)
  | Named n, V.Map m -> (
      match def imp n with
      | Record fs -> `Assoc (record_members imp fs m)
      | Enum cases -> (
          match V.map_find m (V.String "tag") with
          | Some (V.String c) -> (match List.assoc_opt c cases with Some s -> `String s | None -> bad "%s has no case %s" n c)
          | _ -> bad "not a %s" n)
      | Variant { disc; cases } -> (
          match V.map_find m (V.String "tag"), V.map_find m (V.String "value") with
          | Some (V.String c), Some (V.Map pm) -> (
              match List.find_opt (fun (cs, _, _) -> cs = c) cases with
              | Some (_, value, payload) -> (
                  match def imp payload with Record fs -> `Assoc ((disc, `String value) :: record_members imp fs pm) | _ -> bad "payload")
              | None -> bad "%s has no case %s" n c)
          | _ -> bad "not a %s" n)
      | Alias t -> to_json imp t v)
  | Named n, v -> (match def imp n with Alias t -> to_json imp t v | _ -> bad "not a %s: %s" n (V.to_string v))
  | _ -> bad "not of its type: %s" (V.to_string v)

and record_members imp fs m =
  List.filter_map
    (fun f ->
      match field_of m f.name with
      | V.Optional None -> if f.required then Some (f.json, `Null) else None
      | x -> Some (f.json, to_json imp f.ty x))
    fs

(* ---- the residue ----------------------------------------------------------------------------------- *)

(* whether a value of type n satisfies the rest of its schema *)
let satisfies (imp : import) n (v : V.t) =
  match List.assoc_opt n imp.local with
  | None -> Ok ()
  | Some src -> (
      match Cordis_cel.Cel.eval ~vars:[ ("x", v) ] src with
      | Ok { value = V.Bool true; _ } -> Ok ()
      | Ok { value = V.Bool false; _ } -> Error (Printf.sprintf "the value does not satisfy %s's schema" n)
      | Ok { value; _ } -> Error (Printf.sprintf "%s's residue gave %s" n (V.to_string value))
      | Error e -> Error e)

(* ---- reading --------------------------------------------------------------------------------------- *)

let no_duplicates what kv =
  let rec go = function [] -> () | (k, _) :: rest -> if List.mem_assoc k rest then bad "%s: %S given twice" what k else go rest in
  go kv

let rec dyn (j : J.t) : V.t =
  match j with
  | `Assoc kv -> no_duplicates "an object" kv; V.make_map (List.map (fun (k, v) -> (V.String k, dyn v)) kv)
  | `List xs -> V.List (Array.of_list (List.map dyn xs))
  | j -> Schema_pred.value j

let int_of (j : J.t) =
  match j with
  | `Int i -> Int64.of_int i
  | `Intlit s -> (match Int64.of_string_opt s with Some i -> i | None -> bad "%s is beyond int64" s)
  | `Float f when Float.is_integer f && Float.abs f < 9.2e18 -> Int64.of_float f
  | `Float f when Float.is_integer f -> bad "%g is beyond int64" f
  | j -> bad "not an integer: %s" (J.to_string j)

let rec of_json (imp : import) (t : t) (j : J.t) : V.t =
  match t, j with
  | Str _, `String s -> if String.is_valid_utf_8 s then V.String s else bad "not UTF-8"
  | Int, _ -> V.Int (int_of j)
  | Num, (`Int _ | `Intlit _ | `Float _) -> Schema_pred.value j
  | Bool, `Bool b -> V.Bool b
  | Dyn, j -> dyn j
  | Checked s, j -> (
      match Schema_eval.check ~root:imp.doc s j with
      | Ok true -> dyn j
      | Ok false -> bad "the value does not satisfy its schema: %s" (J.to_string j)
      | Error e -> bad "%s" e)
  | Rec k, j -> (
      (* the referenced type, residue and all; kept as plain JSON *)
      let n = match List.assoc_opt k imp.by_def with Some n -> n | None -> bad "no type for %s" k in
      ignore (of_json imp (Named n) j : V.t);
      dyn j)
  | Opt (Dyn | Rec _ | Checked _), `Null -> V.Optional (Some V.Null) (* null is a value of dyn *)
  | Opt _, `Null -> V.Optional None
  | Opt t, j -> V.Optional (Some (of_json imp t j))
  | Lst (t, _), `List xs -> V.List (Array.of_list (List.map (of_json imp t) xs))
  | Mp (t, _), `Assoc kv -> no_duplicates "a map" kv; V.make_map (List.map (fun (k, x) -> (V.String k, of_json imp t x)) kv)
  | Named n, j -> (
      (* a named value is checked against its own residue where it is read *)
      let v = named_value imp n j in
      match satisfies imp n v with Ok () -> v | Error e -> bad "%s" e)
  | _, j -> bad "not of its type: %s" (J.to_string j)

and named_value imp n j =
  match def imp n, j with
      | Record fs, `Assoc kv -> read_record imp n fs kv
      | Enum cases, `String s -> (
          match List.find_opt (fun (_, v) -> v = s) cases with
          | Some (c, _) -> V.make_map [ (V.String "tag", V.String c) ]
          | None -> bad "%s has no value %S" n s)
      | Variant { disc; cases }, `Assoc kv -> (
          match List.assoc_opt disc kv with
          | Some (`String value) -> (
              match List.find_opt (fun (_, v, _) -> v = value) cases with
              | Some (c, _, payload) -> (
                  match def imp payload with
                  | Record fs -> V.make_map [ (V.String "tag", V.String c); (V.String "value", read_record imp payload fs (List.remove_assoc disc kv)) ]
                  | _ -> bad "payload")
              | None -> bad "%s: %s %S is no case" n disc value)
          | _ -> bad "%s: no %s" n disc)
      | Alias t, j -> of_json imp t j
      | Record _, j -> bad "%s is a record: %s is not an object" n (J.to_string j)
      | _, j -> bad "not a %s: %s" n (J.to_string j)

and read_record imp n fs kv =
  no_duplicates n kv;
  List.iter (fun (k, _) -> if not (List.exists (fun f -> f.json = k) fs) then bad "%s has no property %s" n k) kv;
  V.make_map
    (List.map
       (fun f ->
         let x =
           match List.assoc_opt f.json kv with
           | None -> if f.required then bad "%s: %s is required" n f.json else V.Optional None
           | Some `Null when not (f.nullable || (match f.ty with Dyn | Rec _ | Checked _ | Opt (Dyn | Rec _ | Checked _) -> true | _ -> false)) -> (
               (* refused by the type it would have, which says why *)
               let inner = match f.ty with Opt t -> t | t -> t in
               match of_json imp inner `Null with _ -> bad "%s: %s may not be null" n f.json)
           | Some j -> of_json imp f.ty j
         in
         (V.String f.name, x))
       fs)

let read (imp : import) n (j : J.t) =
  match of_json imp (Named n) j with
  | v -> Ok v
  | exception Bad m -> Error m

let write (imp : import) n v = match to_json imp (Named n) v with j -> Ok j | exception Bad m -> Error m
