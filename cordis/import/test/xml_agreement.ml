(* The XSD importer and the XML codec against xmlschema and lxml (docs/
   cordis/rfc/0005-xml-csv-frames.md, revision 4, "Testing"), by a Hegel
   property over each schema given: for a value drawn from the document
   element's imported type, the document the codec writes is valid for
   xmlschema exactly when the codec reads it back (its residue included),
   libxml2 says the same, and xmlschema decodes it to the value the codec
   read (in xmlschema's form: "@" attributes, "$" simple content, a list
   for what may repeat, a repeating choice grouped by element).

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
  (j |> member "valid" |> to_bool, j |> member "lxml" |> to_bool, j |> member "errors" |> to_list |> List.map to_string, j |> member "decoded")

module S = Xsd

(* a value, as the codec's JSON, in xmlschema's decoded form, by the shape *)
(* the complex types a recursive type's Ref names: the schema being compared's *)
let named : (string * S.complex) list ref = ref []

let rec xmlschema_form (sh : S.shape) (j : J.t) : J.t =
  match sh, j with
  | S.Ref n, j -> xmlschema_form (S.Complex (List.assoc n !named)) j
  (* an empty list element decodes as xmlschema's null *)
  | S.Simple (S.Lst _), `List [] -> `Null
  | S.Simple _, j -> j
  | S.Complex c, `Assoc kv ->
      let attrs = List.filter_map (fun (a : S.attr) -> Option.map (fun v -> ("@" ^ a.aname, v)) (List.assoc_opt a.ajson kv)) c.attrs in
      let content =
        match c.content with
        | S.Empty -> []
        | S.Value _ -> (match List.assoc_opt "value" kv with Some v -> [ ("$", v) ] | None -> [])
        | S.Seq ps | S.All ps ->
            List.concat_map
              (function
                | S.Elem d -> (
                    match List.assoc_opt d.ejson kv, d.max with
                    | (None | Some `Null), _ -> []
                    | Some (`List []), m when m <> Some 1 -> []
                    | Some (`List xs), m when m <> Some 1 -> [ (d.ename, `List (List.map (xmlschema_form d.shape) xs)) ]
                    | Some v, _ -> [ (d.ename, xmlschema_form d.shape v) ])
                | S.Choice c -> (
                    let items = match List.assoc_opt c.cjson kv with Some (`List xs) when c.cmax <> Some 1 -> xs | Some `Null | None -> [] | Some x -> [ x ] in
                    let case x = match x with `Assoc kv -> (match List.assoc_opt "kind" kv, List.assoc_opt "value" kv with Some (`String k), Some v -> (k, v) | _ -> failwith "a choice") | _ -> failwith "a choice" in
                    let items = List.map case items in
                    let kinds = List.sort_uniq compare (List.map fst items) in
                    List.map
                      (fun k ->
                        let d = List.find (fun (d : S.elem) -> d.ename = k) c.cases in
                        let vs = List.filter_map (fun (k', v) -> if k' = k then Some (xmlschema_form d.shape v) else None) items in
                        (k, if c.cmax = Some 1 then List.hd vs else `List vs))
                      kinds))
              ps
      in
      `Assoc (attrs @ content)
  | _, j -> j

(* the same decoded value: numbers by value, keys in any order, an empty string as xmlschema's null *)
let rec same_form (a : J.t) (b : J.t) =
  let num = function `Int i -> Some (float_of_int i) | `Float f -> Some f | `Intlit s -> float_of_string_opt s | _ -> None in
  match a, b with
  | `Assoc x, `Assoc y -> List.length x = List.length y && List.for_all (fun (k, v) -> match List.assoc_opt k y with Some w -> same_form v w | None -> false) x
  | `List x, `List y -> List.length x = List.length y && List.for_all2 same_form x y
  | `String "", `Null | `Null, `String "" -> true
  | _ -> (match num a, num b with Some x, Some y -> x = y | _ -> a = b)

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
          let valid, lvalid, errors, decoded = validate path text in
          Hegel.note tc (Printf.sprintf "%s\n%s\nours: %s\nxmlschema: %b %s\nlxml: %b" path text
            (match ours with Ok _ -> "reads it" | Error m -> m) valid (String.concat "; " errors) lvalid);
          Hegel.require tc ~msg:"xmlschema and lxml disagree with each other" (valid = lvalid);
          Hegel.require tc ~msg:"xmlschema and the codec disagree on the document's validity" (valid = Result.is_ok ours);
          match ours with
          | Ok back when valid ->
              incr valid_docs;
              named := t.named;
              let mine = match Jsonschema_codec.write t.imp t.row back with Ok j -> xmlschema_form t.shape j | Error m -> failwith m in
              Hegel.note tc (Printf.sprintf "ours, in xmlschema's form: %s\nxmlschema decodes: %s" (J.to_string mine) (J.to_string decoded));
              Hegel.require tc ~msg:"xmlschema decodes a different value" (same_form mine decoded)
          | _ -> ())

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "xml-agreement"
    [ ("properties", [ Alcotest.test_case "xmlschema accepts exactly what the codec reads" `Quick agreement ]) ]
