(* The validation predicate and the importer against the official JSON
   Schema Test Suite (docs/cordis/rfc/0003-types-importers-scaffolds.md,
   "The JSON Schema importer", "Testing"), drafts 4 to 2020-12, the
   earlier drafts read through Schema_draft.

   - For every test whose schema compiles, the predicate type-checks with
     the instance as dyn and evaluates to the suite's verdict.
   - For every test whose schema imports, the types file checks and the
     codec reads the instance exactly when the suite says it is valid, but
     for the refusals the RFC documents (a property a closed record does
     not name, a non-object where an untyped schema has properties, an
     integer beyond int64), which are counted.

   The schemas that do not compile or import are listed, by draft, file,
   description and reason; the listing is the output, diffed against the
   checked-in expectation, so a schema that newly compiles or imports, or
   newly does not, shows up.

     schema_suite.exe SUITE_DIR   (exits non-zero on any disagreement) *)

open Cordis_import
module J = Yojson.Safe
module C = Cordis_cel

let failed = ref []
let fail fmt = Printf.ksprintf (fun m -> failed := m :: !failed) fmt
let has e sub = let n = String.length sub in let rec go k = k + n <= String.length e && (String.sub e k n = sub || go (k + 1)) in go 0
let documented e = has e " has no property " || has e " is a record: " || has e " is beyond int64"
let env = C.Checker.declare_ident (C.Checker.empty ()) "x" C.Checker.Dyn

let groups dir =
  Sys.readdir dir |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare
  |> List.concat_map (fun file ->
         match J.from_file (Filename.concat dir file) with
         | `List gs ->
             List.map
               (fun g ->
                 let get k = match g with `Assoc kv -> List.assoc_opt k kv | _ -> None in
                 let desc = match get "description" with Some (`String d) -> d | _ -> "?" in
                 let tests =
                   match get "tests" with
                   | Some (`List ts) ->
                       List.map
                         (fun t ->
                           let get k = match t with `Assoc kv -> List.assoc_opt k kv | _ -> None in
                           ((match get "description" with Some (`String d) -> d | _ -> "?"), Option.get (get "data"), get "valid" = Some (`Bool true)))
                         ts
                   | _ -> []
                 in
                 (file, desc, Option.get (get "schema"), tests))
               gs
         | _ -> failwith (file ^ ": not a list of groups"))

let run draft dir =
  let gs = groups dir in
  (* the predicate *)
  let passed = ref 0 and skipped = ref [] and skipped_tests = ref 0 in
  (* the importer and codec *)
  let agree = ref 0 and refused_valid = ref 0 and not_imported = ref [] and not_imported_tests = ref 0 in
  List.iter
    (fun (file, desc, schema, tests) ->
      let where = Printf.sprintf "%s/%s: %s" draft file desc in
      match Schema_draft.normalize ?default:(Schema_draft.draft_named draft) schema with
      | Error why ->
          skipped := Printf.sprintf "%s: %s" where why :: !skipped;
          skipped_tests := !skipped_tests + List.length tests;
          not_imported := Printf.sprintf "%s: %s" where why :: !not_imported;
          not_imported_tests := !not_imported_tests + List.length tests
      | Ok schema -> (
          (match Schema_pred.compile schema "x" with
           | exception Schema_pred.Unsupported why ->
               skipped := Printf.sprintf "%s: %s" where why :: !skipped;
               skipped_tests := !skipped_tests + List.length tests
           | src -> (
               match C.Cel.parse src with
               | Error e -> fail "%s: the predicate does not parse: %s\n  %s" where e src
               | Ok ast -> (
                   match C.Checker.check env ast with
                   | Error e -> fail "%s: the predicate does not check: %s\n  %s" where e src
                   | Ok _ ->
                       List.iter
                         (fun (tdesc, data, valid) ->
                           match C.Cel.eval ~vars:[ ("x", Schema_pred.value data) ] src with
                           | Ok { value = C.Value.Bool b; _ } when b = valid -> incr passed
                           | Ok { value; _ } ->
                               fail "%s: %s: the predicate gives %s, expected %b\n  data: %s\n  predicate: %s" where tdesc (C.Value.to_string value) valid (J.to_string data) src
                           | Error e -> fail "%s: %s: %s" where tdesc e)
                         tests)));
          match Jsonschema.import schema with
          | exception (Jsonschema.Refused why | Schema_pred.Unsupported why) ->
              not_imported := Printf.sprintf "%s: %s" where why :: !not_imported;
              not_imported_tests := !not_imported_tests + List.length tests
          | imp -> (
              let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
              match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
              | Error es -> fail "%s: the imported types do not check: %s" where (String.concat "; " es)
              | Ok _ ->
                  List.iter
                    (fun (tdesc, data, valid) ->
                      match Jsonschema_codec.read imp imp.root data, valid with
                      | Ok _, true | Error _, false -> incr agree
                      | Error e, true when documented e -> incr refused_valid
                      | Ok _, false -> fail "%s: %s: the codec read an invalid instance %s" where tdesc (J.to_string data)
                      | Error e, true -> fail "%s: %s: the codec refused a valid instance %s: %s" where tdesc (J.to_string data) e)
                    tests)))
    gs;
  Printf.printf "# %s: the predicate: %d tests agree; %d tests in %d groups do not compile.\n" draft !passed !skipped_tests (List.length !skipped);
  Printf.printf "# %s: the importer and codec: %d tests agree; %d valid instances are refused as RFC 0003 documents; %d tests in %d groups do not import.\n"
    draft !agree !refused_valid !not_imported_tests (List.length !not_imported);
  (List.rev !skipped, List.rev !not_imported)

let () =
  let root = Sys.argv.(1) in
  let drafts = [ "draft2020-12"; "draft2019-09"; "draft7"; "draft6"; "draft4" ] in
  let results = List.map (fun d -> (d, run d (Filename.concat root d))) drafts in
  List.iter
    (fun (d, (skipped, not_imported)) ->
      Printf.printf "\n## %s: schemas the predicate does not compile\n" d;
      List.iter print_endline skipped;
      Printf.printf "\n## %s: schemas that do not import\n" d;
      List.iter print_endline not_imported)
    results;
  if !failed <> [] then begin
    prerr_endline (Printf.sprintf "%d disagreements:" (List.length !failed));
    List.iter prerr_endline (List.rev !failed);
    exit 1
  end
