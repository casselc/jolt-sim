(* The XSD importer and the XML codec (import/lib/xsd.ml, xml_codec.ml;
   docs/cordis/rfc/0005-xml-csv-frames.md, revision 4): documents read and
   refused, what is written, and a Hegel property that values drawn from
   the imported type, written, read back as themselves. The agreement with
   xmlschema is xml_agreement.ml. *)

open Cordis_import
open Typed_values
module J = Yojson.Safe
module V = Cordis_cel.Value

let order = lazy (Xsd.of_string (In_channel.with_open_bin "xsd/order.xsd" In_channel.input_all))
let kinds = lazy (Xsd.of_string (In_channel.with_open_bin "xsd/kinds.xsd" In_channel.input_all))

let ok_doc =
  {|<?xml version="1.0"?>
<order xmlns="urn:example:orders" id="7">
  <customer><email>a@b.c</email><name>Ada</name></customer>
  <line sku="AB-123" qty="2">12.50</line>
  <line sku="CD-456">3</line>
  <invoice>  net  30 </invoice>
  <tags>a b  c</tags>
</order>|}

let with_body body = {|<order xmlns="urn:example:orders" id="7"><customer><name>Ada</name></customer><line sku="AB-123">1</line>|} ^ body ^ "</order>"

let read_cases =
  [ ("a document in any whitespace, all in any order, defaults filled", ok_doc, None);
    ("a choice's other case", with_body "<card>1234 5678 9012 3456</card>", None);
    ("no choice", with_body "", Some "the choice occurs 0 times");
    ("two choices", with_body "<card>1234 5678 9012 3456</card><invoice>x</invoice>", Some "not expected here");
    ("an unknown element", with_body "<invoice>x</invoice><extra/>", Some "the element extra is not expected here");
    ("out of order", {|<order xmlns="urn:example:orders" id="7"><line sku="AB-123">1</line><customer><name>Ada</name></customer><invoice>x</invoice></order>|}, Some "customer occurs 0 times");
    ("an unknown attribute", {|<order xmlns="urn:example:orders" id="7" x="1"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "the attribute x is not the type's");
    ("a missing required attribute", {|<order xmlns="urn:example:orders"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "id is required");
    ("too many lines", with_body ({|<line sku="AB-123">1</line>|} ^ String.concat "" (List.init 5 (fun _ -> {|<line sku="AB-123">1</line>|})) ^ "<invoice>x</invoice>"), Some "the choice occurs 0 times");
    ("a bad integer", {|<order xmlns="urn:example:orders" id="x"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "not an integer");
    ("a residue: the sku's pattern", {|<order xmlns="urn:example:orders" id="7"><customer><name>Ada</name></customer><line sku="ab-123">1</line><invoice>x</invoice></order>|}, Some "does not satisfy");
    ("a residue: id positive", {|<order xmlns="urn:example:orders" id="0"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "does not satisfy");
    ("an enumeration", {|<order xmlns="urn:example:orders" id="7" status="lost"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "lost");
    ("text among elements", with_body "words<invoice>x</invoice>", Some "text where only elements may be");
    ("the wrong namespace", {|<order id="7"><customer><name>Ada</name></customer><line sku="AB-123">1</line><invoice>x</invoice></order>|}, Some "the document element is");
    ("a DTD", {|<?xml version="1.0"?><!DOCTYPE order [<!ENTITY x "y">]><order xmlns="urn:example:orders" id="7"/>|}, Some "a DTD is not read");
    ("an entity of its own", with_body "<invoice>&x;</invoice>", Some "") ]

let contains m frag = frag = "" || Schema_pred.replace ~sub:frag ~by:"" m <> m

let read_case (label, doc, want) () =
  match Xml_codec.read (Lazy.force order) doc, want with
  | Ok _, None -> ()
  | Ok _, Some f -> Alcotest.failf "%s: read, expected a refusal (%s)" label f
  | Error m, None -> Alcotest.failf "%s: refused: %s" label m
  | Error m, Some f -> if not (contains m f) then Alcotest.failf "%s: refused as %s, expected %s" label m f

let canonical () =
  let t = Lazy.force order in
  match Xml_codec.read t ok_doc with
  | Error m -> Alcotest.fail m
  | Ok v -> (
      match Xml_codec.write t v with
      | Error m -> Alcotest.fail m
      | Ok s ->
          Alcotest.(check string) "canonical"
            {|<?xml version="1.0" encoding="UTF-8"?>
<order xmlns="urn:example:orders" id="7" status="open"><customer><name>Ada</name><email>a@b.c</email></customer><line qty="2" sku="AB-123">12.5</line><line qty="1" sku="CD-456">3</line><invoice>net 30</invoice><tags>a b c</tags></order>|}
            s;
          match Xml_codec.read t s with Ok v' -> Alcotest.(check bool) "reads back" true (same v v') | Error m -> Alcotest.fail m)

let refused =
  [ ({|<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="a" type="T"/><xs:complexType name="T"><xs:sequence><xs:element name="b" type="T" minOccurs="0"/></xs:sequence></xs:complexType></xs:schema>|}, "recursive");
    ({|<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="a" type="xs:date"/></xs:schema>|}, "open question 2");
    ({|<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="a"><xs:complexType mixed="true"/></xs:element></xs:schema>|}, "mixed");
    ({|<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="a"><xs:simpleType><xs:restriction base="xs:string"><xs:pattern value="\i\c*"/></xs:restriction></xs:simpleType></xs:element></xs:schema>|}, "name classes");
    ({|<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:include schemaLocation="b.xsd"/></xs:schema>|}, "one schema document") ]

let refusal (schema, frag) () =
  match Xsd.of_string schema with
  | _ -> Alcotest.failf "imported: %s" schema
  | exception Xsd.Refused m -> if not (contains m frag) then Alcotest.failf "refused as %s" m

(* XSD's own pattern dialect: matched whole, ^ and $ characters, \d any decimal digit, \s the four XML
   white-space characters, . anything but CR and LF *)
let xsd_patterns =
  [ ("[0-9]{3}", "123", true); ("[0-9]{3}", "1234", false); ("\\d+", "\xd9\xa3\xd9\xa4", true); ("a^b$", "a^b$", true);
    ("\\s", "\xc2\xa0", false); ("\\s", "\t", true); (".", "\r", false); (".", "\xe2\x80\xa8", true);
    ("\\w+", "a\xc3\xa9_", false); ("\\w+", "a\xc3\xa91", true) ]

let xsd_pattern (p, s, want) () =
  let ecma = "^(?:" ^ Xsd.ecma_of_xsd p ^ ")$" in
  match Cordis_cel.Eval.compile_regex (Schema_pred.translate ecma) with
  | None -> Alcotest.failf "%s does not compile" p
  | Some r -> Alcotest.(check bool) (Printf.sprintf "%S ~ %S" p s) want (Re.execp r s)

let written = ref 0 and read_back = ref 0

let roundtrip () =
  Fun.protect ~finally:(fun () -> Printf.printf "%d documents written, %d valid ones read back alike\n" !written !read_back) @@ fun () ->
  (* every structural feature, without constraints, so most drawn values are valid *)
  let t = Lazy.force kinds in
  pools_for t.schema;
  strings := !strings @ [ "a b"; " x"; "x\r\ny"; "<&>\""; "tab\there"; "left"; "right" ];
  let settings = Hegel.Settings.create ~test_cases:500 ~seed:8 () in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:xml-roundtrip" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let v = value t.imp d 6 (Jsonschema.Named t.row) in
      match Xml_codec.write t v with
      | Error m -> Hegel.note tc ("not written: " ^ m)
      | Ok s -> (
          incr written;
          Hegel.note tc s;
          let valid = match Jsonschema_codec.write t.imp t.row v with Ok j -> Result.is_ok (Jsonschema_codec.read t.imp t.row j) | Error _ -> false in
          match Xml_codec.read t s with
          | Ok v' ->
              Hegel.note tc (Printf.sprintf "drawn %s\nread  %s" (V.to_string v) (V.to_string v'));
              (* a value outside its schema may be read as another (XSD's whitespace normalised it) *)
              if valid then (Hegel.require tc ~msg:"read back as a different value" (same v v'); incr read_back)
          | Error m -> Hegel.note tc ("refused: " ^ m); Hegel.require tc ~msg:("refused a value that satisfies its schema: " ^ m) (not valid)))

let () =
  Alcotest.run "xml"
    [ ("read", List.map (fun ((l, _, _) as c) -> Alcotest.test_case l `Quick (read_case c)) read_cases);
      ("write", [ Alcotest.test_case "canonical, and read back" `Quick canonical ]);
      ("imports", List.map (fun (s, f) -> Alcotest.test_case f `Quick (refusal (s, f))) refused);
      ("patterns", List.map (fun ((p, s, _) as c) -> Alcotest.test_case (Printf.sprintf "%S %S" p s) `Quick (xsd_pattern c)) xsd_patterns);
      ("properties", [ Alcotest.test_case "values written read back as themselves" `Quick roundtrip ]) ]
