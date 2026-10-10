(* The validation predicate's semantics as an interpreter (docs/cordis/rfc/
   0003-types-importers-scaffolds.md, "The interpreter"): keyword for
   keyword what Schema_pred compiles, read the same way (numbers as
   doubles, JSON equality, ocaml-re patterns, lengths in code points, the
   same refusals), but evaluated directly, so it follows a recursive $ref
   and has no size limit. The codec uses it at dyn locations. A reference
   that comes back to itself at the same place in the instance is a loop
   that consumes nothing, and is refused. *)

module J = Yojson.Safe
module P = Schema_pred

exception Loop of string

let number (j : J.t) = match j with `Int i -> Some (float_of_int i) | `Intlit s -> Some (float_of_string s) | `Float f -> Some f | _ -> None
let is_number j = number j <> None

let is_type (j : J.t) = function
  | "object" -> (match j with `Assoc _ -> true | _ -> false)
  | "array" -> (match j with `List _ -> true | _ -> false)
  | "string" -> (match j with `String _ -> true | _ -> false)
  | "boolean" -> (match j with `Bool _ -> true | _ -> false)
  | "null" -> j = `Null
  | "number" -> is_number j
  | "integer" -> (match number j with Some f -> Float.is_finite f && Float.trunc f = f | None -> false)
  | t -> P.unsupported "type %S" t

(* JSON equality as CEL's: numbers by value, maps in any order *)
let rec equal (a : J.t) (b : J.t) =
  match a, b with
  | (`Int _ | `Intlit _ | `Float _), (`Int _ | `Intlit _ | `Float _) -> number a = number b
  | `List xs, `List ys -> List.length xs = List.length ys && List.for_all2 equal xs ys
  | `Assoc xs, `Assoc ys ->
      List.length xs = List.length ys && List.for_all (fun (k, v) -> match List.assoc_opt k ys with Some w -> equal v w | None -> false) xs
  | _ -> a = b

(* a string's length in code points, as CEL's size *)
let length s =
  let n = ref 0 in
  String.iter (fun c -> if Char.code c land 0xC0 <> 0x80 then incr n) s;
  !n

let matches re s =
  ignore (P.regex re : string);
  match Cordis_cel.Eval.compile_regex re with Some r -> Re.execp r s | None -> P.unsupported "pattern %S does not compile" re

let nat = P.nat
let num j = match number j with Some f -> f | None -> P.unsupported "not a number: %s" (J.to_string j)

let rec valid ~(docs : P.docs) ~(stack : (string * J.t) list) (s : J.t) (x : J.t) : bool =
  match s with
  | `Bool b -> b
  | `Assoc kv ->
      let get k = List.assoc_opt k kv in
      let obj = match x with `Assoc o -> Some o | _ -> None and arr = match x with `List a -> Some a | _ -> None in
      let str = match x with `String s -> Some s | _ -> None and n = number x in
      let v = valid ~docs ~stack in
      List.for_all
        (fun (k, a) ->
          if k = "$schema" then
            match a with
            | `String ("https://json-schema.org/draft/2020-12/schema" | "http://json-schema.org/draft-07/schema#" | "http://json-schema.org/draft-07/schema") -> true
            | _ -> P.unsupported "a custom metaschema (%s)" (J.to_string a)
          else if List.mem k P.ignored || k = "$schema" then true
          else if List.mem k P.refused then P.unsupported "keyword %s" k
          else
            match k, a with
            | "type", `String t -> is_type x t
            | "type", `List ts -> List.exists (function `String t -> is_type x t | _ -> P.unsupported "type") ts
            | "const", c -> equal x c
            | "enum", `List vs -> List.exists (equal x) vs
            | "minLength", m -> (match str with Some s -> length s >= nat m | None -> true)
            | "maxLength", m -> (match str with Some s -> length s <= nat m | None -> true)
            | "pattern", `String re -> (match str with Some s -> matches re s | None -> true)
            | "minimum", m -> (match n with Some f -> f >= num m | None -> true)
            | "maximum", m -> (match n with Some f -> f <= num m | None -> true)
            | "exclusiveMinimum", m -> (match n with Some f -> f > num m | None -> true)
            | "exclusiveMaximum", m -> (match n with Some f -> f < num m | None -> true)
            | "multipleOf", m -> (
                match n with
                | Some f ->
                    let q = f /. num m in
                    Float.is_finite q && Float.trunc q = q
                | None -> true)
            | "required", `List ps -> (match obj with Some o -> List.for_all (function `String p -> List.mem_assoc p o | _ -> P.unsupported "required") ps | None -> true)
            | "properties", `Assoc ps -> (match obj with Some o -> List.for_all (fun (p, sub) -> match List.assoc_opt p o with Some y -> v sub y | None -> true) ps | None -> true)
            | "patternProperties", `Assoc ps ->
                (match obj with Some o -> List.for_all (fun (re, sub) -> List.for_all (fun (key, y) -> (not (matches re key)) || v sub y) o) ps | None -> true)
            | "additionalProperties", sub -> (
                match obj with
                | Some o ->
                    let named = match get "properties" with Some (`Assoc ps) -> List.map fst ps | _ -> [] in
                    let patterns = match get "patternProperties" with Some (`Assoc ps) -> List.map fst ps | _ -> [] in
                    List.for_all (fun (key, y) -> List.mem key named || List.exists (fun re -> matches re key) patterns || v sub y) o
                | None -> true)
            | "propertyNames", sub -> (match obj with Some o -> List.for_all (fun (key, _) -> v sub (`String key)) o | None -> true)
            | "minProperties", m -> (match obj with Some o -> List.length o >= nat m | None -> true)
            | "maxProperties", m -> (match obj with Some o -> List.length o <= nat m | None -> true)
            | "dependentRequired", `Assoc ds ->
                (match obj with
                 | Some o -> List.for_all (fun (p, req) -> (not (List.mem_assoc p o)) || match req with `List rs -> List.for_all (function `String r -> List.mem_assoc r o | _ -> P.unsupported "dependentRequired") rs | _ -> P.unsupported "dependentRequired") ds
                 | None -> true)
            | "dependentSchemas", `Assoc ds -> (match obj with Some o -> List.for_all (fun (p, sub) -> (not (List.mem_assoc p o)) || v sub x) ds | None -> true)
            | "prefixItems", `List subs -> (match arr with Some a -> List.for_all2 (fun sub y -> v sub y) (List.filteri (fun i _ -> i < List.length a) subs) (List.filteri (fun i _ -> i < List.length subs) a) | None -> true)
            | "items", sub -> (
                match arr with
                | Some a ->
                    let skip = match get "prefixItems" with Some (`List ps) -> List.length ps | _ -> 0 in
                    List.for_all (fun y -> v sub y) (List.filteri (fun i _ -> i >= skip) a)
                | None -> true)
            | "contains", sub -> (
                match arr with
                | Some a ->
                    let count = List.length (List.filter (v sub) a) in
                    let lo = match get "minContains" with Some m -> nat m | None -> 1 in
                    count >= lo && (match get "maxContains" with Some m -> count <= nat m | None -> true)
                | None -> true)
            | "minItems", m -> (match arr with Some a -> List.length a >= nat m | None -> true)
            | "maxItems", m -> (match arr with Some a -> List.length a <= nat m | None -> true)
            | "uniqueItems", `Bool true ->
                (match arr with
                 | Some a -> let rec distinct = function [] -> true | y :: rest -> (not (List.exists (equal y) rest)) && distinct rest in distinct a
                 | None -> true)
            | "uniqueItems", `Bool false -> true
            | "allOf", `List subs -> List.for_all (fun sub -> v sub x) subs
            | "anyOf", `List subs -> List.exists (fun sub -> v sub x) subs
            | "oneOf", `List subs -> List.length (List.filter (fun sub -> v sub x) subs) = 1
            | "not", sub -> not (v sub x)
            | "if", sub ->
                if v sub x then (match get "then" with Some t -> v t x | None -> true)
                else (match get "else" with Some e -> v e x | None -> true)
            | "$ref", `String r ->
                if List.exists (fun (r', y) -> r' = r && y == x) stack then raise (Loop r);
                valid ~docs ~stack:((r, x) :: stack) (P.resolve docs r) x
            | _ -> true)
        kv
  | _ -> P.unsupported "not a schema: %s" (J.to_string s)

(* Whether the interpreter reads [schema] (a subschema of [root]): every
   keyword it can reach, each reference target once, checked statically
   for what the boundary does not read, as Schema_pred refuses it. *)
let supported ?docs (schema : J.t) =
  let docs, schema = match docs with Some d -> (d, schema) | None -> let d = P.documents schema in (d, P.main_doc d) in
  let seen = Hashtbl.create 16 in
  let one = [ "not"; "if"; "then"; "else"; "contains"; "propertyNames"; "additionalProperties"; "items" ] in
  let many = [ "allOf"; "anyOf"; "oneOf"; "prefixItems" ] in
  let named = [ "properties"; "patternProperties"; "dependentSchemas"; "$defs"; "definitions" ] in
  let rec walk (s : J.t) =
    match s with
    | `Bool _ -> ()
    | `Assoc kv ->
        List.iter
          (fun (k, a) ->
            if k = "$schema" then
              (match a with
               | `String ("https://json-schema.org/draft/2020-12/schema" | "http://json-schema.org/draft-07/schema#" | "http://json-schema.org/draft-07/schema") -> ()
               | _ -> P.unsupported "a custom metaschema (%s)" (J.to_string a))
            else if List.mem k P.refused then P.unsupported "keyword %s" k
            else
              match k, a with
              | "type", `String t -> ignore (is_type `Null t)
              | "type", `List ts -> List.iter (function `String t -> ignore (is_type `Null t) | _ -> P.unsupported "type") ts
              | "type", _ -> P.unsupported "type"
              | "pattern", `String re -> ignore (P.regex re : string)
              | "patternProperties", `Assoc ps -> List.iter (fun (re, sub) -> ignore (P.regex re : string); walk sub) ps
              | ("minLength" | "maxLength" | "minProperties" | "maxProperties" | "minItems" | "maxItems" | "minContains" | "maxContains"), m -> ignore (nat m)
              | ("minimum" | "maximum" | "exclusiveMinimum" | "exclusiveMaximum" | "multipleOf"), m -> ignore (num m)
              | "$ref", `String r ->
                  if not (Hashtbl.mem seen r) then begin
                    Hashtbl.replace seen r ();
                    walk (P.resolve docs r)
                  end
              | k, sub when List.mem k one -> walk sub
              | k, `List subs when List.mem k many -> List.iter walk subs
              | k, `Assoc ps when List.mem k named -> List.iter (fun (_, sub) -> walk sub) ps
              | ("dependentRequired" | "required" | "enum"), `List _ | "dependentRequired", `Assoc _ -> ()
              | _ -> ())
          kv
    | _ -> P.unsupported "not a schema: %s" (J.to_string s)
  in
  match walk schema with () -> Ok () | exception P.Unsupported m -> Error m

(* Whether [x] satisfies [schema] (a subschema of [root]); Unsupported
   for what the boundary does not read, as Schema_pred. *)
let check ?docs (schema : J.t) (x : J.t) =
  let docs, schema = match docs with Some d -> (d, schema) | None -> let d = P.documents schema in (d, P.main_doc d) in
  match valid ~docs ~stack:[] schema x with
  | b -> Ok b
  | exception Loop r -> Error (Printf.sprintf "the reference %s returns to itself without consuming the instance" r)
  | exception P.Unsupported m -> Error m
