(* The CSV codec (import/lib/csv_codec.ml; docs/cordis/rfc/0005-xml-csv-
   frames.md, revision 3): RFC 4180 text read strictly and written
   canonically, a table's rows by its Table Schema, and two Hegel
   properties: any grid of fields printed reads back as itself, and rows
   drawn from the imported type, written, read back as themselves. The
   agreement with Python's csv and frictionless is csv_agreement.ml. *)

open Cordis_import
open Typed_values
module J = Yojson.Safe
module V = Cordis_cel.Value
module T = Table_schema
module X = Csv_codec

let d = T.default_dialect
let grid = Alcotest.(result (list (list string)) string)
let parse s = match X.parse d s with g -> Ok g | exception X.Bad m -> Error m

let text_cases =
  [ ("a,b\r\nc,d\r\n", Ok [ [ "a"; "b" ]; [ "c"; "d" ] ]);
    ("a,b\nc,d", Ok [ [ "a"; "b" ]; [ "c"; "d" ] ]);
    ("\"a,b\",\"say \"\"hi\"\"\"\r\n", Ok [ [ "a,b"; "say \"hi\"" ] ]);
    ("\"line\r\nbreak\",x\r\n", Ok [ [ "line\r\nbreak"; "x" ] ]);
    (",\r\n", Ok [ [ ""; "" ] ]);
    ("\"\"\r\n", Ok [ [ "" ] ]);
    ("", Ok []);
    ("a\r\n\r\nb\r\n", Error "line 2: a blank line");
    ("a\"b\r\n", Error "line 1: a quote inside an unquoted field");
    ("\"a\"b\r\n", Error "line 1: text after a closing quote");
    ("\"a\r\n", Error "line 1: a quoted field is not closed");
    ("a\rb\r\n", Error "line 1: a bare CR") ]

let stations = lazy (T.import ~name:"Station" (J.from_file "tables/stations.json"))
let kinds = lazy (T.import ~name:"Kinds" (J.from_file "tables/kinds.json"))

let read_cases =
  let h = "id,code,name,kind,elevation,staffed,ref,note\r\n" in
  [ (h ^ "1,ABC,Central,metro,12.5,yes,,\r\n2,DEF,NA,,-3,N,123e4567-e89b-12d3-a456-426614174000,anything\r\n", Ok 2);
    ("id,code\r\n1,ABC\r\n", Error "row 1: the header id,code is not the fields id,code,name,kind,elevation,staffed,ref,note");
    (h ^ "1,ABC\r\n", Error "row 2: 2 columns, not 8");
    (h ^ "x,ABC,,,,,,\r\n", Error "row 2, column id: not an integer: \"x\"");
    (h ^ "1,ABC,,,,maybe,,\r\n", Error "row 2, column staffed: not a boolean: \"maybe\"");
    (h ^ "1,ABC,,,1e999,,,\r\n", Error "row 2, column elevation: not a finite number: \"1e999\"");
    (h ^ "NA,ABC,,,,,,\r\n", Error "row 2: Station: id is required");
    (h ^ "1,ABC,,,,,,\r\n1,DEF,,,,,,\r\n", Error "row 3: id repeats row 2's");
    (h ^ "1,ABC,,,,,,\r\n2,ABC,,,,,,\r\n", Error "row 3: code repeats row 2's");
    (h ^ "1,abc,,,,,,\r\n", Error "row 2: the value does not satisfy Station's schema");
    (h ^ "0,ABC,,,,,,\r\n", Error "row 2: the value does not satisfy Station's schema") ]

(* what the import refuses, by a fragment of the reason *)
let refused =
  [ ({|{"fields": [{"name": "d", "type": "date"}]}|}, "calendar type date");
    ({|{"fields": [{"name": "o", "type": "object"}]}|}, "no CSV form");
    ({|{"fields": [{"name": "e", "type": "string", "format": "email"}]}|}, "format email");
    ({|{"fields": [{"name": "n", "type": "number", "decimalChar": ","}]}|}, "decimalChar");
    ({|{"fields": [{"name": "a"}, {"name": "a"}]}|}, "given twice");
    ({|{"fields": [{"name": "a"}], "primaryKey": "b"}|}, "names no field b") ]

let refusal (schema, fragment) () =
  match T.import (J.from_string schema) with
  | _ -> Alcotest.failf "imported %s" schema
  | exception T.Refused m -> if Schema_pred.replace ~sub:fragment ~by:"" m = m then Alcotest.failf "%s: refused as %s" schema m

(* the shape of a result, for the cases: the row count, or the message *)
let shape = function Ok rows -> Ok (List.length rows) | Error m -> Error m

let grids () =
  let settings = Hegel.Settings.create ~test_cases:500 ~seed:5 () in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:csv-text" (fun tc ->
      let pick l = List.nth l (Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(List.length l - 1) ())) in
      let width = 1 + Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:3 ()) in
      let rows = Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:4 ()) in
      let field () = String.concat "" (List.init (Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:3 ())) (fun _ -> pick [ "a"; ","; "\""; "\r\n"; "\n"; "\r"; " "; "\xc3\xa9" ])) in
      let g = List.init rows (fun _ -> List.init width (fun _ -> field ())) in
      let text = X.print d g in
      Hegel.note tc (Printf.sprintf "%S" text);
      match X.parse d text with
      | g' -> Hegel.require tc ~msg:"the grid read back differently" (g' = g)
      | exception X.Bad m -> Hegel.require tc ~msg:("refused what it printed: " ^ m) false)

