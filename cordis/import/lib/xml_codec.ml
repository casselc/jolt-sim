(* XML documents and values of an imported XSD's types (docs/cordis/rfc/
   0005-xml-csv-frames.md, revision 4, "The codec").

   Reading is strict, by the shape the import built: the document element
   is the schema's; an attribute or element the type does not name is
   refused, and so is a sequence out of order, an element occurring more
   or fewer times than it may, text where only elements may be, and a
   value outside its lexical space. A value is read as XSD reads it (its
   whitespace replaced or collapsed first, as its type says); then the JSON
   Schema codec reads the whole strictly, its residue included. The parser
   reads no DTD (xml_tree.ml).

   Writing is canonical: the declaration, the target namespace declared
   once at the root (the default one when every element is qualified),
   elements in sequence order, attributes sorted, UTF-8, no indentation; a
   CR, and in attributes a tab or LF, as a character reference, so it
   reads back as itself. A value XML 1.0 cannot hold (a control
   character) is refused. *)

module J = Yojson.Safe
module V = Cordis_cel.Value
module X = Xml_tree
module S = Xsd
module C = Jsonschema_codec

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt

(* ---- lexical forms ----------------------------------------------------------------------------------- *)

let replace_ws s = String.map (function '\t' | '\n' | '\r' -> ' ' | c -> c) s

let collapse s =
  String.split_on_char ' ' (replace_ws s) |> List.filter (( <> ) "") |> String.concat " "

let integer s =
  let n = String.length s in
  let st = if n > 0 && (s.[0] = '+' || s.[0] = '-') then 1 else 0 in
  n > st && String.for_all (fun c -> c >= '0' && c <= '9') (String.sub s st (n - st))

(* [+-]digits, without the plus and leading zeros *)
let canonical_integer s =
  let neg = s.[0] = '-' in
  let digits = if s.[0] = '+' || s.[0] = '-' then String.sub s 1 (String.length s - 1) else s in
  let k = ref 0 in
  while !k < String.length digits - 1 && digits.[!k] = '0' do incr k done;
  let d = String.sub digits !k (String.length digits - !k) in
  if neg && d <> "0" then "-" ^ d else d

let decimal_lexical ~exponent s =
  let n = String.length s and i = ref 0 in
  let digits () = let st = !i in while !i < n && s.[!i] >= '0' && s.[!i] <= '9' do incr i done; !i - st in
  if !i < n && (s.[!i] = '+' || s.[!i] = '-') then incr i;
  let a = digits () in
  let b = if !i < n && s.[!i] = '.' then (incr i; digits ()) else 0 in
  let e =
    if exponent && !i < n && (s.[!i] = 'e' || s.[!i] = 'E') then (
      incr i;
      if !i < n && (s.[!i] = '+' || s.[!i] = '-') then incr i;
      digits () > 0)
    else true
  in
  a + b > 0 && e && !i = n

let rec value (l : S.lex) (s : string) : J.t =
  match l with
  | S.Str S.Preserve -> `String s
  | S.Str S.Replace -> `String (replace_ws s)
  | S.Str S.Collapse -> `String (collapse s)
  | S.Int -> let s = collapse s in if integer s then `Intlit (canonical_integer s) else bad "not an integer: %S" s
  | S.Dec | S.Dbl ->
      let s = collapse s in
      if not (decimal_lexical ~exponent:(l = S.Dbl) s) then bad "not a %s: %S" (if l = S.Dec then "decimal" else "double") s;
      let x = float_of_string s in
      if Float.is_finite x then `Float x else bad "not finite: %S" s
  | S.Bool -> (match collapse s with "true" | "1" -> `Bool true | "false" | "0" -> `Bool false | s -> bad "not a boolean: %S" s)
  | S.Lst item -> `List (List.map (value item) (List.filter (( <> ) "") (String.split_on_char ' ' (collapse s))))

(* the shortest decimal that reads back as the same double; for xs:decimal, without an exponent *)
let shortest f =
  let rec go p = let s = Printf.sprintf "%.*g" p f in if p >= 17 || float_of_string s = f then s else go (p + 1) in
  go 1

let plain_decimal f =
  let s = shortest f in
  match String.index_opt s 'e' with
  | None -> s
  | Some k ->
      let mant = String.sub s 0 k and exp = int_of_string (String.sub s (k + 1) (String.length s - k - 1)) in
      let neg = mant.[0] = '-' in
      let mant = if neg then String.sub mant 1 (String.length mant - 1) else mant in
      let ip, fp = match String.index_opt mant '.' with Some d -> (String.sub mant 0 d, String.sub mant (d + 1) (String.length mant - d - 1)) | None -> (mant, "") in
      let digits = ip ^ fp and point = String.length ip + exp in
      let body =
        if point <= 0 then "0." ^ String.make (-point) '0' ^ digits
        else if point >= String.length digits then digits ^ String.make (point - String.length digits) '0'
        else String.sub digits 0 point ^ "." ^ String.sub digits point (String.length digits - point)
      in
      (if neg then "-" else "") ^ body

