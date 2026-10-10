(* The validation predicate (docs/cordis/rfc/0003-types-importers-
   scaffolds.md, "The JSON Schema importer"): a JSON Schema (2020-12)
   compiled to a CEL predicate over the instance as plain JSON (objects as
   maps, arrays as lists, numbers as doubles, null). Each keyword is a
   conjunct guarded by the instance type it applies to, so the predicate
   never errors. What cannot be compiled raises [Unsupported], with the
   reason. *)

module J = Yojson.Safe

exception Unsupported of string

let unsupported fmt = Printf.ksprintf (fun m -> raise (Unsupported m)) fmt

(* ---- CEL literals --------------------------------------------------------------------------------- *)

let cel_string s =
  let b = Buffer.create (String.length s + 2) in
  Buffer.add_char b '"';
  String.iter
    (fun c ->
      match c with
      | '"' -> Buffer.add_string b "\\\""
      | '\\' -> Buffer.add_string b "\\\\"
      | '\n' -> Buffer.add_string b "\\n"
      | '\r' -> Buffer.add_string b "\\r"
      | '\t' -> Buffer.add_string b "\\t"
      | c when Char.code c < 0x20 || Char.code c = 0x7f -> Buffer.add_string b (Printf.sprintf "\\x%02x" (Char.code c))
      | c -> Buffer.add_char b c)
    s;
  Buffer.add_char b '"';
  Buffer.contents b

let cel_double f =
  if Float.is_integer f && Float.abs f < 1e15 then Printf.sprintf "%.1f" f
  else
    let s = Printf.sprintf "%.17g" f in
    if String.contains s '.' || String.contains s 'e' || String.contains s 'n' || String.contains s 'i' then s else s ^ ".0"

(* A JSON value as a CEL literal of its plain-JSON form: numbers as doubles. *)
let rec literal (j : J.t) =
  match j with
  | `Null -> "null"
  | `Bool b -> string_of_bool b
  | `Int i -> cel_double (float_of_int i)
  | `Intlit s -> cel_double (float_of_string s)
  | `Float f -> cel_double f
  | `String s -> cel_string s
  | `List xs -> "[" ^ String.concat ", " (List.map (fun x -> "dyn(" ^ literal x ^ ")") xs) ^ "]"
  | `Assoc kv -> "{" ^ String.concat ", " (List.map (fun (k, v) -> cel_string k ^ ": dyn(" ^ literal v ^ ")") kv) ^ "}"

