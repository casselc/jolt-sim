(* Schemaless XML (import/lib/xml_node.ml; docs/cordis/rfc/0005-xml-csv-frames.md, revision 5): documents
   read into the library node type and refused, the type checked by the graph checker, and a Hegel
   property that a value written reads back as itself, made canonical. With XMLNODE.py given (lxml), it
   also checks that lxml reads every document written, and a set of hand-written ones, as the codec does.

     xml_node_test.exe [XMLNODE.py] *)

open Cordis_import
module V = Cordis_cel.Value
module J = Yojson.Safe

let record fs = V.make_map (List.map (fun (k, v) -> (V.String k, v)) fs)
let case tag v = record [ ("tag", V.String tag); ("value", v) ]
let el ?(ns = "") ?(attrs = []) name kids = record [ ("ns", V.String ns); ("name", V.String name); ("attrs", V.List (Array.of_list attrs)); ("kids", V.List (Array.of_list kids)) ]
let attr ?(ns = "") name value = record [ ("ns", V.String ns); ("name", V.String name); ("value", V.String value) ]
let text t = case "Text" (V.String t)
let elem e = case "Element" e

let reads =
  [ ("mixed content, comments, a processing instruction",
     {|<a>x<!--c--><b k="v"/>y<?p d e?></a>|},
     el "a" [ text "x"; case "Comment" (V.String "c"); elem (el "b" ~attrs:[ attr "k" "v" ] []); text "y"; case "Pi" (record [ ("target", V.String "p"); ("data", V.String "d e") ]) ]);
    ("CDATA, references and text as one node; CR LF as LF",
     "<a>1<![CDATA[<&>]]>&amp;&#13;2\r\n3</a>", el "a" [ text "1<&>&\r2\n3" ]);
    ("namespaces: a default, a prefixed attribute, xml:lang",
     {|<r xmlns="urn:a" xmlns:p="urn:b" p:k="1" xml:lang="en"><s/></r>|},
     el ~ns:"urn:a" ~attrs:[ attr ~ns:"urn:b" "k" "1"; attr ~ns:Xml_tree.ns_xml "lang" "en" ] "r" [ elem (el ~ns:"urn:a" "s" []) ]);
    ("white space between elements is kept", "<a>\n  <b/>\n</a>", el "a" [ text "\n  "; elem (el "b" []); text "\n" ]) ]

let read_case (label, doc, want) () =
  match Xml_node.read doc with
  | Ok v -> Alcotest.(check bool) (label ^ ": " ^ V.to_string v) true (V.equal v want)
  | Error m -> Alcotest.failf "%s: refused: %s" label m

let refusals =
  [ ("a DTD", {|<!DOCTYPE a [<!ENTITY x "y">]><a/>|}); ("-- in a comment", "<a><!-- a -- b --></a>");
    ("a processing instruction named xml", "<a><?xml x?></a>"); ("an undeclared prefix", "<p:a/>") ]

let refusal (label, doc) () = match Xml_node.read doc with Ok v -> Alcotest.failf "%s: read as %s" label (V.to_string v) | Error _ -> ()

let unwritable =
  [ ("a name that is not one", el "1a" []); ("-- in a comment", el "a" [ case "Comment" (V.String "a--b") ]);
    ("?> in a processing instruction", el "a" [ case "Pi" (record [ ("target", V.String "p"); ("data", V.String "a?>b") ]) ]);
    ("a control character", el "a" [ text "\x01" ]); ("an attribute twice", el ~attrs:[ attr "k" "1"; attr "k" "2" ] "a" []) ]

let unwritable_case (label, v) () = match Xml_node.write v with Ok s -> Alcotest.failf "%s: written as %s" label s | Error _ -> ()

(* the library types check, a cell walks a document, and the node bound holds where a value enters *)
let types () =
  let text = Xml_node.types ^ {|
cell elements(e : XmlElement) : int = { e.kids.filter(k, k.tag == "Element").size(); }
cell first_name(e : XmlElement) : string = { e.kids.size() > 0 ? (match e.kids[0] { Element(c) -> c.name; _ -> "" }) : ""; }
|} in
  match Cordis_graph.Patch.load text with
  | Error es -> Alcotest.failf "the node types do not check: %s" (String.concat "; " es)
  | Ok s ->
      let c = List.find (fun (c : Cordis_graph.Check.cell_info) -> c.cname = "elements") s.checked.cells in
      let b = snd (List.hd c.param_bounds) in
      let small = el "a" [ elem (el "b" []) ] in
      Alcotest.(check (option string)) "a small document" None (Cordis_graph.Check.exceeds b small);
      let big = el "a" (List.init 20000 (fun _ -> elem (el "b" []))) in
      Alcotest.(check bool) "a document of over 65,536 nodes" true (Cordis_graph.Check.exceeds b big <> None)

(* ---- drawn values ----------------------------------------------------------------------------------- *)

(* valid parts, and now and then one XML cannot hold *)
let names = ([ "a"; "b"; "x-y"; "_z"; "\xc3\xa9t\xc3\xa9"; "n.1" ], [ "1bad" ])
let nss = [ ""; "urn:a"; "urn:b" ]
let attr_nss = [ ""; "urn:a"; "urn:c"; Xml_tree.ns_xml ]
let texts = ([ ""; " "; "t"; "<&>\"'"; "]]>"; "\r\n"; "\t"; "h\xc3\xa9llo"; "\xf0\x9f\x90\xab" ], [ "\xef\xbf\xbe"; "\x01" ])
let comments = ([ ""; "c"; " x "; "a-b" ], [ "-"; "a--b"; "\r" ])
let targets = ([ "p"; "xml-stylesheet"; "_t" ], [ "xml" ])
let datas = ([ ""; "d"; "x y" ], [ " d"; "a?>b" ])

