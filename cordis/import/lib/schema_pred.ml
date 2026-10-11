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

(* JSON Schema's patterns are ECMA-262; CEL's matches is RE2 (cel/lib/
   re2.ml). Both search unanchored, and both match here by code point (as
   ECMA does with its u flag, and as the JSON Schema test suite's Unicode
   cases expect), so a character beyond the BMP is one `.`, not two. [translate] rewrites what the two spell differently:
   ECMA's \uHHHH (a surrogate pair as the one character it encodes) and
   \xHH become \x{...}, \cX its control character, \0 NUL; `.` excludes
   all four of ECMA's line terminators, and \s and \S are ECMA's white
   space (Unicode's, not RE2's ASCII), written out as ranges; a letter
   escaped for no reason is the letter (ECMA's identity escape, which RE2
   rejects); (?<name> is RE2's (?P<name>; [^] is any character. Features
   with no counterpart are refused rather than approximated: lookaround,
   backreferences, Unicode property classes, legacy octal escapes, a lone
   surrogate, and the empty class []. *)

let ecma_space =
  [ (0x09, 0x0D); (0x20, 0x20); (0xA0, 0xA0); (0x1680, 0x1680); (0x2000, 0x200A); (0x2028, 0x2029); (0x202F, 0x202F); (0x205F, 0x205F);
    (0x3000, 0x3000); (0xFEFF, 0xFEFF) ]

let ranges rs = String.concat "" (List.map (fun (a, b) -> if a = b then Printf.sprintf "\\x{%x}" a else Printf.sprintf "\\x{%x}-\\x{%x}" a b) rs)

let translate p =
  let n = String.length p and i = ref 0 and b = Buffer.create (String.length p + 16) in
  let add = Buffer.add_string b in
  let at s = !i + String.length s <= n && String.sub p !i (String.length s) = s in
  let hex k =
    if !i + k > n then None
    else
      let h = String.sub p !i k in
      if String.for_all (function '0' .. '9' | 'a' .. 'f' | 'A' .. 'F' -> true | _ -> false) h then (i := !i + k; Some (int_of_string ("0x" ^ h))) else None
  in
  let cp v = Printf.sprintf "\\x{%x}" v in
  let surrogate v = v >= 0xD800 && v <= 0xDFFF in
  (* after a backslash *)
  let escape ~in_class =
    if !i >= n then unsupported "pattern %S ends in a backslash" p;
    let c = p.[!i] in
    incr i;
    match c with
    | 'u' -> (
        match hex 4 with
        | Some hi when hi >= 0xD800 && hi <= 0xDBFF && (not in_class) && at "\\u" -> (
            let save = !i in
            i := !i + 2;
            match hex 4 with
            | Some lo when lo >= 0xDC00 && lo <= 0xDFFF -> add (cp (0x10000 + ((hi - 0xD800) lsl 10) + (lo - 0xDC00)))
            | _ -> i := save; unsupported "pattern %S has a lone surrogate" p)
        | Some v when surrogate v -> unsupported "pattern %S has a lone surrogate" p
        | Some v -> add (cp v)
        | None -> add "u")
    | 'x' -> (match hex 2 with Some v -> add (cp v) | None -> add "x")
    | 'c' when !i < n && ((p.[!i] >= 'a' && p.[!i] <= 'z') || (p.[!i] >= 'A' && p.[!i] <= 'Z')) ->
        add (cp (Char.code p.[!i] land 31));
        incr i
    | '0' when not (!i < n && p.[!i] >= '0' && p.[!i] <= '9') -> add (cp 0)
    | '0' .. '9' -> unsupported "pattern %S uses a backreference or an octal escape" p
    | 'd' | 'D' | 'w' | 'W' | 'f' | 'n' | 'r' | 't' | 'v' -> add ("\\" ^ String.make 1 c)
    | 's' -> add (if in_class then ranges ecma_space else "[" ^ ranges ecma_space ^ "]")
    | 'S' -> add (if in_class then ranges (Cordis_cel.Re2.complement ecma_space) else "[^" ^ ranges ecma_space ^ "]")
    | 'b' -> add (if in_class then cp 8 else "\\b")
    | 'B' when not in_class -> add "\\B"
    | 'p' | 'P' -> unsupported "pattern %S uses a Unicode property class" p
    | 'k' -> unsupported "pattern %S uses a backreference" p
    | c when (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') -> add (String.make 1 c)
    | c when Char.code c < 0x80 -> add ("\\" ^ String.make 1 c)
    | c -> Buffer.add_char b c
  in
  while !i < n do
    let c = p.[!i] in
    incr i;
    match c with
    | '\\' -> escape ~in_class:false
    | '.' -> add "[^\\n\\r\\x{2028}\\x{2029}]"
    | '(' when at "?=" || at "?!" || at "?<=" || at "?<!" -> unsupported "pattern %S uses lookaround" p
    | '(' when at "?<" -> add "(?P<"; i := !i + 2
    | '[' ->
        let neg = at "^" in
        if neg then incr i;
        if at "]" then (
          incr i;
          if neg then add ("[" ^ ranges [ (0, 0x10FFFF) ] ^ "]") else unsupported "pattern %S has the empty class []" p)
        else begin
          add (if neg then "[^" else "[");
          let closed = ref false in
          while (not !closed) && !i < n do
            let c = p.[!i] in
            incr i;
            match c with
            | ']' -> closed := true
            | '\\' -> escape ~in_class:true
            | '[' -> add "\\["
            | c -> Buffer.add_char b c
          done;
          if not !closed then unsupported "pattern %S has an unterminated class" p;
          add "]"
        end
    | c -> Buffer.add_char b c
  done;
  Buffer.contents b

let regex p =
  let t = translate p in
  if Cordis_cel.Eval.compile_regex t = None then unsupported "pattern %S does not compile" p;
  cel_string t

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
    match if s.[!i] = '%' && !i + 2 < String.length s then int_of_string_opt ("0x" ^ String.sub s (!i + 1) 2) else None with
    | Some c ->
      Buffer.add_char b (Char.chr c);
      i := !i + 3
    | None -> Buffer.add_char b s.[!i]; incr i
  done;
  Buffer.contents b

(* ---- URIs (RFC 3986, section 5: reference resolution) --------------------------------------------- *)

type uri = { scheme : string option; authority : string option; path : string; query : string option; fragment : string option }

(* RFC 3986 appendix B, by hand *)
let parse_uri s =
  let cut s c = match String.index_opt s c with Some i -> (String.sub s 0 i, Some (String.sub s (i + 1) (String.length s - i - 1))) | None -> (s, None) in
  let s, fragment = cut s '#' in
  let s, query = cut s '?' in
  let scheme, rest =
    match String.index_opt s ':' with
    | Some i when i > 0 && String.for_all (fun c -> match c with 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' | '+' | '-' | '.' -> true | _ -> false) (String.sub s 0 i)
                  && (match s.[0] with 'a' .. 'z' | 'A' .. 'Z' -> true | _ -> false) ->
        (Some (String.sub s 0 i), String.sub s (i + 1) (String.length s - i - 1))
    | _ -> (None, s)
  in
  let authority, path =
    if String.length rest >= 2 && String.sub rest 0 2 = "//" then
      let r = String.sub rest 2 (String.length rest - 2) in
      match String.index_opt r '/' with Some i -> (Some (String.sub r 0 i), String.sub r i (String.length r - i)) | None -> (Some r, "")
    else (None, rest)
  in
  { scheme; authority; path; query; fragment }

let uri_to_string u =
  (match u.scheme with Some s -> s ^ ":" | None -> "")
  ^ (match u.authority with Some a -> "//" ^ a | None -> "")
  ^ u.path
  ^ (match u.query with Some q -> "?" ^ q | None -> "")
  ^ match u.fragment with Some f -> "#" ^ f | None -> ""

let remove_dot_segments path =
  let segs = String.split_on_char '/' path in
  let out =
    List.fold_left
      (fun acc seg -> match seg with "." -> acc | ".." -> (match acc with _ :: rest when rest <> [] -> rest | l -> l) | s -> s :: acc)
      [] segs
  in
  let r = String.concat "/" (List.rev out) in
  (* a trailing . or .. leaves the path ending in / *)
  match List.rev segs with ("." | "..") :: _ -> r ^ "/" | _ -> r

(* The absolute form of reference [r] against base [b]. *)
let resolve_uri b r =
  let base = parse_uri b and r' = parse_uri r in
  let t =
    if r'.scheme <> None then { r' with path = remove_dot_segments r'.path }
    else if r'.authority <> None then { r' with scheme = base.scheme; path = remove_dot_segments r'.path }
    else if r'.path = "" then { base with query = (if r'.query <> None then r'.query else base.query); fragment = r'.fragment }
    else if r'.path.[0] = '/' then { base with path = remove_dot_segments r'.path; query = r'.query; fragment = r'.fragment }
    else
      let merged =
        if base.authority <> None && base.path = "" then "/" ^ r'.path
        else match String.rindex_opt base.path '/' with Some i -> String.sub base.path 0 (i + 1) ^ r'.path | None -> r'.path
      in
      { base with path = remove_dot_segments merged; query = r'.query; fragment = r'.fragment }
  in
  uri_to_string t

let split_fragment u = match String.index_opt u '#' with Some i -> (String.sub u 0 i, String.sub u (i + 1) (String.length u - i - 1)) | None -> (u, "")

(* ---- documents ------------------------------------------------------------------------------------- *)

(* The documents an import reads (docs/cordis/rfc/0003-types-importers-
   scaffolds.md, "Documents"): every $ref rewritten to an absolute URI when
   loaded, every resource ($id) and anchor indexed by absolute URI, so a
   reference resolves by lookup. The main document's URI is [main]. *)
type docs = {
  resources : (string, J.t) Hashtbl.t;  (** absolute URI, no fragment -> the resource (rewritten) *)
  anchors : (string, J.t) Hashtbl.t;  (** absolute URI#anchor -> the subschema *)
  mutable main : string;
}

let values_not_schemas = [ "enum"; "const"; "default"; "examples" ]

(* the keywords whose value maps names (not keywords) to schemas *)
let schema_maps = [ "properties"; "patternProperties"; "$defs"; "definitions"; "dependentSchemas" ]

(* rewrite and index a document whose base is [base]; under a schema map
   every member is a schema, whatever its name ("default" is a common
   property name) *)
let rec load docs ~base (s : J.t) : J.t =
  match s with
  | `Assoc kv ->
      let base = match List.assoc_opt "$id" kv with Some (`String id) -> fst (split_fragment (resolve_uri base id)) | _ -> base in
      let kv =
        List.map
          (fun (k, v) ->
            match k, v with
            | "$ref", `String r -> (k, `String (resolve_uri base r))
            | k, _ when List.mem k values_not_schemas -> (k, v)
            | k, `Assoc members when List.mem k schema_maps -> (k, `Assoc (List.map (fun (n, x) -> (n, load docs ~base x)) members))
            | _ -> (k, load docs ~base v))
          kv
      in
      let s' = `Assoc kv in
      (match List.assoc_opt "$id" kv with Some (`String _) -> Hashtbl.replace docs.resources base s' | _ -> ());
      (match List.assoc_opt "$anchor" kv with Some (`String a) -> Hashtbl.replace docs.anchors (base ^ "#" ^ a) s' | _ -> ());
      s'
  | `List xs -> `List (List.map (load docs ~base) xs)
  | s -> s

(* Add a document given under [uri] (its $id, if any, resolved against
   it); returns its absolute URI. *)
let add docs ?(uri = "") (doc : J.t) =
  let base = match doc with `Assoc kv -> (match List.assoc_opt "$id" kv with Some (`String id) -> fst (split_fragment (resolve_uri uri id)) | _ -> uri) | _ -> uri in
  let doc' = load docs ~base:uri doc in
  Hashtbl.replace docs.resources base doc';
  if uri <> "" && uri <> base then Hashtbl.replace docs.resources uri doc';
  base