let written = ref 0 and unwritten = ref [] and read_back = ref 0

let rows_roundtrip () =
  Fun.protect ~finally:(fun () ->
      Printf.printf "%d tables written, %d rows read back alike; not written: %s\n" !written !read_back
        (String.concat "; " (List.sort_uniq compare !unwritten)))
  @@ fun () ->
  (* every kind of column, without constraints, so most drawn rows are valid *)
  let t = Lazy.force kinds in
  pools_for t.schema;
  strings := !strings @ [ ","; "\""; "a\r\nb"; "NA"; " x "; "yes" ];
  let settings = Hegel.Settings.create ~test_cases:500 ~seed:6 () in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:csv-rows" (fun tc ->
      let dr = Typed_gen.of_hegel tc in
      let rows = List.init (1 + dr.pick 3) (fun _ -> value t.imp dr 4 (Jsonschema.Named t.row)) in
      match X.write t rows with
      | Error m -> unwritten := (try String.sub m 0 (String.index m ':') with Not_found -> m) :: !unwritten; Hegel.note tc ("not written: " ^ m)
      | Ok text -> (
          incr written;
          Hegel.note tc (Printf.sprintf "%S" text);
          (* what the codec writes it reads back exactly when every row satisfies the schema and the keys
             are unique *)
          match X.read t text with
          | Ok back ->
              Hegel.require tc ~msg:"read back as different rows" (List.length back = List.length rows && List.for_all2 same back rows);
              read_back := !read_back + List.length back
          | Error m ->
              let valid = List.for_all (fun r -> match Jsonschema_codec.write t.imp t.row r with Ok j -> Result.is_ok (Jsonschema_codec.read t.imp t.row j) | Error _ -> false) rows in
              Hegel.note tc ("refused: " ^ m);
              (* a refusal must be one of the row's own schema, or a repeated key *)
              Hegel.require tc ~msg:("refused rows that satisfy their schema: " ^ m)
                ((not valid) || String.length m > 0 && (Schema_pred.replace ~sub:"repeats" ~by:"" m <> m))))

(* a row type the graph declares reads and writes as the record it is *)
let declared () =
  let src = "type Reading = { sensor : string<max 8>; value : double; ok : option(bool); n : option(int); };" in
  let file = match Cordis_graph.Parse.file src with Ok f -> f | Error m -> Alcotest.fail m in
  let t = T.of_record file "Reading" in
  let text = "sensor,value,ok,n\r\nt1,20.5,true,\r\nt2,-1,,3\r\n" in
  match X.read t text with
  | Error m -> Alcotest.fail m
  | Ok rows ->
      Alcotest.(check int) "rows" 2 (List.length rows);
      Alcotest.(check (result string string)) "written back" (Ok text) (X.write t rows);
      (match X.read t "sensor,value,ok,n\r\ntoolongname,1,,\r\n" with
       | Ok _ -> Alcotest.fail "a sensor over its <max 8> was read"
       | Error _ -> ());
      (match T.of_record file "Nope" with _ -> Alcotest.fail "no such record" | exception T.Refused _ -> ());
      let file2 = match Cordis_graph.Parse.file "type R = { at : timestamp; };" with Ok f -> f | Error m -> Alcotest.fail m in
      match T.of_record file2 "R" with _ -> Alcotest.fail "a timestamp column" | exception T.Refused _ -> ()

let () =
  Alcotest.run "csv"
    [ ("text", List.map (fun (s, want) -> Alcotest.test_case (Printf.sprintf "%S" s) `Quick (fun () -> Alcotest.check grid s want (parse s))) text_cases);
      ("imports", List.map (fun (sc, f) -> Alcotest.test_case f `Quick (refusal (sc, f))) refused);
      ("declared", [ Alcotest.test_case "a row type the graph declares" `Quick declared ]);
      ( "tables",
        List.map
          (fun (s, want) ->
            Alcotest.test_case (Printf.sprintf "%S" s) `Quick (fun () ->
                Alcotest.(check (result int string)) s want (shape (X.read (Lazy.force stations) s))))
          read_cases );
      ( "properties",
        [ Alcotest.test_case "a grid printed reads back as itself" `Quick grids;
          Alcotest.test_case "rows written read back as themselves" `Quick rows_roundtrip ] ) ]
