(* XML read into a tree (docs/cordis/rfc/0005-xml-csv-frames.md, "The
   codec"), by a parser of its own: XML 1.0 with namespaces, strictly. No
   DTD (a document carrying one is refused, so there are no external
   entities and no entity expansion): the only references are the five
   predefined entities and character references. Line ends are normalised
   as the spec says (CR LF and CR to LF), and attribute values as CDATA
   attributes' are (a literal tab, LF or CR becomes a space; a reference
   stays what it names), so a value written with references reads back as
   itself. (Xmlm, which revision 2 named, collapses every attribute value,
   so a string attribute could not hold two spaces.) The size, depth and
   attribute count are limited by policy. Each element keeps its namespace
   bindings in scope, so a QName in an attribute's value (an XSD type
   reference) can be resolved. *)

type node =
  | El of el
  | Text of string
  | Comment of string  (** kept only when asked ([parse ~misc:true]): a schemaless read *)
  | Pi of string * string  (** a processing instruction: target, data; likewise *)

and el = {
  ns : string;
  name : string;
  attrs : ((string * string) * string) list;  (** (namespace, local name), value; no xmlns declarations *)
  scope : (string * string) list;  (** prefix -> namespace URI in scope ("" the default) *)
  kids : node list;
}

type limits = { max_bytes : int; max_depth : int; max_attrs : int }

let default_limits = { max_bytes = 16 * 1024 * 1024; max_depth = 64; max_attrs = 256 }

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt
let ns_xml = "http://www.w3.org/XML/1998/namespace"
let ns_xmlns = "http://www.w3.org/2000/xmlns/"