let rec text (l : S.lex) (j : J.t) : string =
  let s =
    match l, j with
    | S.Str _, `String s -> s
    | S.Int, `Int i -> string_of_int i
    | S.Int, `Intlit s -> s
    | S.Int, `Float x when Float.is_integer x && Float.abs x < 1e18 -> Printf.sprintf "%.0f" x
    | (S.Dec | S.Dbl), `Int i -> string_of_int i
    | (S.Dec | S.Dbl), `Intlit s -> s
    | S.Dec, `Float x -> if Float.is_finite x then plain_decimal x else bad "not finite"
    | S.Dbl, `Float x -> if Float.is_finite x then shortest x else bad "not finite"
    | S.Bool, `Bool b -> string_of_bool b
    | S.Lst item, `List xs ->
        String.concat " "
          (List.map
             (fun x ->
               let t = text item x in
               if t = "" || String.exists (fun c -> c = ' ' || c = '\t' || c = '\n' || c = '\r') t then bad "a list item %S holds white space" t;
               t)
             xs)
    | _, j -> bad "not of its type: %s" (J.to_string j)
  in
  if not (X.writable s) then bad "%S holds a character XML 1.0 cannot" s;
  s

(* ---- reading ----------------------------------------------------------------------------------------- *)

(* the complex types by name, which a recursive type's Ref names: the type being read's *)
let named : (string * S.complex) list ref = ref []

let resolve n = match List.assoc_opt n !named with Some c -> c | None -> bad "no complex type %s" n

let rec read_shape (path : string) (sh : S.shape) (e : X.el) : J.t =
  match sh with
  | S.Ref n -> read_shape path (S.Complex (resolve n)) e
  | S.Simple l ->
      if X.elements e <> [] then bad "%s: an element inside a simple value" path;
      (try value l (X.text e) with Bad m -> bad "%s: %s" path m)
  | S.Complex c ->
      List.iter
        (fun ((ns, a), _) -> if ns <> "" || not (List.exists (fun (d : S.attr) -> d.aname = a) c.attrs) then bad "%s: the attribute %s is not the type's" path a)
        e.attrs;
      let attrs =
        List.filter_map
          (fun (d : S.attr) ->
            match X.attr e d.aname, d.adefault with
            | Some v, _ | None, Some v -> Some (d.ajson, (try value d.alex v with Bad m -> bad "%s@%s: %s" path d.aname m))
            | None, None -> None)
          c.attrs
      in
      let no_text () = if not (X.blank (X.text e)) then bad "%s: text where only elements may be" path in
      let content =
        match c.content with
        | S.Empty -> no_text (); if X.elements e <> [] then bad "%s: an element in an empty type" path; []
        | S.Value l ->
            if X.elements e <> [] then bad "%s: an element inside a simple value" path;
            [ ("value", (try value l (X.text e) with Bad m -> bad "%s: %s" path m)) ]
        | S.Seq ps -> no_text (); sequence path ps (X.elements e)
        | S.All ps -> no_text (); all path ps (X.elements e)
      in
      `Assoc (attrs @ content)

and is_el (d : S.elem) (k : X.el) = k.ns = d.ens && k.name = d.ename

and one_or_list (d_max : int option) xs = match d_max with Some 1 -> (match xs with [ x ] -> Some x | _ -> None) | _ -> Some (`List xs)