let rec draw (d : Typed_gen.draw) depth =
  let pick l = Typed_gen.choose d l in
  let some (ok, bad) = if d.pick 12 = 0 then pick bad else pick ok in
  let kid () =
    match d.pick (if depth <= 0 then 3 else 4) with
    | 0 -> text (some texts)
    | 1 -> case "Comment" (V.String (some comments))
    | 2 -> case "Pi" (record [ ("target", V.String (some targets)); ("data", V.String (some datas)) ])
    | _ -> elem (draw d (depth - 1))
  in
  el ~ns:(pick nss) (some names)
    ~attrs:(List.init (d.pick 3) (fun _ -> attr ~ns:(pick attr_nss) (some names) (some texts)))
    (List.init (d.pick 5) (fun _ -> kid ()))

let settings = Hegel.Settings.create ~test_cases:1000 ~seed:31 ()
let written = ref 0 and refused = ref 0

let roundtrip () =
  Fun.protect ~finally:(fun () -> Printf.printf "%d documents written and read back as themselves, %d values refused\n" !written !refused) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:xml-node" (fun tc ->
      let v = draw (Typed_gen.of_hegel tc) 3 in
      match Xml_node.write v with
      | Error m -> incr refused; Hegel.note tc ("refused: " ^ m)
      | Ok s -> (
          Hegel.note tc s;
          match Xml_node.read s with
          | Ok back ->
              Hegel.note tc (V.to_string back);
              Hegel.require tc ~msg:"read back as another value" (V.equal back (Xml_node.canonical v));
              incr written
          | Error m -> Hegel.require tc ~msg:("refused what it wrote: " ^ m) false))

(* ---- against lxml ----------------------------------------------------------------------------------- *)

let rec json (v : V.t) : J.t =
  match v with
  | V.String s -> `String s
  | V.List xs -> `List (Array.to_list (Array.map json xs))
  | V.Map m -> `Assoc (List.sort compare (List.map (fun (k, x) -> ((match k with V.String k -> k | k -> V.to_string k), json x)) m))
  | v -> `String (V.to_string v)

let rec sorted (j : J.t) : J.t =
  match j with `Assoc kv -> `Assoc (List.sort compare (List.map (fun (k, x) -> (k, sorted x)) kv)) | `List xs -> `List (List.map sorted xs) | j -> j

let oracle = lazy (Unix.open_process_args "python3" [| "python3"; Sys.argv.(1) |])

let lxml doc =
  let ic, oc = Lazy.force oracle in
  output_string oc (J.to_string (`Assoc [ ("xml", `String doc) ]) ^ "\n");
  flush oc;
  let j = J.from_string (input_line ic) in
  match J.Util.member "ok" j with `Bool true -> Ok (sorted (J.Util.member "node" j)) | _ -> Error (J.to_string (J.Util.member "error" j))

let agree doc =
  match Xml_node.read doc, lxml doc with
  | Ok v, Ok n -> if json v <> n then Some (Printf.sprintf "ours %s\nlxml %s" (J.to_string (json v)) (J.to_string n)) else None
  | Error _, Error _ -> None
  | Ok _, Error e -> Some ("lxml refuses what the codec reads: " ^ e)
  | Error m, Ok _ -> Some ("the codec refuses what lxml reads: " ^ m)

(* a DTD the codec refuses by design (no entities beyond XML's own), where lxml reads it *)
let handwritten =
  List.map (fun (_, d, _) -> d) reads
  @ List.filter (fun d -> not (Xml_node.contains d "<!DOCTYPE")) (List.map snd refusals)
  @ [ {|<a xmlns:p="urn:p"><p:b p:c="1" c="2"/></a>|}; "<a>&#x10FFFF;&#9;</a>"; "<a b='x&#10;y\ttab'/>"; {|<a xmlns="urn:x"><b xmlns=""/></a>|};
      "<a><?p?></a>"; "<a><!----></a>"; "<a>\xef\xbf\xbe</a>"; "<a>&#xFFFE;</a>"; "<a>x</a><!-- after -->" ]

let lxml_handwritten () =
  List.iter (fun doc -> match agree doc with Some m -> Alcotest.failf "%S:\n%s" doc m | None -> ()) handwritten

let compared = ref 0

let lxml_written () =
  Fun.protect ~finally:(fun () -> Printf.printf "%d written documents read alike by lxml\n" !compared) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:xml-node-lxml" (fun tc ->
      let v = draw (Typed_gen.of_hegel tc) 3 in
      match Xml_node.write v with
      | Error _ -> ()
      | Ok s -> (
          Hegel.note tc s;
          match agree s with Some m -> Hegel.require tc ~msg:m false | None -> incr compared))

let () =
  let oracle = Array.length Sys.argv > 1 in
  Alcotest.run ~argv:[| Sys.argv.(0) |] "xml-node"
    ([ ("read", List.map (fun ((l, _, _) as c) -> Alcotest.test_case l `Quick (read_case c)) reads);
       ("refused", List.map (fun ((l, _) as c) -> Alcotest.test_case l `Quick (refusal c)) refusals);
       ("unwritable", List.map (fun ((l, _) as c) -> Alcotest.test_case l `Quick (unwritable_case c)) unwritable);
       ("types", [ Alcotest.test_case "the node types check, a cell walks them, the node bound holds" `Quick types ]);
       ("properties", [ Alcotest.test_case "values written read back as themselves" `Quick roundtrip ]) ]
    @
    if oracle then
      [ ("lxml", [ Alcotest.test_case "hand-written documents read as lxml reads them" `Quick lxml_handwritten;
                   Alcotest.test_case "written documents read as lxml reads them" `Quick lxml_written ]) ]
    else [])
