(* The validation predicate and the importer against the official JSON
   Schema Test Suite (docs/cordis/rfc/0003-types-importers-scaffolds.md,
   "The JSON Schema importer", "Testing"). For every test whose schema
   compiles, the predicate type-checks with the instance as dyn and
   evaluates to the suite's verdict. For every test whose schema imports,
   the types file checks and the codec reads the instance exactly when the
   suite says it is valid, but for the refusals the RFC documents (a
   property a closed record does not name, a non-object where an untyped
   schema has properties, an integer beyond int64), which are counted. Schemas that do not compile are listed, by file,
   description and reason; the list is the output, diffed against the
   checked-in expectation, so a schema that newly compiles, or newly does
   not, shows up.

     schema_suite.exe SUITE_DIR   (exits non-zero on any disagreement) *)

open Cordis_import
module J = Yojson.Safe
module C = Cordis_cel

let () =
  let dir = Sys.argv.(1) in
  let files = Sys.readdir dir |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare in
  let passed = ref 0 and failed = ref [] and skipped = ref [] and skipped_tests = ref 0 in
  let env = C.Checker.declare_ident (C.Checker.empty ()) "x" C.Checker.Dyn in
  List.iter
    (fun file ->
      match J.from_file (Filename.concat dir file) with
      | `List groups ->
          List.iter
            (fun g ->
              let get k = match g with `Assoc kv -> List.assoc_opt k kv | _ -> None in
              let desc = match get "description" with Some (`String d) -> d | _ -> "?" in
              let schema = Option.get (get "schema") in
              let tests = match get "tests" with Some (`List ts) -> ts | _ -> [] in
              match Schema_pred.compile schema "x" with
              | exception Schema_pred.Unsupported why ->
                  skipped := Printf.sprintf "%s: %s: %s" file desc why :: !skipped;
                  skipped_tests := !skipped_tests + List.length tests
              | src -> (
                  let checked =
                    match C.Cel.parse src with
                    | Error e -> Error ("does not parse: " ^ e)
                    | Ok ast -> (match C.Checker.check env ast with Ok _ -> Ok () | Error e -> Error ("does not check: " ^ e))
                  in
                  match checked with
                  | Error e -> failed := Printf.sprintf "%s: %s: the predicate %s\n  %s" file desc e src :: !failed
                  | Ok () ->
                      List.iter
                        (fun t ->
                          let get k = match t with `Assoc kv -> List.assoc_opt k kv | _ -> None in
                          let tdesc = match get "description" with Some (`String d) -> d | _ -> "?" in
                          let data = Option.get (get "data") and valid = get "valid" = Some (`Bool true) in
                          match C.Cel.eval ~vars:[ ("x", Schema_pred.value data) ] src with
                          | Ok { value = C.Value.Bool b; _ } when b = valid -> incr passed
                          | Ok { value; _ } ->
                              failed :=
                                Printf.sprintf "%s: %s: %s: %s, expected %b\n  data: %s\n  predicate: %s" file desc tdesc
                                  (C.Value.to_string value) valid (J.to_string data) src
                                :: !failed
                          | Error e -> failed := Printf.sprintf "%s: %s: %s: %s" file desc tdesc e :: !failed)
                        tests))
            groups
      | _ -> failwith (file ^ ": not a list of groups"))
    files;
  (* ---- the importer and codec ---- *)
  let read_agree = ref 0 and documented = ref 0 and not_imported = ref [] and refused_tests = ref 0 in
  let has e sub = let n = String.length sub in let rec go k = k + n <= String.length e && (String.sub e k n = sub || go (k + 1)) in go 0 in
  List.iter
    (fun file ->
      match J.from_file (Filename.concat dir file) with
      | `List groups ->
          List.iter
            (fun g ->
              let get k = match g with `Assoc kv -> List.assoc_opt k kv | _ -> None in
              let desc = match get "description" with Some (`String d) -> d | _ -> "?" in
              let schema = Option.get (get "schema") in
              let tests = match get "tests" with Some (`List ts) -> ts | _ -> [] in
              match Jsonschema.import schema with
              | exception Jsonschema.Refused why ->
                  not_imported := Printf.sprintf "%s: %s: %s" file desc why :: !not_imported;
                  refused_tests := !refused_tests + List.length tests
              | exception Schema_pred.Unsupported why ->
                  not_imported := Printf.sprintf "%s: %s: %s" file desc why :: !not_imported;
                  refused_tests := !refused_tests + List.length tests
              | imp -> (
                  let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
                  match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
                  | Error es -> failed := Printf.sprintf "%s: %s: the imported types do not check: %s" file desc (String.concat "; " es) :: !failed
                  | Ok _ ->
                      List.iter
                        (fun t ->
                          let get k = match t with `Assoc kv -> List.assoc_opt k kv | _ -> None in
                          let tdesc = match get "description" with Some (`String d) -> d | _ -> "?" in
                          let data = Option.get (get "data") and valid = get "valid" = Some (`Bool true) in
                          match Jsonschema_codec.read imp imp.root data, valid with
                          | Ok _, true | Error _, false -> incr read_agree
                          | Error e, true when has e " has no property " || has e " is a record: " || has e " is beyond int64" -> incr documented
                          | Ok _, false -> failed := Printf.sprintf "%s: %s: %s: the codec read an invalid instance %s" file desc tdesc (J.to_string data) :: !failed
                          | Error e, true -> failed := Printf.sprintf "%s: %s: %s: the codec refused a valid instance %s: %s" file desc tdesc (J.to_string data) e :: !failed)
                        tests))
            groups
      | _ -> ())
    files;
  Printf.printf "# The importer and codec: %d tests agree; %d valid instances are refused as RFC 0003 documents; %d tests in %d groups are not imported, as listed after the predicate's.\n"
    !read_agree !documented !refused_tests (List.length !not_imported);
  Printf.printf "# The validation predicate against the JSON Schema Test Suite (draft 2020-12)\n";
  Printf.printf "# %d tests agree; %d tests in %d groups are skipped: their schemas do not compile, as below.\n" !passed !skipped_tests (List.length !skipped);
  List.iter print_endline (List.rev !skipped);
  Printf.printf "# Not imported:\n";
  List.iter print_endline (List.rev !not_imported);
  if !failed <> [] then begin
    prerr_endline (Printf.sprintf "%d disagreements:" (List.length !failed));
    List.iter prerr_endline (List.rev !failed);
    exit 1
  end