let number (j : J.t) =
  match j with
  | `Int i -> float_of_int i
  | `Intlit s -> float_of_string s
  | `Float f -> f
  | _ -> unsupported "not a number: %s" (J.to_string j)

let nat (j : J.t) = match j with `Int i when i >= 0 -> i | `Float f when Float.is_integer f && f >= 0. -> int_of_float f | _ -> unsupported "not a count: %s" (J.to_string j)

(* ---- regular expressions --------------------------------------------------------------------------- *)

(* JSON Schema's patterns are ECMA-262; CEL's matches is RE2-like (here
   ocaml-re's Perl syntax). Both search unanchored. Features with no
   counterpart are refused rather than approximated. *)
let regex p =
  let has sub =
    let n = String.length sub in
    let rec go i = i + n <= String.length p && (String.sub p i n = sub || go (i + 1)) in
    go 0
  in
  if has "(?=" || has "(?!" || has "(?<=" || has "(?<!" then unsupported "pattern %S uses lookaround" p;
  if List.exists (fun d -> has ("\\" ^ string_of_int d)) [ 1; 2; 3; 4; 5; 6; 7; 8; 9 ] then unsupported "pattern %S uses a backreference" p;
  if has "\\p{" || has "\\P{" then unsupported "pattern %S uses a Unicode property class" p;
  String.iter (fun c -> if Char.code c >= 0x80 then unsupported "pattern %S is not ASCII" p) p;
  if Cordis_cel.Eval.compile_regex p = None then unsupported "pattern %S does not compile" p;
  cel_string p

(* ---- references ------------------------------------------------------------------------------------ *)

let replace ~sub ~by s =
  let n = String.length sub and b = Buffer.create (String.length s) in
  let i = ref 0 in
  while !i < String.length s do
    if !i + n <= String.length s && String.sub s !i n = sub then (Buffer.add_string b by; i := !i + n)
    else (Buffer.add_char b s.[!i]; incr i)
  done;
  Buffer.contents b

let unescape_pointer s = replace ~sub:"~0" ~by:"~" (replace ~sub:"~1" ~by:"/" s)

let percent_decode s =
  let b = Buffer.create (String.length s) and i = ref 0 in
  while !i < String.length s do
    if s.[!i] = '%' && !i + 2 < String.length s then begin
      Buffer.add_char b (Char.chr (int_of_string ("0x" ^ String.sub s (!i + 1) 2)));
      i := !i + 3
    end
    else (Buffer.add_char b s.[!i]; incr i)
  done;
  Buffer.contents b

(* every subschema with an $anchor, by anchor *)
let rec anchors (j : J.t) =
  match j with
  | `Assoc kv ->
      (match List.assoc_opt "$anchor" kv with Some (`String a) -> [ (a, j) ] | _ -> [])
      @ List.concat_map (fun (_, v) -> anchors v) kv
  | `List xs -> List.concat_map anchors xs
  | _ -> []

(* Resolve a $ref within the document. *)
let resolve (root : J.t) (r : string) =
  let doc_id = match root with `Assoc kv -> (match List.assoc_opt "$id" kv with Some (`String s) -> s | _ -> "") | _ -> "" in
  let r = if doc_id <> "" && String.length r > String.length doc_id && String.sub r 0 (String.length doc_id) = doc_id then String.sub r (String.length doc_id) (String.length r - String.length doc_id) else r in
  if r = "#" || r = "" then root
  else if String.length r >= 2 && String.sub r 0 2 = "#/" then
    let parts = String.split_on_char '/' (String.sub r 2 (String.length r - 2)) |> List.map (fun p -> unescape_pointer (percent_decode p)) in
    List.fold_left
      (fun (j : J.t) p ->
        match j with
        | `Assoc kv -> (match List.assoc_opt p kv with Some v -> v | None -> unsupported "$ref %s: no %s" r p)
        | `List xs -> (match int_of_string_opt p with Some i when i < List.length xs -> List.nth xs i | _ -> unsupported "$ref %s: no %s" r p)
        | _ -> unsupported "$ref %s does not resolve" r)
      root parts
  else if String.length r >= 1 && r.[0] = '#' then
    match List.assoc_opt (String.sub r 1 (String.length r - 1)) (anchors root) with
    | Some j -> j
    | None -> unsupported "$ref %s: no such anchor" r
  else unsupported "a remote $ref (%s)" r

(* ---- the compiler ----------------------------------------------------------------------------------- *)

let conj = function [] -> "true" | [ p ] -> p | ps -> "(" ^ String.concat " && " ps ^ ")"
let disj = function [] -> "false" | [ p ] -> p | ps -> "(" ^ String.concat " || " ps ^ ")"

let is_type x = function
  | "object" -> Printf.sprintf "type(%s) == map" x
  | "array" -> Printf.sprintf "type(%s) == list" x
  | "string" -> Printf.sprintf "type(%s) == string" x
  | "boolean" -> Printf.sprintf "type(%s) == bool" x
  | "null" -> Printf.sprintf "type(%s) == null_type" x
  | "number" -> Printf.sprintf "(type(%s) == double || type(%s) == int || type(%s) == uint)" x x x
  | "integer" ->
      Printf.sprintf "(type(%s) == int || type(%s) == uint || (type(%s) == double && math.isFinite(%s) && math.trunc(%s) == %s))" x x x x x x
  | t -> unsupported "type %S" t

(* keywords that are annotations, or handled by the keyword next to them *)
let ignored =
  [ "$id"; "$anchor"; "$defs"; "definitions"; "$comment"; "title"; "description"; "default"; "examples";
    "deprecated"; "readOnly"; "writeOnly"; "format"; "contentEncoding"; "contentMediaType"; "contentSchema";
    "then"; "else"; "minContains"; "maxContains"; "$vocabulary"; "discriminator" ]

let refused = [ "$dynamicRef"; "$dynamicAnchor"; "$recursiveRef"; "$recursiveAnchor"; "unevaluatedProperties"; "unevaluatedItems" ]

type ctx = { root : J.t; mutable fresh : int; refs : string list  (** the $refs being expanded, for recursion *) }

let fresh c prefix =
  c.fresh <- c.fresh + 1;
  Printf.sprintf "%s%d" prefix c.fresh

(* The largest predicate compiled: references are inlined, so a schema
   that shares definitions widely can compile to text exponential in its
   size; past this it is refused rather than evaluated. *)
let max_size = ref 200_000