let empty_docs () = { resources = Hashtbl.create 64; anchors = Hashtbl.create 64; main = "" }

(* A document set of [main] (given under [uri]) and [others] (each under
   its own URI, or "" to rely on its $id). *)
let documents ?(uri = "") ?(others = []) (main : J.t) =
  let docs = empty_docs () in
  List.iter (fun (u, d) -> ignore (add docs ~uri:u d)) others;
  docs.main <- add docs ~uri main;
  docs

let main_doc docs = Hashtbl.find docs.resources docs.main

(* Resolve an absolute reference (as loading leaves every $ref). *)
let resolve (docs : docs) (r : string) =
  let u, frag = split_fragment r in
  match Hashtbl.find_opt docs.resources u with
  | None -> unsupported "a remote $ref (%s): no document %s was given" r (if u = "" then "(the schema itself)" else u)
  | Some doc ->
      if frag = "" then doc
      else if frag.[0] = '/' then
        let parts = String.split_on_char '/' (String.sub frag 1 (String.length frag - 1)) |> List.map (fun p -> unescape_pointer (percent_decode p)) in
        (* a key earlier drafts used that Schema_draft renamed: a reference
           written against the original document still lands *)
        let renamed = [ ("items", "prefixItems"); ("additionalItems", "items"); ("dependencies", "dependentSchemas"); ("dependencies", "dependentRequired") ] in
        List.fold_left
          (fun (j : J.t) p ->
            match j with
            | `Assoc kv -> (
                match List.assoc_opt p kv with
                | Some (`List _ as v) when p = "items" -> v
                | Some v when not (List.mem_assoc p renamed) -> v
                | found -> (
                    let alternatives = List.filter_map (fun (o, n) -> if o = p then List.assoc_opt n kv else None) renamed in
                    match found, alternatives with
                    | _, v :: _ -> v
                    | Some v, [] -> v
                    | None, [] -> unsupported "$ref %s: no %s" r p))
            | `List xs -> (match int_of_string_opt p with Some i when i < List.length xs -> List.nth xs i | _ -> unsupported "$ref %s: no %s" r p)
            | _ -> unsupported "$ref %s does not resolve" r)
          doc parts
      else
        match Hashtbl.find_opt docs.anchors (u ^ "#" ^ frag) with
        | Some j -> j
        | None -> unsupported "$ref %s: no such anchor" r

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

type ctx = {
  docs : docs;
  mutable fresh : int;
  refs : string list;  (** the $refs being expanded, for recursion *)
  cut : bool;  (** a recursive $ref compiles to true (checked to that edge only) instead of being refused *)
}

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
                  if List.mem r c.refs && c.cut then [ "true" ] else begin
                  if List.mem r c.refs then unsupported "a recursive $ref (%s)" r;
                  let p = pred { c with refs = r :: c.refs } (resolve c.docs r) x in
                  if String.length p > !max_size then unsupported "a predicate over %d bytes" !max_size;
                  [ p ] end
              | _ -> [] (* an unknown keyword: ignored, as JSON Schema says *))
          kv
      in
      conj parts
  | _ -> unsupported "not a schema: %s" (J.to_string s)

(* The predicate for [schema] at CEL expression [x]: [schema] is a
   subschema of a document in [docs] (as loaded), or, without [docs], a
   document of its own. *)
let compile ?docs ?(cut = false) (schema : J.t) (x : string) =
  let docs, schema = match docs with Some d -> (d, schema) | None -> let d = documents schema in (d, main_doc d) in
  let c = { docs; fresh = 0; refs = []; cut } in
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