let parse ?(limits = default_limits) ?(misc = false) (src : string) : el =
  let keep = misc in
  if String.length src > limits.max_bytes then bad "the document is over %d bytes" limits.max_bytes;
  (* line ends: CR LF and CR are LF *)
  let s =
    let b = Buffer.create (String.length src) in
    let n = String.length src in
    let i = ref 0 in
    while !i < n do
      (match src.[!i] with
       | '\r' -> Buffer.add_char b '\n'; if !i + 1 < n && src.[!i + 1] = '\n' then incr i
       | c -> Buffer.add_char b c);
      incr i
    done;
    Buffer.contents b
  in
  let n = String.length s and i = ref 0 in
  let line () = let l = ref 1 in for k = 0 to min (n - 1) (!i - 1) do if s.[k] = '\n' then incr l done; !l in
  let fail fmt = Printf.ksprintf (fun m -> bad "line %d: %s" (line ()) m) fmt in
  (* every character a valid UTF-8 one XML 1.0 allows *)
  let k = ref 0 in
  while !k < n do
    let c = Char.code s.[!k] in
    let len, cp =
      if c < 0x80 then (1, c)
      else if c land 0xE0 = 0xC0 && c >= 0xC2 then (2, c land 0x1F)
      else if c land 0xF0 = 0xE0 then (3, c land 0x0F)
      else if c land 0xF8 = 0xF0 && c <= 0xF4 then (4, c land 0x07)
      else (i := !k; fail "not UTF-8")
    in
    if !k + len > n then (i := !k; fail "not UTF-8");
    let cp = ref cp in
    for j = 1 to len - 1 do
      let b = Char.code s.[!k + j] in
      if b land 0xC0 <> 0x80 then (i := !k; fail "not UTF-8");
      cp := (!cp lsl 6) lor (b land 0x3F)
    done;
    let cp = !cp in
    if not (cp = 0x9 || cp = 0xA || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF)) then (
      i := !k;
      fail "a character XML 1.0 does not allow (U+%04X)" cp);
    k := !k + len
  done;
  let at t = !i + String.length t <= n && String.sub s !i (String.length t) = t in
  let skip_ws () = while !i < n && (s.[!i] = ' ' || s.[!i] = '\t' || s.[!i] = '\n') do incr i done in
  let expect t = if at t then i := !i + String.length t else fail "expected %S" t in
  let until t what =
    match
      let rec find j = if j + String.length t > n then None else if String.sub s j (String.length t) = t then Some j else find (j + 1) in
      find !i
    with
    | Some j -> let r = String.sub s !i (j - !i) in i := j + String.length t; r
    | None -> fail "%s is not closed" what
  in
  let name_char c = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c = '_' || c = '-' || c = '.' || c = ':' || Char.code c >= 0x80 in
  let name () =
    let st = !i in
    if !i < n && (match s.[!i] with '0' .. '9' | '-' | '.' -> false | c -> name_char c) then
      while !i < n && name_char s.[!i] do incr i done;
    if !i = st then fail "expected a name";
    String.sub s st (!i - st)
  in
  (* a reference, after the ampersand *)
  let reference b =
    let r = until ";" "a reference" in
    let utf8 cp =
      if cp < 0x80 then Buffer.add_char b (Char.chr cp)
      else if cp < 0x800 then (Buffer.add_char b (Char.chr (0xC0 lor (cp lsr 6))); Buffer.add_char b (Char.chr (0x80 lor (cp land 0x3F))))
      else if cp < 0x10000 then (
        Buffer.add_char b (Char.chr (0xE0 lor (cp lsr 12)));
        Buffer.add_char b (Char.chr (0x80 lor ((cp lsr 6) land 0x3F)));
        Buffer.add_char b (Char.chr (0x80 lor (cp land 0x3F))))
      else (
        Buffer.add_char b (Char.chr (0xF0 lor (cp lsr 18)));
        Buffer.add_char b (Char.chr (0x80 lor ((cp lsr 12) land 0x3F)));
        Buffer.add_char b (Char.chr (0x80 lor ((cp lsr 6) land 0x3F)));
        Buffer.add_char b (Char.chr (0x80 lor (cp land 0x3F))))
    in
    match r with
    | "lt" -> Buffer.add_char b '<'
    | "gt" -> Buffer.add_char b '>'
    | "amp" -> Buffer.add_char b '&'
    | "apos" -> Buffer.add_char b '\''
    | "quot" -> Buffer.add_char b '"'
    | r when String.length r > 1 && r.[0] = '#' ->
        let v = if r.[1] = 'x' then int_of_string_opt ("0x" ^ String.sub r 2 (String.length r - 2)) else int_of_string_opt (String.sub r 1 (String.length r - 1)) in
        (match v with
         | Some cp when cp = 0x9 || cp = 0xA || cp = 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF) -> utf8 cp
         | _ -> fail "the character reference &%s; names no XML character" r)
    | r -> fail "the entity &%s; (only XML's five, and character references)" r
  in
  (* comments, processing instructions and white space between markup *)
  let rec misc () =
    skip_ws ();
    if at "<!--" then (i := !i + 4; ignore (until "-->" "a comment"); misc ())
    else if at "<?" then (i := !i + 2; let t = name () in if String.lowercase_ascii t = "xml" then fail "an XML declaration not at the start"; ignore (until "?>" "a processing instruction"); misc ())
    else if at "<!DOCTYPE" then fail "a DTD is not read (no entities beyond XML's own)"
  in
  let rec element depth scope =
    if depth > limits.max_depth then fail "deeper than %d" limits.max_depth;
    expect "<";
    let tag = name () in
    let raw = ref [] in
    let rec attrs () =
      let had_ws = !i < n && (s.[!i] = ' ' || s.[!i] = '\t' || s.[!i] = '\n') in
      skip_ws ();
      if at "/>" || at ">" then ()
      else begin
        if not had_ws then fail "white space before an attribute";
        let a = name () in
        skip_ws ();
        expect "=";
        skip_ws ();
        let q = if !i < n && (s.[!i] = '"' || s.[!i] = '\'') then s.[!i] else fail "an attribute value is quoted" in
        incr i;
        let b = Buffer.create 16 in
        while !i < n && s.[!i] <> q do
          (match s.[!i] with
           | '<' -> fail "< in an attribute value"
           | '&' -> incr i; reference b; decr i
           | '\t' | '\n' -> Buffer.add_char b ' '
           | c -> Buffer.add_char b c);
          incr i
        done;
        if !i >= n then fail "an attribute value is not closed";
        incr i;
        if List.mem_assoc a !raw then fail "the attribute %s is given twice" a;
        raw := (a, Buffer.contents b) :: !raw;
        if List.length !raw > limits.max_attrs then fail "more than %d attributes" limits.max_attrs;
        attrs ()
      end
    in
    attrs ();
    let raw = List.rev !raw in
    let decls =
      List.filter_map
        (fun (a, v) ->
          if a = "xmlns" then Some ("", v)
          else if String.length a > 6 && String.sub a 0 6 = "xmlns:" then Some (String.sub a 6 (String.length a - 6), v)
          else None)
        raw
    in
    let scope = decls @ List.filter (fun (p, _) -> not (List.mem_assoc p decls)) scope in
    let split q = match String.index_opt q ':' with Some k -> (String.sub q 0 k, String.sub q (k + 1) (String.length q - k - 1)) | None -> ("", q) in
    let resolve ~attr q =
      let p, l = split q in
      if p = "" then ((if attr then "" else Option.value (List.assoc_opt "" scope) ~default:""), l)
      else match List.assoc_opt p scope with Some u -> (u, l) | None -> fail "the prefix %s is not declared" p
    in
    let ns, local = resolve ~attr:false tag in
    let attrs =
      List.filter_map
        (fun (a, v) -> if a = "xmlns" || (String.length a > 6 && String.sub a 0 6 = "xmlns:") then None else Some (resolve ~attr:true a, v))
        raw
    in
    let rec dup = function [] -> () | (k, _) :: rest -> if List.mem_assoc k rest then fail "the attribute {%s}%s is given twice" (fst k) (snd k) else dup rest in
    dup attrs;
    if at "/>" then (i := !i + 2; { ns; name = local; attrs; scope; kids = [] })
    else begin
      expect ">";
      let kids = ref [] and text = Buffer.create 16 in
      let flush () = if Buffer.length text > 0 then (kids := Text (Buffer.contents text) :: !kids; Buffer.clear text) in
      let closed = ref false in
      while not !closed do
        if !i >= n then fail "the element %s is not closed" tag
        else if at "</" then (
          i := !i + 2;
          let t = name () in
          if t <> tag then fail "</%s> closes <%s>" t tag;
          skip_ws ();
          expect ">";
          closed := true)
        else if at "<!--" then (
          i := !i + 4;
          let c = until "-->" "a comment" in
          (* XML forbids -- in a comment, and one ending in - *)
          let rec dashes k = k + 1 < String.length c && ((c.[k] = '-' && c.[k + 1] = '-') || dashes (k + 1)) in
          if dashes 0 || (c <> "" && c.[String.length c - 1] = '-') then fail "-- in a comment";
          if keep then (flush (); kids := Comment c :: !kids))
        else if at "<![CDATA[" then (i := !i + 9; Buffer.add_string text (until "]]>" "a CDATA section"))
        else if at "<?" then (
          i := !i + 2;
          let p = until "?>" "a processing instruction" in
          let k = ref 0 in
          while !k < String.length p && not (p.[!k] = ' ' || p.[!k] = '\t' || p.[!k] = '\n') do incr k done;
          let target = String.sub p 0 !k in
          if target = "" || String.lowercase_ascii target = "xml" then fail "a processing instruction's target %S" target;
          let j = ref !k in
          while !j < String.length p && (p.[!j] = ' ' || p.[!j] = '\t' || p.[!j] = '\n') do incr j done;
          if keep then (flush (); kids := Pi (target, String.sub p !j (String.length p - !j)) :: !kids))
        else if at "<!" then fail "a declaration inside the document"
        else if at "<" then (flush (); kids := El (element (depth + 1) scope) :: !kids)
        else if at "]]>" then fail "]]> in character data"
        else if s.[!i] = '&' then (incr i; reference text)
        else (Buffer.add_char text s.[!i]; incr i)
      done;
      flush ();
      { ns; name = local; attrs; scope; kids = List.rev !kids }
    end
  in
  if at "<?xml" && (n = 5 || s.[5] = ' ' || s.[5] = '\t' || s.[5] = '\n' || s.[5] = '?') then begin
    i := 5;
    let decl = until "?>" "the XML declaration" in
    let has t = Schema_pred.replace ~sub:t ~by:"" decl <> decl in
    if not (has "version=\"1.0\"" || has "version='1.0'") then fail "an XML version other than 1.0";
    if has "encoding=" && not (has "UTF-8" || has "utf-8") then fail "an encoding other than UTF-8"
  end;
  misc ();
  if !i >= n || s.[!i] <> '<' then fail "no root element";
  let root = element 1 [ ("xml", ns_xml) ] in
  misc ();
  if !i < n then fail "content after the root element";
  root