and sequence path ps kids =
  let rec go ps kids acc =
    match ps with
    | [] -> (match kids with [] -> List.rev acc | (k : X.el) :: _ -> bad "%s: the element %s is not expected here" path k.name)
    | S.Elem d :: rest ->
        let rec take n kids acc' =
          match kids with
          | (k : X.el) :: more when is_el d k && (match d.max with Some m -> n < m | None -> true) -> take (n + 1) more (read_shape (path ^ "/" ^ d.ename) d.shape k :: acc')
          | _ -> (n, List.rev acc', kids)
        in
        let n, vals, kids = take 0 kids [] in
        if n < d.min then bad "%s: %s occurs %d times, at least %d" path d.ename n d.min;
        (* an element that does not occur is an absent property, or the empty list *)
        let acc = if n = 0 && d.max = Some 1 then acc else match one_or_list d.max vals with Some v -> (d.ejson, v) :: acc | None -> acc in
        go rest kids acc
    | S.Choice c :: rest ->
        let rec take n kids acc' =
          match kids with
          | (k : X.el) :: more when List.exists (fun d -> is_el d k) c.cases && (match c.cmax with Some m -> n < m | None -> true) ->
              let d = List.find (fun d -> is_el d k) c.cases in
              take (n + 1) more (`Assoc [ ("kind", `String d.ename); ("value", read_shape (path ^ "/" ^ d.ename) d.shape k) ] :: acc')
          | _ -> (n, List.rev acc', kids)
        in
        let n, vals, kids = take 0 kids [] in
        if n < c.cmin then bad "%s: the choice occurs %d times, at least %d" path n c.cmin;
        let acc = if n = 0 && c.cmax = Some 1 then acc else match one_or_list c.cmax vals with Some v -> (c.cjson, v) :: acc | None -> acc in
        go rest kids acc
  in
  go ps kids []

and all path ps kids =
  let elems = List.filter_map (function S.Elem d -> Some d | S.Choice _ -> None) ps in
  let seen = Hashtbl.create 8 in
  let props =
    List.map
      (fun (k : X.el) ->
        match List.find_opt (fun d -> is_el d k) elems with
        | None -> bad "%s: the element %s is not expected here" path k.name
        | Some d ->
            if Hashtbl.mem seen d.ename then bad "%s: %s occurs twice" path d.ename;
            Hashtbl.replace seen d.ename ();
            (d.ejson, read_shape (path ^ "/" ^ d.ename) d.shape k))
      kids
  in
  List.iter (fun (d : S.elem) -> if d.min > 0 && not (Hashtbl.mem seen d.ename) then bad "%s: %s is missing" path d.ename) elems;
  props

let read ?limits (t : S.t) (doc : string) : (V.t, string) result =
  match
    let e = try X.parse ?limits doc with X.Bad m -> bad "%s" m in
    let ns, name = t.root in
    if e.ns <> ns || e.name <> name then bad "the document element is {%s}%s, not {%s}%s" e.ns e.name ns name;
    named := t.named;
    let j = read_shape ("/" ^ name) t.shape e in
    match C.read t.imp t.row j with Ok v -> v | Error m -> bad "%s" m
  with
  | v -> Ok v
  | exception Bad m -> Error m

(* ---- writing ----------------------------------------------------------------------------------------- *)

let write (t : S.t) (v : V.t) : (string, string) result =
  match
    let j = match C.write t.imp t.row v with Ok j -> j | Error m -> bad "%s" m in
    let b = Buffer.create 256 in
    let add = Buffer.add_string b in
    (* an element's tag: the default namespace when every element is qualified, else a prefix for the
       qualified ones and none for the rest *)
    let tag ns name = if ns = "" || t.qualified then name else "tns:" ^ name in
    let rec elem ?(root = false) ns name (sh : S.shape) (j : J.t) =
      let tg = tag ns name in
      add ("<" ^ tg);
      if root && t.tns <> "" then add (if t.qualified then Printf.sprintf " xmlns=\"%s\"" (X.escape ~attr:true t.tns) else Printf.sprintf " xmlns:tns=\"%s\"" (X.escape ~attr:true t.tns));
      let sh = match sh with S.Ref n -> (match List.assoc_opt n t.named with Some c -> S.Complex c | None -> bad "no complex type %s" n) | sh -> sh in
      match sh with
      | S.Ref n -> bad "no complex type %s" n
      | S.Simple l -> add ">"; add (X.escape ~attr:false (text l j)); add ("</" ^ tg ^ ">")
      | S.Complex c ->
          let props = match j with `Assoc kv -> kv | j -> bad "%s: not a record: %s" name (J.to_string j) in
          let attrs = List.sort (fun (a : S.attr) (b : S.attr) -> compare a.aname b.aname) c.attrs in
          List.iter
            (fun (a : S.attr) ->
              match List.assoc_opt a.ajson props with
              | Some (`Null) | None -> ()
              | Some x -> add (Printf.sprintf " %s=\"%s\"" a.aname (X.escape ~attr:true (text a.alex x))))
            attrs;
          (match c.content with
           | S.Empty -> add "/>"
           | S.Value l ->
               add ">";
               (match List.assoc_opt "value" props with Some x -> add (X.escape ~attr:false (text l x)) | None -> ());
               add ("</" ^ tg ^ ">")
           | S.Seq ps | S.All ps ->
               add ">";
               List.iter
                 (function
                   | S.Elem d -> (
                       match List.assoc_opt d.ejson props with
                       | None | Some `Null -> ()
                       | Some (`List xs) when d.max <> Some 1 -> List.iter (fun x -> elem d.ens d.ename d.shape x) xs
                       | Some x -> elem d.ens d.ename d.shape x)
                   | S.Choice c -> (
                       let one x =
                         match x with
                         | `Assoc kv -> (
                             match List.assoc_opt "kind" kv, List.assoc_opt "value" kv with
                             | Some (`String k), Some v -> (
                                 match List.find_opt (fun (d : S.elem) -> d.ename = k) c.cases with
                                 | Some d -> elem d.ens d.ename d.shape v
                                 | None -> bad "no case %s" k)
                             | _ -> bad "a choice's value is {kind; value}")
                         | _ -> bad "a choice's value is {kind; value}"
                       in
                       match List.assoc_opt c.cjson props with
                       | None | Some `Null -> ()
                       | Some (`List xs) when c.cmax <> Some 1 -> List.iter one xs
                       | Some x -> one x))
                 ps;
               add ("</" ^ tg ^ ">"))
    in
    add "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    let ns, name = t.root in
    elem ~root:true ns name t.shape j;
    Buffer.contents b
  with
  | s -> Ok s
  | exception Bad m -> Error m