let rec pred (c : ctx) (s : J.t) (x : string) : string =
  match s with
  | `Bool true -> "true"
  | `Bool false -> "false"
  | `Assoc kv ->
      let get k = List.assoc_opt k kv in
      let guard t p = Printf.sprintf "(!(%s) || %s)" (is_type x t) p in
      let parts =
        List.concat_map
          (fun (k, v) ->
            if k = "$schema" then
              match v with
              | `String ("https://json-schema.org/draft/2020-12/schema" | "http://json-schema.org/draft-07/schema#" | "http://json-schema.org/draft-07/schema") -> []
              | _ -> unsupported "a custom metaschema (%s)" (J.to_string v)
            else if k = "$id" && v <> (match c.root with `Assoc rk -> Option.value (List.assoc_opt "$id" rk) ~default:`Null | _ -> `Null) then
              unsupported "an embedded resource ($id %s): references would resolve against it" (J.to_string v)
            else if List.mem k ignored then []
            else if List.mem k refused then unsupported "keyword %s" k
            else
              match k, v with
              | "type", `String t -> [ is_type x t ]
              | "type", `List ts -> [ disj (List.map (function `String t -> is_type x t | _ -> unsupported "type") ts) ]
              | "const", v -> [ Printf.sprintf "%s == dyn(%s)" x (literal v) ]
              | "enum", `List vs -> [ Printf.sprintf "%s in [%s]" x (String.concat ", " (List.map (fun v -> "dyn(" ^ literal v ^ ")") vs)) ]
              (* strings *)
              | "minLength", n -> [ guard "string" (Printf.sprintf "size(%s) >= %d" x (nat n)) ]
              | "maxLength", n -> [ guard "string" (Printf.sprintf "size(%s) <= %d" x (nat n)) ]
              | "pattern", `String p -> [ guard "string" (Printf.sprintf "%s.matches(%s)" x (regex p)) ]
              (* numbers *)
              | "minimum", n -> [ guard "number" (Printf.sprintf "%s >= %s" x (cel_double (number n))) ]
              | "maximum", n -> [ guard "number" (Printf.sprintf "%s <= %s" x (cel_double (number n))) ]
              | "exclusiveMinimum", n -> [ guard "number" (Printf.sprintf "%s > %s" x (cel_double (number n))) ]
              | "exclusiveMaximum", n -> [ guard "number" (Printf.sprintf "%s < %s" x (cel_double (number n))) ]
              | "multipleOf", n ->
                  let m = cel_double (number n) in
                  [ guard "number" (Printf.sprintf "(math.isFinite(double(%s) / %s) && math.trunc(double(%s) / %s) == double(%s) / %s)" x m x m x m) ]
              (* objects *)
              | "required", `List ps -> [ guard "object" (conj (List.map (function `String p -> Printf.sprintf "%s in %s" (cel_string p) x | _ -> unsupported "required") ps)) ]
              | "properties", `Assoc ps ->
                  [ guard "object" (conj (List.map (fun (p, sub) -> Printf.sprintf "(!(%s in %s) || %s)" (cel_string p) x (pred c sub (Printf.sprintf "%s[%s]" x (cel_string p)))) ps)) ]
              | "patternProperties", `Assoc ps ->
                  [ guard "object"
                      (conj
                         (List.map
                            (fun (re, sub) ->
                              let k = fresh c "k" in
                              Printf.sprintf "%s.all(%s, !%s.matches(%s) || %s)" x k k (regex re) (pred c sub (Printf.sprintf "%s[%s]" x k)))
                            ps)) ]
              | "additionalProperties", sub ->
                  let k = fresh c "k" in
                  let named = match get "properties" with Some (`Assoc ps) -> List.map (fun (p, _) -> Printf.sprintf "%s == %s" k (cel_string p)) ps | _ -> [] in
                  let patterned = match get "patternProperties" with Some (`Assoc ps) -> List.map (fun (re, _) -> Printf.sprintf "%s.matches(%s)" k (regex re)) ps | _ -> [] in
                  [ guard "object" (Printf.sprintf "%s.all(%s, %s)" x k (disj (named @ patterned @ [ pred c sub (Printf.sprintf "%s[%s]" x k) ]))) ]
              | "propertyNames", sub ->
                  let k = fresh c "k" in
                  [ guard "object" (Printf.sprintf "%s.all(%s, %s)" x k (pred c sub k)) ]
              | "minProperties", n -> [ guard "object" (Printf.sprintf "size(%s) >= %d" x (nat n)) ]
              | "maxProperties", n -> [ guard "object" (Printf.sprintf "size(%s) <= %d" x (nat n)) ]
              | "dependentRequired", `Assoc ds ->
                  [ guard "object"
                      (conj
                         (List.map
                            (fun (p, req) ->
                              let req = match req with `List rs -> List.map (function `String r -> Printf.sprintf "%s in %s" (cel_string r) x | _ -> unsupported "dependentRequired") rs | _ -> unsupported "dependentRequired" in
                              Printf.sprintf "(!(%s in %s) || %s)" (cel_string p) x (conj req))
                            ds)) ]
              | "dependentSchemas", `Assoc ds ->
                  [ guard "object" (conj (List.map (fun (p, sub) -> Printf.sprintf "(!(%s in %s) || %s)" (cel_string p) x (pred c sub x)) ds)) ]
              (* arrays *)
              | "prefixItems", `List subs ->
                  [ guard "array" (conj (List.mapi (fun i sub -> Printf.sprintf "(size(%s) <= %d || %s)" x i (pred c sub (Printf.sprintf "%s[%d]" x i))) subs)) ]
              | "items", sub ->
                  let n = match get "prefixItems" with Some (`List ps) -> List.length ps | _ -> 0 in
                  let i = fresh c "i" and v = fresh c "v" in
                  [ guard "array" (Printf.sprintf "%s.all(%s, %s, %s < %d || %s)" x i v i n (pred c sub v)) ]
              | "contains", sub ->
                  let v = fresh c "v" in
                  let count = Printf.sprintf "size(%s.filter(%s, %s))" x v (pred c sub v) in
                  let lo = match get "minContains" with Some n -> nat n | None -> 1 in
                  let hi = match get "maxContains" with Some n -> [ Printf.sprintf "%s <= %d" count (nat n) ] | None -> [] in
                  [ guard "array" (conj ((if lo > 0 then [ Printf.sprintf "%s >= %d" count lo ] else []) @ hi)) ]
              | "minItems", n -> [ guard "array" (Printf.sprintf "size(%s) >= %d" x (nat n)) ]
              | "maxItems", n -> [ guard "array" (Printf.sprintf "size(%s) <= %d" x (nat n)) ]
              | "uniqueItems", `Bool true -> [ guard "array" (Printf.sprintf "size(%s.distinct()) == size(%s)" x x) ]
              | "uniqueItems", `Bool false -> []
              (* applicators *)
              | "allOf", `List subs -> [ conj (List.map (fun sub -> pred c sub x) subs) ]
              | "anyOf", `List subs -> [ disj (List.map (fun sub -> pred c sub x) subs) ]
              | "oneOf", `List subs ->
                  let b = fresh c "b" in
                  [ Printf.sprintf "size([%s].filter(%s, %s)) == 1" (String.concat ", " (List.map (fun sub -> pred c sub x) subs)) b b ]
              | "not", sub -> [ Printf.sprintf "!(%s)" (pred c sub x) ]
              | "if", sub ->
                  let t = match get "then" with Some s -> pred c s x | None -> "true" in
                  let e = match get "else" with Some s -> pred c s x | None -> "true" in
                  [ Printf.sprintf "(%s ? %s : %s)" (pred c sub x) t e ]
              | "$ref", `String r ->
                  if List.mem r c.refs then unsupported "a recursive $ref (%s)" r;
                  let p = pred { c with refs = r :: c.refs } (resolve c.root r) x in
                  if String.length p > !max_size then unsupported "a predicate over %d bytes" !max_size;
                  [ p ]
              | _ -> [] (* an unknown keyword: ignored, as JSON Schema says *))
          kv
      in
      conj parts
  | _ -> unsupported "not a schema: %s" (J.to_string s)

(* The predicate for [schema] (a subschema of [root]) at CEL expression [x]. *)
let compile ?(root : J.t option) (schema : J.t) (x : string) =
  let c = { root = Option.value root ~default:schema; fresh = 0; refs = [] } in
  let src = pred c schema x in
  if String.length src > !max_size then unsupported "a predicate of %d bytes, over the limit of %d" (String.length src) !max_size;
  src

(* ---- plain JSON as a CEL value ------------------------------------------------------------------------ *)

let rec value (j : J.t) : Cordis_cel.Value.t =
  let module V = Cordis_cel.Value in
  match j with
  | `Null -> V.Null
  | `Bool b -> V.Bool b
  | `Int i -> V.Double (float_of_int i)
  | `Intlit s -> V.Double (float_of_string s)
  | `Float f -> V.Double f
  | `String s -> V.String s
  | `List xs -> V.List (Array.of_list (List.map value xs))
  | `Assoc kv -> V.make_map (List.map (fun (k, v) -> (V.String k, value v)) kv)
