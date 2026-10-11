(* The XSD importer and the XML codec against xmlschema and lxml (docs/
   cordis/rfc/0005-xml-csv-frames.md, revision 4, "Testing"), by a Hegel
   property over each schema given: for a value drawn from the document
   element's imported type, the document the codec writes is valid for
   xmlschema exactly when the codec reads it back (its residue included),
   and libxml2 says the same.

     xml_agreement.exe XMLVALID.py SCHEMA.xsd... *)

open Cordis_import
open Typed_values
module J = Yojson.Safe

let settings =
  let cases = match Sys.getenv_opt "IMPORT_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:23 ()

let oracle = lazy (Unix.open_process_args "python3" [| "python3"; Sys.argv.(1) |])

let validate xsd text =
  let ic, oc = Lazy.force oracle in
  output_string oc (J.to_string (`Assoc [ ("xsd", `String xsd); ("xml", `String text) ]) ^ "\n");
  flush oc;
  let j = J.from_string (input_line ic) in
  let open J.Util in
  (j |> member "valid" |> to_bool, j |> member "lxml" |> to_bool, j |> member "errors" |> to_list |> List.map to_string)

let schemas =
  List.map (fun p -> (p, lazy (Xsd.of_string (In_channel.with_open_bin p In_channel.input_all)))) (List.tl (List.tl (Array.to_list Sys.argv)))

let agreement () =
  let compared = ref 0 and valid_docs = ref 0 in
  Fun.protect ~finally:(fun () -> Printf.printf "%d documents compared with xmlschema and lxml, %d of them valid\n" !compared !valid_docs) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:xml-agreement" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let path, t = choose d schemas in
      let t = Lazy.force t in
      pools_for t.schema;
      strings := !strings @ [ "a b"; " x"; "x\r\ny"; "<&>\""; "tab\there"; "AB-123"; "1234 5678 9012 3456"; "open"; "paid"; "Ada" ];
      let v = value t.imp d 6 (Jsonschema.Named t.row) in
      match Xml_codec.write t v with
      | Error m -> Hegel.note tc ("not written: " ^ m)
      | Ok text ->
          incr compared;
          let ours = Xml_codec.read t text in
          let valid, lvalid, errors = validate path text in
          Hegel.note tc (Printf.sprintf "%s\n%s\nours: %s\nxmlschema: %b %s\nlxml: %b" path text
            (match ours with Ok _ -> "reads it" | Error m -> m) valid (String.concat "; " errors) lvalid);
          Hegel.require tc ~msg:"xmlschema and lxml disagree with each other" (valid = lvalid);
          Hegel.require tc ~msg:"xmlschema and the codec disagree on the document's validity" (valid = Result.is_ok ours);
          if valid then incr valid_docs)

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "xml-agreement"
    [ ("properties", [ Alcotest.test_case "xmlschema accepts exactly what the codec reads" `Quick agreement ]) ]
