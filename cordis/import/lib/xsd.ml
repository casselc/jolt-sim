(* XSD to graph-language types (docs/cordis/rfc/0005-xml-csv-frames.md,
   revision 4, "XML and XSD"): the document element's type, through the
   JSON Schema importer, as Table Schema is, so the types file, residue,
   report, name map and strict codec are that importer's. Beside the JSON
   Schema it builds the shape the XML codec reads and writes by: which
   properties are attributes, the elements in order with their occurrence,
   the choices, and each simple value's lexical kind.

     complexType with sequence / all   a closed record; elements in order / any order
     choice                            a variant, one case per element ({kind; value})
     minOccurs 0, maxOccurs 1          option(T)
     maxOccurs n, unbounded            list(T) <max n>, at least minOccurs (none: the empty list)
     attribute                         a field; optional unless use="required" or it has a default
     default, fixed                    the value read when absent; fixed also a const
     simpleContent extension           a record with value beside the attributes
     complexContent extension          the base's fields, then its own
     simpleType restriction            the base, and its facets as keywords (enumeration
                                       of strings a variant of unit cases)
     list                              list(T)
     string, normalizedString, token,  string (whitespace as XSD says: replaced, collapsed; a
     anyURI                            value that would not survive it is refused by a pattern)
     boolean                           bool
     integer and its derivations       int, the widths as bounds (beyond int64 refused, as
                                       JSON Schema's integer)
     float, double                     double
     decimal                           double, reported: lossy (RFC 0005, open question 1)

   Refused, naming what: recursion (RFC 0002's references first),
   include, import and redefine, groups and attribute groups, xs:any and
   anyAttribute, mixed content, nillable, unions, element defaults,
   substitution groups and abstract types, qualified attributes, the
   calendar types (open question 2), the binary and name-like types, and
   patterns using XSD's own classes (\i, \c, class subtraction, \p{Is..}).
   key, keyref and unique are reported, not checked. *)

module J = Yojson.Safe
module X = Xml_tree

exception Refused of string

let refuse fmt = Printf.ksprintf (fun m -> raise (Refused m)) fmt
let xs = "http://www.w3.org/2001/XMLSchema"

(* how a simple value is written: its whitespace, and its lexical space *)
type ws = Preserve | Replace | Collapse
type lex = Str of ws | Int | Dec | Dbl | Bool | Lst of lex

type shape =
  | Simple of lex
  | Complex of complex
  | Ref of string  (** a complex type by name, where it is referred to inside itself: a recursive type *)
and complex = { attrs : attr list; content : content }
and attr = { aname : string; ajson : string; alex : lex; adefault : string option }
and content = Empty | Seq of particle list | All of particle list | Value of lex
and particle = Elem of elem | Choice of choice
and elem = { ename : string; ens : string; ejson : string; min : int; max : int option; shape : shape }
and choice = { cjson : string; cmin : int; cmax : int option; cases : elem list }

type t = {
  imp : Jsonschema.import;
  root : string * string;  (** the document element: namespace, name *)
  row : string;  (** its type *)
  shape : shape;
  named : (string * complex) list;  (** the complex types by name, which a [Ref] names *)
  tns : string;
  qualified : bool;  (** elementFormDefault qualified: every element in the target namespace *)
  report : (string * string) list;
  schema : J.t;  (** the JSON Schema imported *)
}

(* ---- the schema document ---------------------------------------------------------------------------- *)

let is_xs (e : X.el) n = e.ns = xs && e.name = n
let children (e : X.el) = List.filter (fun (k : X.el) -> k.ns = xs && k.name <> "annotation") (X.elements e)
let child (e : X.el) n = List.find_opt (fun k -> is_xs k n) (children e)
let occurs (e : X.el) =
  let min = match X.attr e "minOccurs" with Some s -> (match int_of_string_opt s with Some n -> n | None -> refuse "minOccurs %S" s) | None -> 1 in
  let max = match X.attr e "maxOccurs" with Some "unbounded" -> None | Some s -> (match int_of_string_opt s with Some n -> Some n | None -> refuse "maxOccurs %S" s) | None -> Some 1 in
  (min, max)

(* An XSD pattern (XML Schema's regular expressions, matched whole) as an ECMA-262 one, which the JSON
   Schema importer translates to RE2: ^ and $ are characters, not anchors; . is any character but CR and
   LF; \d is a Unicode decimal digit, \w anything but punctuation, separators and others, \s the four
   XML white-space characters. \w and \S, and \W, inside a class have no ECMA form here and are refused,
   as are XSD's name classes (\i, \c), its block escapes (\p{Is..}) and class subtraction. *)
let ecma_of_xsd p =
  let n = String.length p and b = Buffer.create (String.length p + 16) in
  let add = Buffer.add_string b in
  let rec go i depth =
    if i < n then
      match p.[i] with
      | '\\' when i + 1 < n -> (
          let c = p.[i + 1] in
          let inside = depth > 0 in
          (match c with
           | 'd' -> add "\\p{Nd}"
           | 'D' -> add "\\P{Nd}"
           | 's' -> add (if inside then " \\t\\n\\r" else "[ \\t\\n\\r]")
           | 'S' -> if inside then refuse "the pattern %S uses \\S in a class" p else add "[^ \\t\\n\\r]"
           | 'w' -> add (if inside then "\\p{L}\\p{M}\\p{N}\\p{S}" else "[\\p{L}\\p{M}\\p{N}\\p{S}]")
           | 'W' -> if inside then refuse "the pattern %S uses \\W in a class" p else add "[^\\p{L}\\p{M}\\p{N}\\p{S}]"
           | 'i' | 'I' | 'c' | 'C' -> refuse "the pattern %S uses XSD's name classes (\\%c)" p c
           | 'p' | 'P' when i + 4 < n && String.sub p (i + 2) 3 = "{Is" -> refuse "the pattern %S uses a Unicode block" p
           | _ -> add (String.sub p i 2));
          go (i + 2) depth)
      | '[' -> add "["; go (i + 1) (depth + 1)
      | ']' when depth > 0 -> add "]"; go (i + 1) (depth - 1)
      | '-' when depth > 0 && i + 1 < n && p.[i + 1] = '[' -> refuse "the pattern %S uses class subtraction" p
      | ('^' | '$') when depth = 0 -> add ("\\" ^ String.make 1 p.[i]); go (i + 1) depth
      | '.' when depth = 0 -> add "[^\\n\\r]"; go (i + 1) depth
      | c -> Buffer.add_char b c; go (i + 1) depth
  in
  go 0 0;
  Buffer.contents b

let import ?root (doc : X.el) : t =
  if not (is_xs doc "schema") then refuse "not an XSD schema";
  let report = ref [] in
  let say loc fmt = Printf.ksprintf (fun m -> report := (loc, m) :: !report) fmt in
  let tns = Option.value (X.attr doc "targetNamespace") ~default:"" in
  let qualified = X.attr doc "elementFormDefault" = Some "qualified" in
  if X.attr doc "attributeFormDefault" = Some "qualified" then refuse "attributeFormDefault qualified";
  let top = children doc in
  List.iter
    (fun (k : X.el) ->
      match k.name with
      | "element" | "complexType" | "simpleType" -> ()
      | ("include" | "import" | "redefine" | "override") as n -> refuse "xs:%s: one schema document only, for now" n
      | ("group" | "attributeGroup" | "attribute" | "notation") as n -> refuse "a top-level xs:%s" n
      | n -> refuse "xs:%s" n)
    top;
  let named kind = List.filter_map (fun (k : X.el) -> if k.name = kind then Option.map (fun n -> (n, k)) (X.attr k "name") else None) top in
  let elements = named "element" and complexes = named "complexType" and simples = named "simpleType" in
  (* a type reference: a builtin, or one of this schema's own *)
  let resolve (e : X.el) q =
    let ns, local = X.qname e q in
    if ns = xs then `Builtin local else if ns = tns then `Own local else refuse "the type %s is in another namespace (%s)" q ns
  in
  let defs = ref [] in
  let add_def name s = if not (List.mem_assoc name !defs) then defs := !defs @ [ (name, s) ] in
  let ref_ name = `Assoc [ ("$ref", `String ("#/$defs/" ^ name)) ] in
  (* ---- simple types ---- *)
  let builtin loc n : (string * J.t) list * lex =
    let i lo hi = [ ("type", `String "integer"); ("minimum", lo); ("maximum", hi) ] in
    let big s = `Intlit s in
    match n with
    | "string" -> ([ ("type", `String "string") ], Str Preserve)
    | "normalizedString" -> ([ ("type", `String "string"); ("pattern", `String "^[^\t\n\r]*$") ], Str Replace)
    | "token" | "anyURI" -> ([ ("type", `String "string"); ("pattern", `String "^([^\t\n\r ]+( [^\t\n\r ]+)*)?$") ], Str Collapse)
    | "boolean" -> ([ ("type", `String "boolean") ], Bool)
    | "integer" -> ([ ("type", `String "integer") ], Int)
    | "nonNegativeInteger" -> ([ ("type", `String "integer"); ("minimum", `Int 0) ], Int)
    | "positiveInteger" -> ([ ("type", `String "integer"); ("minimum", `Int 1) ], Int)
    | "nonPositiveInteger" -> ([ ("type", `String "integer"); ("maximum", `Int 0) ], Int)
    | "negativeInteger" -> ([ ("type", `String "integer"); ("maximum", `Int (-1)) ], Int)
    | "long" -> (i (big "-9223372036854775808") (big "9223372036854775807"), Int)
    | "int" -> (i (`Int (-2147483648)) (`Int 2147483647), Int)
    | "short" -> (i (`Int (-32768)) (`Int 32767), Int)
    | "byte" -> (i (`Int (-128)) (`Int 127), Int)
    | "unsignedLong" -> (i (`Int 0) (big "18446744073709551615"), Int)
    | "unsignedInt" -> (i (`Int 0) (`Int 4294967295), Int)
    | "unsignedShort" -> (i (`Int 0) (`Int 65535), Int)
    | "unsignedByte" -> (i (`Int 0) (`Int 255), Int)
    | "decimal" ->
        say loc "xs:decimal is a double: lossy beyond its 53 bits (RFC 0005, open question 1)";
        ([ ("type", `String "number") ], Dec)
    | "float" | "double" -> ([ ("type", `String "number") ], Dbl)
    | ("dateTime" | "date" | "time" | "duration" | "gYear" | "gYearMonth" | "gMonth" | "gMonthDay" | "gDay") as n ->
        refuse "%s: xs:%s has no CEL form yet (RFC 0005, open question 2)" loc n
    | "anyType" | "anySimpleType" -> refuse "%s: xs:%s" loc n
    | n -> refuse "%s: xs:%s is not read" loc n
  in
  let simple_cache = Hashtbl.create 8 in
  (* a simple type's JSON Schema keywords and lexical kind *)
  let rec simple loc (st : X.el) : (string * J.t) list * lex =
    match children st with
    | [ r ] when r.name = "restriction" ->
        let base, lex =
          match X.attr r "base" with
          | Some b -> named_simple loc r b
          | None -> (match child r "simpleType" with Some s -> simple loc s | None -> refuse "%s: a restriction with no base" loc)
        in
        let facets = List.filter (fun (k : X.el) -> k.name <> "simpleType") (children r) in
        let value (k : X.el) = match X.attr k "value" with Some v -> v | None -> refuse "%s: xs:%s with no value" loc k.name in
        let number (k : X.el) =
          let v = String.trim (value k) in
          match int_of_string_opt v with Some n -> `Int n | None -> (match float_of_string_opt v with Some f -> `Float f | None -> refuse "%s: %s %S" loc k.name v)
        in
        let count k = match int_of_string_opt (value k) with Some n -> `Int n | None -> refuse "%s: %s" loc k.name in
        let enums = List.filter (fun (k : X.el) -> k.name = "enumeration") facets in
        let patterns = List.filter (fun (k : X.el) -> k.name = "pattern") facets in
        let is_list = match lex with Lst _ -> true | _ -> false in
        let kw =
          List.concat_map
            (fun (k : X.el) ->
              match k.name with
              | "enumeration" | "pattern" | "whiteSpace" -> []
              | "length" -> if is_list then [ ("minItems", count k); ("maxItems", count k) ] else [ ("minLength", count k); ("maxLength", count k) ]
              | "minLength" -> [ ((if is_list then "minItems" else "minLength"), count k) ]
              | "maxLength" -> [ ((if is_list then "maxItems" else "maxLength"), count k) ]
              | "minInclusive" -> [ ("minimum", number k) ]
              | "maxInclusive" -> [ ("maximum", number k) ]
              | "minExclusive" -> [ ("exclusiveMinimum", number k) ]
              | "maxExclusive" -> [ ("exclusiveMaximum", number k) ]
              | n -> refuse "%s: the facet %s is not read" loc n)
            facets
        in
        let pattern =
          match patterns with
          | [] -> []
          | ps ->
              if List.mem_assoc "pattern" base then refuse "%s: patterns at two derivation steps" loc;
              let one (k : X.el) = "(?:" ^ (try ecma_of_xsd (value k) with Refused m -> refuse "%s: %s" loc m) ^ ")" in
              [ ("pattern", `String ("^(?:" ^ String.concat "|" (List.map one ps) ^ ")$")) ]
        in
        let enum =
          match enums with
          | [] -> []
          | es ->
              let v (k : X.el) =
                let s = value k in
                match lex with
                | Str _ -> `String s
                | Int -> (match int_of_string_opt (String.trim s) with Some n -> `Int n | None -> refuse "%s: enumeration %S" loc s)
                | Dec | Dbl -> number k
                | Bool | Lst _ -> refuse "%s: an enumeration of %s" loc (if lex = Bool then "booleans" else "lists")
              in
              [ ("enum", `List (List.map v es)) ]
        in
        let override = List.map fst (kw @ pattern @ enum) in
        (List.filter (fun (k, _) -> not (List.mem k override)) base @ kw @ pattern @ enum, lex)
    | [ l ] when l.name = "list" ->
        let items, item_lex =
          match X.attr l "itemType", child l "simpleType" with
          | Some it, _ -> named_simple loc l it
          | None, Some s -> simple loc s
          | None, None -> refuse "%s: a list with no item type" loc
        in
        (match item_lex with Lst _ -> refuse "%s: a list of lists" loc | _ -> ());
        (* a list item holds no space: a string item with one would split on reading *)
        let items = match item_lex with Str _ -> items @ [ ("pattern", `String "^[^\t\n\r ]+$") ] | _ -> items in
        ([ ("type", `String "array"); ("items", `Assoc items) ], Lst item_lex)
    | [ u ] when u.name = "union" -> refuse "%s: xs:union is not read yet" loc
    | _ -> refuse "%s: a simple type is a restriction, a list or a union" loc
  (* a named simple type: a builtin's keywords inline, one of the schema's as its own keywords too (they
     are flattened into a derived type's), registered as a $def *)
  and named_simple loc (at : X.el) q : (string * J.t) list * lex =
    match resolve at q with
    | `Builtin n -> builtin loc n
    | `Own n -> (
        match Hashtbl.find_opt simple_cache n with
        | Some r -> r
        | None -> (
            match List.assoc_opt n simples with
            | Some st ->
                let r = simple n st in
                Hashtbl.replace simple_cache n r;
                add_def n (`Assoc (fst r));
                r
            | None -> if List.mem_assoc n complexes then refuse "%s: %s is a complex type" loc n else refuse "%s: no simple type %s" loc n))
  in
  (* a reference to a simple type, as a schema: a $ref for one of the schema's own, so it is named *)
  let simple_ref loc (at : X.el) q =
    let kw, lex = named_simple loc at q in
    match resolve at q with `Own n -> (ref_ n, lex) | `Builtin _ -> (`Assoc kw, lex)
  in
  (* ---- complex types ---- *)
  let building = ref [] and complex_cache = Hashtbl.create 8 in
  let rec complex_named loc n : J.t * complex =
    match Hashtbl.find_opt complex_cache n with
    | Some c -> (ref_ n, c)
    | None ->
        if List.mem n !building then refuse "%s: the type %s extends a type being defined in it" loc n;
        let ct = match List.assoc_opt n complexes with Some ct -> ct | None -> refuse "%s: no complex type %s" loc n in
        building := n :: !building;
        let schema, c = complex_type n ct in
        building := List.tl !building;
        Hashtbl.replace complex_cache n c;
        add_def n schema;
        (ref_ n, c)
  and complex_type loc (ct : X.el) : J.t * complex =
    if X.attr ct "mixed" = Some "true" then refuse "%s: mixed content" loc;
    if X.attr ct "abstract" = Some "true" then refuse "%s: an abstract type" loc;
    let kids = children ct in
    let attrs_of (owner : X.el) =
      List.filter_map
        (fun (k : X.el) ->
          match k.name with
          | "attribute" -> Some (attribute loc k)
          | "attributeGroup" | "anyAttribute" -> refuse "%s: xs:%s" loc k.name
          | _ -> None)
        (children owner)
    in
    let props_of_attrs attrs = List.map (fun (a, s, req) -> (a.ajson, s, req)) attrs in
    let content_kids = List.filter (fun (k : X.el) -> k.name <> "attribute" && k.name <> "attributeGroup" && k.name <> "anyAttribute") kids in
    let attrs, content, props =
      match content_kids with
      | [] -> (attrs_of ct, Empty, [])
      | [ k ] when k.name = "sequence" || k.name = "all" || k.name = "choice" ->
          let ps, props = particles loc k in
          (attrs_of ct, (if k.name = "all" then All ps else Seq ps), props)
      | [ k ] when k.name = "simpleContent" -> (
          match children k with
          | [ x ] when x.name = "extension" ->
              let base = match X.attr x "base" with Some b -> b | None -> refuse "%s: an extension with no base" loc in
              let base_attrs, vschema, vlex =
                match resolve x base with
                | `Own n when List.mem_assoc n complexes -> (
                    let _, c = complex_named loc n in
                    match c.content with
                    | Value l ->
                        let base_props = match List.assoc n !defs with `Assoc kv -> (match List.assoc_opt "properties" kv with Some (`Assoc ps) -> ps | _ -> []) | _ -> [] in
                        (List.map (fun a -> (a, List.assoc a.ajson base_props, true)) c.attrs, List.assoc "value" base_props, l)
                    | _ -> refuse "%s: simple content extending %s, which has none" loc n)
                | _ ->
                    let s, l = simple_ref loc x base in
                    ([], s, l)
              in
              (base_attrs @ attrs_of x, Value vlex, [ ("value", vschema, true) ])
          | _ -> refuse "%s: simple content is an extension (a restriction is not read)" loc)
      | [ k ] when k.name = "complexContent" -> (
          if X.attr k "mixed" = Some "true" then refuse "%s: mixed content" loc;
          match children k with
          | [ x ] when x.name = "extension" ->
              let base = match X.attr x "base" with Some b -> b | None -> refuse "%s: an extension with no base" loc in
              let bname = match resolve x base with `Own n -> n | `Builtin n -> refuse "%s: extending xs:%s" loc n in
              let _, bc = complex_named loc bname in
              let bprops =
                match List.assoc_opt bname !defs with
                | Some (`Assoc kv) -> (
                    let req = match List.assoc_opt "required" kv with Some (`List r) -> List.filter_map (function `String s -> Some s | _ -> None) r | _ -> [] in
                    match List.assoc_opt "properties" kv with Some (`Assoc ps) -> List.map (fun (p, s) -> (p, s, List.mem p req)) ps | _ -> [])
                | _ -> []
              in
              let bparts = match bc.content with Seq ps -> ps | Empty -> [] | _ -> refuse "%s: extending %s, whose content is not a sequence" loc bname in
              let own_kids = List.filter (fun (k : X.el) -> k.name = "sequence" || k.name = "choice" || k.name = "all") (children x) in
              let ps, props = match own_kids with [] -> ([], []) | [ s ] when s.name = "sequence" -> particles loc s | _ -> refuse "%s: an extension adds a sequence" loc in
              let own_attrs = attrs_of x in
              let battr_props = List.filter (fun (p, _, _) -> List.exists (fun a -> a.ajson = p) bc.attrs) bprops in
              let bpart_props = List.filter (fun (p, _, _) -> not (List.exists (fun a -> a.ajson = p) bc.attrs)) bprops in
              ( List.map (fun a -> (a, (let _, s, _ = List.find (fun (p, _, _) -> p = a.ajson) battr_props in s), (let _, _, r = List.find (fun (p, _, _) -> p = a.ajson) battr_props in r))) bc.attrs @ own_attrs,
                Seq (bparts @ ps),
                bpart_props @ props )
          | _ -> refuse "%s: complex content is an extension (a restriction is not read)" loc)
      | k :: _ -> refuse "%s: xs:%s as content" loc k.name
    in
    let attr_props = props_of_attrs attrs in
    let all_props = attr_props @ props in
    let names = List.map (fun (p, _, _) -> p) all_props in
    if List.length (List.sort_uniq compare names) <> List.length names then refuse "%s: an attribute and an element share a name" loc;
    let schema =
      `Assoc
        [ ("type", `String "object"); ("properties", `Assoc (List.map (fun (p, s, _) -> (p, s)) all_props));
          ("required", `List (List.filter_map (fun (p, _, r) -> if r then Some (`String p) else None) all_props));
          ("additionalProperties", `Bool false) ]
    in
    (schema, { attrs = List.map (fun (a, _, _) -> a) attrs; content })
  and attribute loc (a : X.el) : attr * J.t * bool =
    let name = match X.attr a "name" with Some n -> n | None -> refuse "%s: an attribute by ref" loc in
    if X.attr a "form" = Some "qualified" then refuse "%s: a qualified attribute" loc;
    let use = Option.value (X.attr a "use") ~default:"optional" in
    if use = "prohibited" then refuse "%s: a prohibited attribute" loc;
    let s, lex =
      match X.attr a "type", child a "simpleType" with
      | Some t, _ -> simple_ref (loc ^ "@" ^ name) a t
      | None, Some st -> let kw, l = simple (loc ^ "@" ^ name) st in (`Assoc kw, l)
      | None, None -> refuse "%s@%s: an attribute of no type (anySimpleType)" loc name
    in
    let fixed = X.attr a "fixed" in
    let default = match fixed with Some f -> Some f | None -> X.attr a "default" in
    let s = match fixed with Some f -> `Assoc [ ("allOf", `List [ s; `Assoc [ ("const", `String f) ] ]) ] | None -> s in
    (match fixed, lex with Some _, Str _ -> () | Some _, _ -> refuse "%s@%s: a fixed value of a type other than a string" loc name | None, _ -> ());
    ({ aname = name; ajson = name; alex = lex; adefault = default }, s, use = "required" || default <> None)
  (* a sequence's, all's or choice's particles, and their properties *)
  and particles loc (g : X.el) : particle list * (string * J.t * bool) list =
    let gmin, gmax = occurs g in
    let parts =
      if g.name = "choice" then [ g ]
      else begin
        if gmin <> 1 || gmax <> Some 1 then refuse "%s: a %s that repeats" loc g.name;
        (* a nested sequence that occurs once is its particles in place *)
        List.concat_map
          (fun (k : X.el) ->
            match k.name with
            | "sequence" when g.name = "sequence" && occurs k = (1, Some 1) -> children k
            | _ -> [ k ])
          (children g)
      end
    in
    let nth = ref 0 in
    List.split
      (List.map
         (fun (k : X.el) ->
           match k.name with
           | "element" ->
               let e, s = element loc k in
               let s, req = arrayed s e.min e.max in
               (Elem e, (e.ejson, s, req))
           | "choice" ->
               incr nth;
               let cmin, cmax = occurs k in
               let cases =
                 List.map
                   (fun (c : X.el) ->
                     if c.name <> "element" then refuse "%s: a choice of xs:%s" loc c.name;
                     let e, s = element loc c in
                     if e.min <> 1 || e.max <> Some 1 then refuse "%s: a choice's element %s that repeats" loc e.ename;
                     (e, s))
                   (children k)
               in
               let json = if !nth = 1 then "choice" else Printf.sprintf "choice%d" !nth in
               let branch (e, s) =
                 `Assoc
                   [ ("type", `String "object");
                     ("properties", `Assoc [ ("kind", `Assoc [ ("const", `String e.ename) ]); ("value", s) ]);
                     ("required", `List [ `String "kind"; `String "value" ]); ("additionalProperties", `Bool false) ]
               in
               let s, req = arrayed (`Assoc [ ("oneOf", `List (List.map branch cases)) ]) cmin cmax in
               (Choice { cjson = json; cmin; cmax; cases = List.map fst cases }, (json, s, req))
           | ("any" | "group") as n -> refuse "%s: xs:%s" loc n
           | n -> refuse "%s: xs:%s in a %s" loc n g.name)
         parts)
  (* a particle that may repeat is a list, always present: no elements is the empty list, so the type has
     one value for it (option(list) would have two) *)
  and arrayed s min max =
    match max with
    | Some 1 -> (s, min >= 1)
    | _ ->
        ( `Assoc
            ([ ("type", `String "array"); ("items", s) ] @ (if min > 0 then [ ("minItems", `Int min) ] else [])
            @ match max with Some n -> [ ("maxItems", `Int n) ] | None -> []),
          true )
  and element loc (k : X.el) : elem * J.t =
    if X.attr k "nillable" = Some "true" then refuse "%s: nillable" loc;
    if X.attr k "default" <> None || X.attr k "fixed" <> None then refuse "%s: an element's default or fixed value" loc;
    if X.attr k "substitutionGroup" <> None || X.attr k "abstract" = Some "true" then refuse "%s: substitution groups" loc;
    let min, max = occurs k in
    if max = Some 0 then refuse "%s: maxOccurs 0" loc;
    match X.attr k "ref" with
    | Some r ->
        let ns, n = X.qname k r in
        if ns <> tns then refuse "%s: an element of another namespace (%s)" loc ns;
        let g = match List.assoc_opt n elements with Some g -> g | None -> refuse "%s: no global element %s" loc n in
        let e, s = element_type n g in
        ({ e with min; max; ens = tns }, s)
    | None ->
        let name = match X.attr k "name" with Some n -> n | None -> refuse "%s: an element with no name" loc in
        let q = match X.attr k "form" with Some "qualified" -> true | Some _ -> false | None -> qualified in
        let e, s = element_type (loc ^ "_" ^ name) k in
        ({ e with ename = name; ejson = name; ens = (if q then tns else ""); min; max }, s)
  (* an element's type: its shape and schema *)
  and element_type loc (k : X.el) : elem * J.t =
    let name = Option.value (X.attr k "name") ~default:loc in
    let shape, s =
      match X.attr k "type", child k "complexType", child k "simpleType" with
      | Some t, _, _ -> (
          match resolve k t with
          | `Builtin n -> let kw, l = builtin loc n in (Simple l, `Assoc kw)
          (* a type referred to inside itself: a recursive type (RFC 0002 revision 3), by name *)
          | `Own n when List.mem_assoc n complexes && List.mem n !building -> (Ref n, ref_ n)
          | `Own n when List.mem_assoc n complexes -> let s, c = complex_named loc n in (Complex c, s)
          | `Own _ -> let s, l = simple_ref loc k t in (Simple l, s))
      | None, Some ct, _ -> let s, c = complex_type loc ct in (Complex c, s)
      | None, None, Some st -> let kw, l = simple loc st in (Simple l, `Assoc kw)
      | None, None, None -> refuse "%s: an element of no type (anyType)" loc
    in
    List.iter (fun (c : X.el) -> if List.mem c.name [ "key"; "keyref"; "unique" ] then say loc "xs:%s is not checked" c.name) (children k);
    ({ ename = name; ens = tns; ejson = name; min = 1; max = Some 1; shape }, s)
  in
  let rname, rel =
    match root, elements with
    | Some r, _ -> (match List.assoc_opt r elements with Some e -> (r, e) | None -> refuse "no global element %s" r)
    | None, [ (n, e) ] -> (n, e)
    | None, [] -> refuse "no global element"
    | None, (n, e) :: _ -> say "root" "the first global element, %s, is the document element (--root names another)" n; (n, e)
  in
  let e, s = element_type rname rel in
  (* the document element's type is a $def: named after its type, or after the element *)
  let key = match s with `Assoc [ ("$ref", `String r) ] -> String.sub r 8 (String.length r - 8) | _ -> add_def (String.capitalize_ascii rname) s; String.capitalize_ascii rname in
  let uri = "urn:cordis:xsd" in
  let synthetic = `Assoc [ ("$schema", `String "https://json-schema.org/draft/2020-12/schema"); ("$defs", `Assoc !defs) ] in
  let imp = try Jsonschema.import ~name:"XsdRoot" ~uri synthetic with Jsonschema.Refused m | Schema_pred.Unsupported m -> refuse "%s" m in
  let imp =
    { imp with
      defs = List.filter (fun (n, _) -> n <> imp.root) imp.defs;
      residue = List.filter (fun (n, _) -> n <> imp.root) imp.residue;
      local = List.filter (fun (n, _) -> n <> imp.root) imp.local }
  in
  let row = match List.assoc_opt (uri ^ "#/$defs/" ^ key) imp.by_def with Some n -> n | None -> refuse "no type for %s" key in
  (* an element whose type holds it where it is required has no finite document *)
  (match List.find_opt (fun (_, m) -> String.length m > 30 && String.sub m 0 30 = "a required recursive reference") imp.report with
   | Some (loc, _) -> refuse "%s: a recursive element that is required: no document is finite" loc
   | None -> ());
  let named = List.of_seq (Hashtbl.to_seq complex_cache) |> List.sort compare in
  { imp; root = (tns, rname); row; shape = e.shape; named; tns; qualified; report = List.rev !report @ imp.report; schema = synthetic }

let of_string ?root s =
  match X.parse s with
  | doc -> import ?root doc
  | exception X.Bad m -> refuse "the schema is not XML: %s" m

(* the name map: the types', as the JSON Schema importer writes it, and the shape the XML codec reads by *)
let name_map (t : t) : J.t =
  let rec lex = function
    | Str Preserve -> "string" | Str Replace -> "string (whitespace replaced)" | Str Collapse -> "string (whitespace collapsed)"
    | Int -> "integer" | Dec -> "decimal" | Dbl -> "double" | Bool -> "boolean" | Lst l -> "list of " ^ lex l
  in
  let occ min max = `String (Printf.sprintf "%d..%s" min (match max with Some n -> string_of_int n | None -> "*")) in
  let rec shape = function
    | Simple l -> `Assoc [ ("simple", `String (lex l)) ]
    | Ref n -> `Assoc [ ("ref", `String n) ]
    | Complex c ->
        `Assoc
          [ ( "attributes",
              `List
                (List.map
                   (fun a -> `Assoc ([ ("name", `String a.aname); ("type", `String (lex a.alex)) ] @ match a.adefault with Some d -> [ ("default", `String d) ] | None -> []))
                   c.attrs) );
            ( "content",
              match c.content with
              | Empty -> `String "empty"
              | Value l -> `Assoc [ ("value", `String (lex l)) ]
              | Seq ps -> `Assoc [ ("sequence", `List (List.map particle ps)) ]
              | All ps -> `Assoc [ ("all", `List (List.map particle ps)) ] ) ]
  and elem e = `Assoc [ ("element", `String e.ename); ("namespace", `String e.ens); ("occurs", occ e.min e.max); ("type", shape e.shape) ]
  and particle = function
    | Elem e -> elem e
    | Choice c -> `Assoc [ ("choice", `String c.cjson); ("occurs", occ c.cmin c.cmax); ("cases", `List (List.map elem c.cases)) ]
  in
  `Assoc
    ([ ("root", `Assoc [ ("namespace", `String (fst t.root)); ("element", `String (snd t.root)); ("type", `String t.row) ]);
      ("elementFormDefault", `String (if t.qualified then "qualified" else "unqualified")); ("shape", shape t.shape) ]
    @ (* the recursive types, which a ref names *)
    (let rec refs = function
       | Ref n -> [ n ]
       | Simple _ -> []
       | Complex c -> (match c.content with Seq ps | All ps -> List.concat_map parts ps | _ -> [])
     and parts = function Elem (e : elem) -> refs e.shape | Choice c -> List.concat_map (fun (e : elem) -> refs e.shape) c.cases in
     let named = List.sort_uniq compare (refs t.shape @ List.concat_map (fun (_, c) -> refs (Complex c)) t.named) in
     if named = [] then [] else [ ("recursive", `Assoc (List.map (fun n -> (n, shape (Complex (List.assoc n t.named)))) named)) ])
    @ [
      ("types", Jsonschema.name_map t.imp) ])
