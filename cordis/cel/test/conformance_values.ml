(* The corpus's value encoding (cordis/cel/conformance/README.md), shared by
   the conformance runner and the differential test. *)

open Cordis_cel
module J = Yojson.Safe.Util

(* ---- decoding the corpus's value encoding ----------------------------------- *)

exception Unsupported of string

let rec decode (j : Yojson.Safe.t) : Value.t =
  match j with
  | `Assoc [ ("int", `String s) ] -> Value.Int (Int64.of_string s)
  | `Assoc [ ("uint", `String s) ] -> Value.Uint (Int64.of_string ("0u" ^ s))
  | `Assoc [ ("double", d) ] ->
      Value.Double
        (match d with
         | `Float f -> f
         | `Int i -> float_of_int i
         | `Intlit s -> float_of_string s
         | `String "NaN" -> Float.nan
         | `String "Infinity" -> Float.infinity
         | `String "-Infinity" -> Float.neg_infinity
         | `String "-0" -> -0.0
         | _ -> raise (Unsupported "double encoding"))
  | `Assoc [ ("string", `String s) ] -> Value.String s
  | `Assoc [ ("bytes", `String b) ] -> Value.Bytes (Base64_decode.decode b)
  | `Assoc [ ("bool", `Bool b) ] -> Value.Bool b
  | `Assoc [ ("null", _) ] -> Value.Null
  | `Assoc [ ("list", `List xs) ] -> Value.List (Array.of_list (List.map decode xs))
  | `Assoc [ ("map", `List kvs) ] -> (
      let pairs = List.map (function `List [ k; v ] -> (decode k, decode v) | _ -> raise (Unsupported "map entry")) kvs in
      match Value.make_map pairs with Value.Error e -> raise (Unsupported ("map: " ^ e)) | m -> m)
  | `Assoc [ ("type", `String t) ] -> (
      match List.assoc_opt t Eval.type_idents with
      | Some ty -> Value.Type ty
      | None -> Value.Type (Value.T_named t))
  | `Assoc [ ("timestamp", `String t) ] -> (
      match Ctime.parse_timestamp t with Ok (s, n) -> Value.Timestamp (s, n) | Error e -> raise (Unsupported e))
  | `Assoc [ ("duration", `String d) ] -> (
      match Ctime.parse_duration d with Ok (s, n) -> Value.Duration (s, n) | Error e -> raise (Unsupported e))
  | `Assoc [ ("object", o) ] -> (
      let url = J.to_string (J.member "type_url" o) in
      let tname = match String.rindex_opt url '/' with Some i -> String.sub url (i + 1) (String.length url - i - 1) | None -> url in
      match J.member "json" o with
      | `Assoc [ ("unsupported", _) ] -> raise (Unsupported "protobuf message the converter could not decode")
      | j -> (match Proto.of_json tname j with Value.Error e -> raise (Unsupported ("protobuf JSON: " ^ e)) | v -> v))
  | `Assoc [ ("enum", e) ] ->
      Value.Enum { tname = J.to_string (J.member "type" e); num = J.to_int (J.member "value" e) }
  | _ -> raise (Unsupported ("value " ^ Yojson.Safe.to_string j))

(* Structural equality with exact types (int is not uint), NaN equal to NaN. *)
let rec same (a : Value.t) (b : Value.t) =
  match a, b with
  | Double x, Double y -> (Float.is_nan x && Float.is_nan y) || x = y
  | List xs, List ys -> Array.length xs = Array.length ys && Array.for_all2 same xs ys
  | Map xs, Map ys ->
      List.length xs = List.length ys
      && List.for_all
           (fun (k, v) ->
             List.exists (fun (k', v') -> Value.type_of k = Value.type_of k' && Value.equal k k' && same v v') ys)
           xs
  | Type x, Type y -> Value.type_name x = Value.type_name y
  | _ -> Value.type_of a = Value.type_of b && Value.equal a b

let mentions s sub =
  let n = String.length s and m = String.length sub in
  let rec go i = i + m <= n && (String.sub s i m = sub || go (i + 1)) in
  go 0

