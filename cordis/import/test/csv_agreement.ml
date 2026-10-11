(* The CSV codec against frictionless (docs/cordis/rfc/0005-xml-csv-
   frames.md, revision 3, "CSV"), by a Hegel property over each Table
   Schema given: for rows drawn from the imported row type, the table the
   codec writes is valid for frictionless exactly when the codec reads it
   back (the row schema's residue, the header, unique and primary keys),
   and then frictionless reads the same rows.

     csv_agreement.exe CSVVALID.py SCHEMA.json... *)

open Cordis_import
open Typed_values
module J = Yojson.Safe
module T = Table_schema
module X = Csv_codec

let settings =
  let cases = match Sys.getenv_opt "IMPORT_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:17 ()

let oracle = lazy (Unix.open_process_args "python3" [| "python3"; Sys.argv.(1) |])

let validate schema text =
  let ic, oc = Lazy.force oracle in
  output_string oc (J.to_string (`Assoc [ ("schema", schema); ("csv", `String text) ]) ^ "\n");
  flush oc;
  let j = J.from_string (input_line ic) in
  let open J.Util in
  (j |> member "valid" |> to_bool, j |> member "errors" |> to_list |> List.map to_string, j |> member "rows" |> to_list)

(* the same row: numbers by value, absent and null alike *)
let rec same_json (a : J.t) (b : J.t) =
  let num = function `Int i -> Some (float_of_int i) | `Float f -> Some f | `Intlit s -> float_of_string_opt s | _ -> None in
  match a, b with
  | `Assoc x, `Assoc y ->
      let x = List.filter (fun (_, v) -> v <> `Null) x and y = List.filter (fun (_, v) -> v <> `Null) y in
      List.length x = List.length y && List.for_all (fun (k, v) -> match List.assoc_opt k y with Some w -> same_json v w | None -> false) x
  | _ -> (match num a, num b with Some x, Some y -> x = y | _ -> a = b)

let schemas = List.map (fun p -> (p, J.from_file p)) (List.tl (List.tl (Array.to_list Sys.argv)))

let agreement () =
  let compared = ref 0 and valid_tables = ref 0 in
  Fun.protect ~finally:(fun () -> Printf.printf "%d tables compared with frictionless, %d of them valid\n" !compared !valid_tables) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:csv-agreement" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let path, schema = choose d schemas in
      let t = T.import schema in
      pools_for t.schema;
      strings := !strings @ [ ","; "\""; "a\r\nb"; "NA"; " x "; "yes"; "ABC"; "DEF"; "123e4567-e89b-12d3-a456-426614174000" ];
      let rows = List.init (1 + d.pick 3) (fun _ -> value t.imp d 4 (Jsonschema.Named t.row)) in
      match X.write t rows with
      | Error m -> Hegel.note tc ("not written: " ^ m)
      | Ok text ->
          incr compared;
          let ours = X.read t text in
          let valid, errors, theirs = validate schema text in
          Hegel.note tc (Printf.sprintf "%s\n%S\nours: %s\nfrictionless: %b %s" path text
            (match ours with Ok _ -> "reads it" | Error m -> m) valid (String.concat "; " errors));
          Hegel.require tc ~msg:"frictionless and the codec disagree on the table's validity" (valid = Result.is_ok ours);
          match ours with
          | Ok back ->
              incr valid_tables;
              let ours_json = List.map (fun v -> match Jsonschema_codec.write t.imp t.row v with Ok j -> j | Error m -> failwith m) back in
              Hegel.note tc (Printf.sprintf "ours %s\ntheirs %s" (J.to_string (`List ours_json)) (J.to_string (`List theirs)));
              Hegel.require tc ~msg:"frictionless reads different rows"
                (List.length theirs = List.length ours_json && List.for_all2 same_json ours_json theirs)
          | Error _ -> ())

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "csv-agreement"
    [ ("properties", [ Alcotest.test_case "frictionless accepts exactly what the codec reads, as the same rows" `Quick agreement ]) ]
