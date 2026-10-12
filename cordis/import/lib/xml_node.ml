(* Schemaless XML (docs/cordis/rfc/0005-xml-csv-frames.md, revision 5): a
   document with no XSD is read into a library type for an XML node, which
   is recursive (RFC 0002 revision 3), and written back from it.

     type XmlNode = Element(XmlElement) | Text(string) | Comment(string) | Pi(XmlPi);
     type XmlElement = { ns; name; attrs : list(XmlAttr); kids : list(XmlNode) };
     type XmlAttr = { ns; name; value };
     type XmlPi = { target; data };

   Reading keeps everything a document says inside its root element but its
   namespace declarations: elements, attributes in document order, text
   (adjacent text, CDATA and references as one node; white space between
   elements kept), comments and processing instructions. A DTD is refused,
   as for every read (Xml_tree). Writing is canonical (namespaces declared
   where they change, an attribute's namespace by a generated prefix) and
   refuses what XML cannot hold: a name that is not one, -- in a comment,
   ?> in a processing instruction, a character XML 1.0 has no form for. A
   value read is canonical: no empty text and no two texts side by side
   ([canonical] makes a value so). *)

module X = Xml_tree
module V = Cordis_cel.Value

(* the library type, in the graph language: a document's element, and its nodes, hold at most 65,536 *)
let types =
  {|type XmlNode = Element(XmlElement) | Text(string) | Comment(string) | Pi(XmlPi) <nodes 65536>;
type XmlElement = { ns : string; name : string; attrs : list(XmlAttr); kids : list(XmlNode); } <nodes 65536>;
type XmlAttr = { ns : string; name : string; value : string; };
type XmlPi = { target : string; data : string; };
|}

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt
let record fs = V.make_map (List.map (fun (k, v) -> (V.String k, v)) fs)
let case tag v = record [ ("tag", V.String tag); ("value", v) ]

(* ---- reading ---------------------------------------------------------------------------------------- *)

let rec of_el (e : X.el) =
  record
    [ ("ns", V.String e.ns); ("name", V.String e.name);
      ("attrs", V.List (Array.of_list (List.map (fun ((ns, n), v) -> record [ ("ns", V.String ns); ("name", V.String n); ("value", V.String v) ]) e.attrs)));
      ("kids", V.List (Array.of_list (List.map of_node e.kids))) ]

and of_node = function
  | X.El e -> case "Element" (of_el e)
  | X.Text t -> case "Text" (V.String t)
  | X.Comment c -> case "Comment" (V.String c)
  | X.Pi (t, d) -> case "Pi" (record [ ("target", V.String t); ("data", V.String d) ])

let read ?limits doc = match X.parse ?limits ~misc:true doc with e -> Ok (of_el e) | exception X.Bad m -> Error m

(* ---- the value's parts ------------------------------------------------------------------------------ *)

let field v k = match v with V.Map m -> (match V.map_find m (V.String k) with Some x -> x | None -> bad "no field %s" k) | _ -> bad "not a record: %s" (V.to_string v)
let str v k = match field v k with V.String s -> s | x -> bad "%s is not a string: %s" k (V.to_string x)
let list v k = match field v k with V.List xs -> Array.to_list xs | x -> bad "%s is not a list: %s" k (V.to_string x)

let tag_of v = match field v "tag" with V.String t -> t | x -> bad "a node's tag: %s" (V.to_string x)

(* no empty text, and adjacent texts as one: what reading gives *)
let rec canonical v =
  let kids =
    List.fold_left
      (fun acc k ->
        match tag_of k, acc with
        | "Text", _ when str k "value" = "" -> acc
        | "Text", prev :: rest when tag_of prev = "Text" -> case "Text" (V.String (str prev "value" ^ str k "value")) :: rest
        | "Element", _ -> case "Element" (canonical (field k "value")) :: acc
        | _ -> k :: acc)
      [] (list v "kids")
  in
  record [ ("ns", field v "ns"); ("name", field v "name"); ("attrs", field v "attrs"); ("kids", V.List (Array.of_list (List.rev kids))) ]

(* ---- writing ---------------------------------------------------------------------------------------- *)

(* an XML name without a colon (an NCName): letters, _, and anything past ASCII first; then digits, . and -
   too. Past ASCII it is lenient: XML's name classes there are wide, and this does not check them. *)
let ncname s =
  s <> ""
  && (match s.[0] with 'a' .. 'z' | 'A' .. 'Z' | '_' -> true | c -> Char.code c >= 0x80)
  && String.for_all (function 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' | '_' | '-' | '.' -> true | c -> Char.code c >= 0x80) s
  && String.is_valid_utf_8 s

let text_ok what s = if not (String.is_valid_utf_8 s && X.writable s) then bad "%s holds a character XML 1.0 cannot" what

let contains s sub =
  let n = String.length sub in
  let rec go i = i + n <= String.length s && (String.sub s i n = sub || go (i + 1)) in
  go 0

let write v =
  match
    let b = Buffer.create 256 in
    let add = Buffer.add_string b in
    add "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    let rec el ~default e =
      let ns = str e "ns" and name = str e "name" in
      if not (ncname name) then bad "an element's name %S" name;
      if ns = X.ns_xml || ns = X.ns_xmlns then bad "an element in the namespace %s" ns;
      add ("<" ^ name);
      if ns <> default then add (Printf.sprintf " xmlns=\"%s\"" (X.escape ~attr:true ns));
      let attrs = list e "attrs" in
      let prefixes = ref [] in
      let seen = Hashtbl.create 4 in
      List.iter
        (fun a ->
          let ans = str a "ns" and an = str a "name" and av = str a "value" in
          if not (ncname an) then bad "an attribute's name %S" an;
          if Hashtbl.mem seen (ans, an) then bad "the attribute {%s}%s twice" ans an;
          Hashtbl.add seen (ans, an) ();
          if ans = X.ns_xmlns || (ans = "" && an = "xmlns") then bad "a namespace declaration as an attribute";
          text_ok "an attribute's value" av;
          let q =
            if ans = "" then an
            else if ans = X.ns_xml then "xml:" ^ an
            else
              let p =
                match List.assoc_opt ans !prefixes with
                | Some p -> p
                | None ->
                    let p = Printf.sprintf "a%d" (List.length !prefixes + 1) in
                    prefixes := (ans, p) :: !prefixes;
                    add (Printf.sprintf " xmlns:%s=\"%s\"" p (X.escape ~attr:true ans));
                    p
              in
              p ^ ":" ^ an
          in
          add (Printf.sprintf " %s=\"%s\"" q (X.escape ~attr:true av)))
        attrs;
      match list e "kids" with
      | [] -> add "/>"
      | kids ->
          add ">";
          List.iter
            (fun k ->
              match tag_of k with
              | "Element" -> el ~default:ns (field k "value")
              | "Text" -> let t = str k "value" in text_ok "text" t; add (X.escape ~attr:false t)
              | "Comment" ->
                  let c = str k "value" in
                  text_ok "a comment" c;
                  (* a comment and a processing instruction hold no reference, so a CR would read as LF *)
                  if String.contains c '\r' then bad "a CR in a comment";
                  if contains c "--" || (c <> "" && c.[String.length c - 1] = '-') then bad "-- in a comment";
                  add ("<!--" ^ c ^ "-->")
              | "Pi" ->
                  let p = field k "value" in
                  let t = str p "target" and d = str p "data" in
                  if not (ncname t) || String.lowercase_ascii t = "xml" then bad "a processing instruction's target %S" t;
                  text_ok "a processing instruction" d;
                  if String.contains d '\r' then bad "a CR in a processing instruction";
                  if contains d "?>" then bad "?> in a processing instruction";
                  (* white space before the data separates it from the target, and is not kept *)
                  if d <> "" && (d.[0] = ' ' || d.[0] = '\t' || d.[0] = '\n' || d.[0] = '\r') then bad "a processing instruction's data begins with white space";
                  add ("<?" ^ t ^ (if d = "" then "" else " " ^ d) ^ "?>")
              | t -> bad "a node %s" t)
            kids;
          add ("</" ^ name ^ ">")
    in
    el ~default:"" v;
    Buffer.contents b
  with
  | s -> Ok s
  | exception Bad m -> Error m