(* the element children, and whether there is text that is not white space between them *)
let elements (e : el) = List.filter_map (function El x -> Some x | _ -> None) e.kids
let text (e : el) = String.concat "" (List.filter_map (function Text t -> Some t | _ -> None) e.kids)
let blank s = String.for_all (fun c -> c = ' ' || c = '\t' || c = '\n' || c = '\r') s
let attr (e : el) name = List.assoc_opt ("", name) e.attrs

(* a QName in an attribute's value, resolved in the element's scope: (namespace, local name) *)
let qname (e : el) q =
  match String.index_opt q ':' with
  | Some k -> (
      let p = String.sub q 0 k and l = String.sub q (k + 1) (String.length q - k - 1) in
      match List.assoc_opt p e.scope with Some u -> (u, l) | None -> bad "the prefix %s is not declared" p)
  | None -> (Option.value (List.assoc_opt "" e.scope) ~default:"", q)

(* ---- writing ----------------------------------------------------------------------------------------- *)

(* XML 1.0's characters: a value holding another (a control character) cannot be written *)
(* every code point one XML 1.0 has a form for (its Char production): not the C0 controls but tab, LF and
   CR, not U+FFFE or U+FFFF; and valid UTF-8 *)
let writable s =
  String.is_valid_utf_8 s
  &&
  let rec go i =
    i >= String.length s
    ||
    let d = String.get_utf_8_uchar s i in
    let cp = Uchar.to_int (Uchar.utf_decode_uchar d) in
    (cp = 0x9 || cp = 0xA || cp = 0xD || (cp >= 0x20 && cp <> 0xFFFE && cp <> 0xFFFF)) && go (i + Uchar.utf_decode_length d)
  in
  go 0

let escape ~attr s =
  let b = Buffer.create (String.length s) in
  String.iter
    (function
      | '&' -> Buffer.add_string b "&amp;"
      | '<' -> Buffer.add_string b "&lt;"
      | '>' -> Buffer.add_string b "&gt;"
      | '"' when attr -> Buffer.add_string b "&quot;"
      (* a CR would be read as a line end, and in an attribute tab and LF as spaces: written as references *)
      | '\r' -> Buffer.add_string b "&#13;"
      | '\t' when attr -> Buffer.add_string b "&#9;"
      | '\n' when attr -> Buffer.add_string b "&#10;"
      | c -> Buffer.add_char b c)
    s;
  Buffer.contents b
