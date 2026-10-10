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

(* What a successful evaluation annotates, for unevaluatedProperties and
   unevaluatedItems: the properties and the item indices some keyword
   evaluated, or every item. A failed subschema annotates nothing. *)
type ann = { props : string list; items : int list; all : bool }

let none = { props = []; items = []; all = false }
let merge a b = { props = a.props @ b.props; items = a.items @ b.items; all = a.all || b.all }

(* the keywords only this interpreter reads; Schema_pred refuses them *)
let annotating = [ "unevaluatedProperties"; "unevaluatedItems" ]
let refused = List.filter (fun k -> not (List.mem k annotating)) P.refused

(* [eval ~collect s x] is the annotations of a valid [x], or None. With
   [collect] false nothing reads the annotations, so anyOf may stop at
   its first match; a schema with an unevaluated* keyword sets it for its
   siblings and what they reach. *)
let rec eval ~(docs : P.docs) ~(stack : (string * J.t) list) ~collect (s : J.t) (x : J.t) : ann option =
  match s with
  | `Bool b -> if b then Some none else None
  | `Assoc kv ->
      let get k = List.assoc_opt k kv in
      let collect = collect || List.exists (fun k -> List.mem_assoc k kv) annotating in
      let obj = match x with `Assoc o -> Some o | _ -> None and arr = match x with `List a -> Some a | _ -> None in
      let str = match x with `String s -> Some s | _ -> None and n = number x in
      let ev sub y = eval ~docs ~stack ~collect sub y in
      let v sub y = ev sub y <> None in
      let ok b = if b then Some none else None in
      let keyword (k, a) : ann option =
        if k = "$schema" then
          match a with
          | `String ("https://json-schema.org/draft/2020-12/schema" | "http://json-schema.org/draft-07/schema#" | "http://json-schema.org/draft-07/schema") -> Some none
          | _ -> P.unsupported "a custom metaschema (%s)" (J.to_string a)
        else if List.mem k P.ignored then Some none
        else if List.mem k refused then P.unsupported "keyword %s" k
        else
          match k, a with
          | "type", `String t -> ok (is_type x t)
          | "type", `List ts -> ok (List.exists (function `String t -> is_type x t | _ -> P.unsupported "type") ts)
          | "const", c -> ok (equal x c)
          | "enum", `List vs -> ok (List.exists (equal x) vs)
          | "minLength", m -> ok (match str with Some s -> length s >= nat m | None -> true)
          | "maxLength", m -> ok (match str with Some s -> length s <= nat m | None -> true)
          | "pattern", `String re -> ok (match str with Some s -> matches re s | None -> true)
          | "minimum", m -> ok (match n with Some f -> f >= num m | None -> true)
          | "maximum", m -> ok (match n with Some f -> f <= num m | None -> true)
          | "exclusiveMinimum", m -> ok (match n with Some f -> f > num m | None -> true)
          | "exclusiveMaximum", m -> ok (match n with Some f -> f < num m | None -> true)
          | "multipleOf", m ->
              ok
                (match n with
                 | Some f ->
                     let q = f /. num m in
                     Float.is_finite q && Float.trunc q = q
                 | None -> true)
          | "required", `List ps -> ok (match obj with Some o -> List.for_all (function `String p -> List.mem_assoc p o | _ -> P.unsupported "required") ps | None -> true)
          | "properties", `Assoc ps -> (
              match obj with
              | Some o ->
                  let present = List.filter (fun (p, _) -> List.mem_assoc p o) ps in
                  if List.for_all (fun (p, sub) -> v sub (List.assoc p o)) present then Some { none with props = List.map fst present } else None
              | None -> Some none)
          | "patternProperties", `Assoc ps -> (
              match obj with
              | Some o ->
                  if List.for_all (fun (re, sub) -> List.for_all (fun (key, y) -> (not (matches re key)) || v sub y) o) ps then
                    Some { none with props = List.filter_map (fun (key, _) -> if List.exists (fun (re, _) -> matches re key) ps then Some key else None) o }
                  else None
              | None -> Some none)
          | "additionalProperties", sub -> (
              match obj with
              | Some o ->
                  let named = match get "properties" with Some (`Assoc ps) -> List.map fst ps | _ -> [] in
                  let patterns = match get "patternProperties" with Some (`Assoc ps) -> List.map fst ps | _ -> [] in
                  let extra = List.filter (fun (key, _) -> not (List.mem key named || List.exists (fun re -> matches re key) patterns)) o in
                  if List.for_all (fun (_, y) -> v sub y) extra then Some { none with props = List.map fst extra } else None
              | None -> Some none)
          | "propertyNames", sub -> ok (match obj with Some o -> List.for_all (fun (key, _) -> eval ~docs ~stack ~collect:false sub (`String key) <> None) o | None -> true)
          | "minProperties", m -> ok (match obj with Some o -> List.length o >= nat m | None -> true)
          | "maxProperties", m -> ok (match obj with Some o -> List.length o <= nat m | None -> true)
          | "dependentRequired", `Assoc ds ->
              ok
                (match obj with
                 | Some o -> List.for_all (fun (p, req) -> (not (List.mem_assoc p o)) || match req with `List rs -> List.for_all (function `String r -> List.mem_assoc r o | _ -> P.unsupported "dependentRequired") rs | _ -> P.unsupported "dependentRequired") ds
                 | None -> true)
          | "dependentSchemas", `Assoc ds -> (
              match obj with
              | Some o -> all (List.filter_map (fun (p, sub) -> if List.mem_assoc p o then Some (fun () -> ev sub x) else None) ds)
              | None -> Some none)
          | "prefixItems", `List subs -> (
              match arr with
              | Some a ->
                  let k = min (List.length a) (List.length subs) in
                  if List.for_all2 (fun sub y -> v sub y) (List.filteri (fun i _ -> i < k) subs) (List.filteri (fun i _ -> i < k) a) then Some { none with items = List.init k Fun.id }
                  else None
              | None -> Some none)
          | "items", sub -> (
              match arr with
              | Some a ->
                  let skip = match get "prefixItems" with Some (`List ps) -> List.length ps | _ -> 0 in
                  if List.for_all (fun y -> v sub y) (List.filteri (fun i _ -> i >= skip) a) then Some { none with all = true } else None
              | None -> Some none)
          | "contains", sub -> (
              match arr with
              | Some a ->
                  let hits = List.filteri (fun _ y -> v sub y) a |> List.length in
                  let idx = List.concat (List.mapi (fun i y -> if v sub y then [ i ] else []) a) in
                  let lo = match get "minContains" with Some m -> nat m | None -> 1 in
                  if hits >= lo && match get "maxContains" with Some m -> hits <= nat m | None -> true then Some { none with items = idx } else None
              | None -> Some none)
          | "minItems", m -> ok (match arr with Some a -> List.length a >= nat m | None -> true)
          | "maxItems", m -> ok (match arr with Some a -> List.length a <= nat m | None -> true)
          | "uniqueItems", `Bool true ->
              ok
                (match arr with
                 | Some a -> let rec distinct = function [] -> true | y :: rest -> (not (List.exists (equal y) rest)) && distinct rest in distinct a
                 | None -> true)
          | "uniqueItems", `Bool false -> Some none
          | "allOf", `List subs -> all (List.map (fun sub () -> ev sub x) subs)
          | "anyOf", `List subs ->
              if collect then
                let anns = List.filter_map (fun sub -> ev sub x) subs in
                if anns = [] then None else Some (List.fold_left merge none anns)
              else ok (List.exists (fun sub -> v sub x) subs)
          | "oneOf", `List subs -> (match List.filter_map (fun sub -> ev sub x) subs with [ a ] -> Some a | _ -> None)
          | "not", sub -> ok (eval ~docs ~stack ~collect:false sub x = None)
          | "if", sub -> (
              match ev sub x with
              | Some a -> (match get "then" with Some t -> Option.map (merge a) (ev t x) | None -> Some a)
              | None -> (match get "else" with Some e -> ev e x | None -> Some none))
          | "$ref", `String r ->
              if List.exists (fun (r', y) -> r' = r && y == x) stack then raise (Loop r);
              eval ~docs ~stack:((r, x) :: stack) ~collect (P.resolve docs r) x
          | _ -> Some none
      in
      (* the siblings first, then what they left unevaluated *)
      let rest = List.filter (fun (k, _) -> not (List.mem k annotating)) kv in
      (match all (List.map (fun kw () -> keyword kw) rest) with
       | None -> None
       | Some acc ->
           let acc = Some acc in
           let acc =
             match get "unevaluatedProperties", obj with
             | Some sub, Some o ->
                 Option.bind acc (fun acc ->
                     if List.for_all (fun (key, y) -> List.mem key acc.props || v sub y) o then Some { acc with props = List.map fst o } else None)
             | _ -> acc
           in
           match get "unevaluatedItems", arr with
           | Some sub, Some a ->
               Option.bind acc (fun acc ->
                   if acc.all || List.for_all (fun (i, y) -> List.mem i acc.items || v sub y) (List.mapi (fun i y -> (i, y)) a) then Some { acc with all = true } else None)
           | _ -> acc)
  | _ -> P.unsupported "not a schema: %s" (J.to_string s)

(* every one of [fs] succeeds, their annotations merged; stops at the first failure *)
and all fs =
  let rec go acc = function [] -> Some acc | f :: rest -> (match f () with Some a -> go (merge acc a) rest | None -> None) in
  go none fs

let valid ~docs ~stack s x = eval ~docs ~stack ~collect:false s x <> None

(* Whether the interpreter reads [schema] (a subschema of [root]): every
   keyword it can reach, each reference target once, checked statically
   for what the boundary does not read, as Schema_pred refuses it. *)
let supported ?docs (schema : J.t) =
  let docs, schema = match docs with Some d -> (d, schema) | None -> let d = P.documents schema in (d, P.main_doc d) in
  let seen = Hashtbl.create 16 in
  let one = [ "not"; "if"; "then"; "else"; "contains"; "propertyNames"; "additionalProperties"; "items"; "unevaluatedProperties"; "unevaluatedItems" ] in
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
            else if List.mem k refused then P.unsupported "keyword %s" k
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
