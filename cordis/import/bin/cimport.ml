(* cimport: schema importers (docs/cordis/rfc/0003-types-importers-scaffolds.md).

     cimport protobuf SET.binpb [--out DIR | --print types|report|names]
     cimport jsonschema SCHEMA.json [--with FILE|DIR]... [--out DIR | --print types|report|names]
     cimport openapi DOC.(json|yaml) [--service NAME] [--out DIR | --print types|report|names|operations|allowlist|pf]
     cimport tableschema SCHEMA.json [--name ROW] [--dialect DIALECT.json] [--out DIR | --print types|report|names]
     cimport csvrow TYPES.cg ROW [--dialect DIALECT.json] [--print names]
     cimport csvinfer SAMPLE.csv   (a Table Schema guessed for review, and what was seen)
     cimport xsd SCHEMA.xsd [--root ELEMENT] [--out DIR | --print types|report|names]

   reads a FileDescriptorSet and writes DIR/types.cg (the types and their
   check_ cells), DIR/report.txt (what was approximated) and DIR/names.json
   (the name map); without --out it prints the types file and the report.
   For OpenAPI it also writes DIR/operations.json, the operation map the
   outbox's http executor reads. A Frictionless Table Schema (RFC 0005
   revision 3) imports a CSV table's row; its name map adds the dialect and
   the columns the CSV codec reads.

   For protobuf this is a report tool: it shows what a schema would lose as
   graph-language records. Graphs use protobuf types directly, attaching
   the set with `uses descriptors sha256:<hex>;` (RFC 0004); --print
   attach prints that line for SET. *)

open Cordis_import

let read path = In_channel.with_open_bin path In_channel.input_all

let die fmt = Printf.ksprintf (fun m -> prerr_endline m; exit 1) fmt

(* the output must check on its own, with no budget: the budget belongs to
   the graph that uses it *)
let check_output types =
  match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
  | Ok _ -> ()
  | Error es ->
      if Sys.getenv_opt "CIMPORT_DUMP" <> None then print_string types;
      die "the imported types do not check:\n  %s" (String.concat "\n  " es)

let emit rest types report names =
  match rest with
  | [ "--print"; "types" ] -> print_string types
  | [ "--print"; "report" ] -> print_string report
  | [ "--print"; "names" ] -> print_string names
  | [ "--out"; dir ] ->
      let write name s = Out_channel.with_open_text (Filename.concat dir name) (fun oc -> output_string oc s) in
      write "types.cg" types;
      write "report.txt" report;
      write "names.json" names
  | [] -> print_string types; print_string "\n// ---- report\n"; print_string report
  | _ -> die "usage: cimport (protobuf SET.binpb | jsonschema SCHEMA.json [--with FILE|DIR]...) [--out DIR | --print types|report|names]"

let read_doc path = match Openapi.document (read path) with Ok j -> j | Error m -> die "%s: %s" path m

let () =
  match Array.to_list Sys.argv with
  | _ :: "openapi" :: path :: rest -> (
      let service, rest = match rest with "--service" :: s :: r -> (Some s, r) | r -> (None, r) in
      match Openapi.import ?service (read_doc path) with
      | exception Openapi.Refused m -> die "%s: %s" path m
      | t ->
          let types = Cordis_graph.Print.file (Openapi.types_file t) in
          check_output types;
          let report = String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) t.report) in
          let names = Yojson.Safe.pretty_to_string (Jsonschema.name_map t.imp) ^ "\n" in
          let operations = Yojson.Safe.pretty_to_string (Openapi.operations t) ^ "\n" in
          match rest with
          | [ "--print"; "operations" ] -> print_string operations
          | [ "--print"; "allowlist" ] -> print_string (Yojson.Safe.pretty_to_string (Openapi.allowlist_json t) ^ "\n")
          | [ "--print"; "pf" ] -> print_string (Openapi.pf t)
          | [ "--out"; dir ] ->
              emit rest types report names;
              Out_channel.with_open_text (Filename.concat dir "operations.json") (fun oc -> output_string oc operations);
              Out_channel.with_open_text (Filename.concat dir "allowlist.json") (fun oc ->
                  output_string oc (Yojson.Safe.pretty_to_string (Openapi.allowlist_json t) ^ "\n"));
              Out_channel.with_open_text (Filename.concat dir "egress.pf") (fun oc -> output_string oc (Openapi.pf t))
          | _ -> emit rest types report names)
  | _ :: "jsonschema" :: path :: rest -> (
      (* --with FILE|DIR: further documents references may name, each
         under its $id (or its file: URI) *)
      let rec split acc = function
        | "--with" :: p :: r -> split (p :: acc) r
        | r -> (List.rev acc, r)
      in
      let withs, rest = split [] rest in
      let files =
        List.concat_map
          (fun p ->
            if Sys.is_directory p then
              Sys.readdir p |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare |> List.map (Filename.concat p)
            else [ p ])
          withs
      in
      let file_uri p = "file://" ^ (if Filename.is_relative p then Filename.concat (Sys.getcwd ()) p else p) in
      let others =
        List.filter_map
          (fun p ->
            match Yojson.Safe.from_string (read p) with
            | d -> (match Schema_draft.normalize d with Ok d -> Some (file_uri p, d) | Error _ -> None)
            | exception Yojson.Json_error _ -> None)
          files
      in
      match Yojson.Safe.from_string (read path) with
      | exception Yojson.Json_error m -> die "%s: not JSON: %s" path m
      | doc -> (
          let doc = match Schema_draft.normalize doc with Ok d -> d | Error m -> die "%s: %s" path m in
          let stem = Filename.remove_extension (Filename.remove_extension (Filename.basename path)) in
          match Jsonschema.import ~name:stem ~uri:(file_uri path) ~others doc with
          | exception Jsonschema.Refused m -> die "%s: %s" path m
          | imp ->
              let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
              check_output types;
              emit rest types
                (String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) imp.report))
                (Yojson.Safe.pretty_to_string (Jsonschema.name_map imp) ^ "\n")))
  | _ :: "tableschema" :: path :: rest -> (
      let rec opts name dialect = function
        | "--name" :: n :: r -> opts n dialect r
        | "--dialect" :: d :: r -> opts name (Some d) r
        | r -> (name, dialect, r)
      in
      let stem = Filename.remove_extension (Filename.basename path) in
      let name, dialect, rest = opts (String.capitalize_ascii stem) None rest in
      let json p = match Yojson.Safe.from_string (read p) with j -> j | exception Yojson.Json_error m -> die "%s: not JSON: %s" p m in
      match
        let dialect = Option.map (fun d -> Table_schema.dialect_of (json d)) dialect in
        Table_schema.import ~name ?dialect (json path)
      with
      | exception Table_schema.Refused m -> die "%s: %s" path m
      | t ->
          let types = Cordis_graph.Print.file (Jsonschema.types_file t.imp) in
          check_output types;
          emit rest types
            (String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) t.report))
            (Yojson.Safe.pretty_to_string (Table_schema.name_map t) ^ "\n"))
  | _ :: "csvrow" :: path :: row :: rest -> (
      (* a row type the graph declares: the CSV name map for it, which the codec reads *)
      let dialect, rest = match rest with "--dialect" :: d :: r -> (Some d, r) | r -> (None, r) in
      let file = match Cordis_graph.Parse.file (read path) with Ok f -> f | Error m -> die "%s: %s" path m in
      match
        let dialect = Option.map (fun d -> Table_schema.dialect_of (Yojson.Safe.from_string (read d))) dialect in
        Table_schema.of_record ?dialect file row
      with
      | exception Table_schema.Refused m -> die "%s: %s" path m
      | t -> (
          match rest with
          | [] | [ "--print"; "names" ] -> print_string (Yojson.Safe.pretty_to_string (Table_schema.name_map t) ^ "\n")
          | _ -> die "usage: cimport csvrow TYPES.cg ROW [--dialect DIALECT.json] [--print names]"))
  | [ _; "csvinfer"; sample ] -> (
      match Csv_codec.infer (read sample) with
      | exception Csv_codec.Bad m -> die "%s: %s" sample m
      | schema, notes ->
          print_string (Yojson.Safe.pretty_to_string schema ^ "\n");
          List.iter (fun n -> prerr_endline ("inferred " ^ n)) notes)
  | _ :: "xsd" :: path :: rest -> (
      let root, rest = match rest with "--root" :: r :: rest -> (Some r, rest) | rest -> (None, rest) in
      match Xsd.of_string ?root (read path) with
      | exception Xsd.Refused m -> die "%s: %s" path m
      | t ->
          let types = Cordis_graph.Print.file (Jsonschema.types_file t.imp) in
          check_output types;
          emit rest types
            (String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) t.report))
            (Yojson.Safe.pretty_to_string (Xsd.name_map t) ^ "\n"))
  | [ _; "protobuf"; set; "--print"; "attach" ] ->
      Printf.printf "uses descriptors sha256:%s;\n" (Cordis_cel.Sha256.digest (read set))
  | _ :: "protobuf" :: set :: rest -> (
      match Descriptor.set_of_string (read set) with
      | Error m -> die "%s: %s" set m
      | Ok files ->
          let imp = Protobuf.import files in
          let types = Cordis_graph.Print.file (Protobuf.types_file imp) in
          let report = String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) imp.report) in
          (* the output must check on its own; a residue check over the
             default budget is reported, not refused: the budget belongs to
             the graph that uses it *)
          let report =
            match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
            | Error es ->
                if Sys.getenv_opt "CIMPORT_DUMP" <> None then print_string types;
                die "the imported types do not check:\n  %s" (String.concat "\n  " es)
            | Ok s ->
                let budget = Cordis_graph.Check.default_options.budget in
                report
                ^ String.concat ""
                    (List.filter_map
                       (fun (c : Cordis_graph.Check.cell_info) ->
                         match c.turns with
                         | [ { cost = Some e; _ } ] when e.max > budget ->
                             Some
                               (Printf.sprintf
                                  "%s: costs up to %s with the default size ceiling, over the default budget of %d: bound its collections, or raise the budget\n"
                                  c.cname (if e.max = Cordis_cel.Cost.inf then "an unbounded amount" else string_of_int e.max) budget)
                         | _ -> None)
                       s.checked.cells)
          in
          match rest with
          | [ "--print"; "types" ] -> print_string types
          | [ "--print"; "report" ] -> print_string report
          | [ "--print"; "names" ] -> print_string (Yojson.Safe.pretty_to_string (Protobuf.name_map imp) ^ "\n")
          | [ "--out"; dir ] ->
              let write name s = Out_channel.with_open_text (Filename.concat dir name) (fun oc -> output_string oc s) in
              write "types.cg" types;
              write "report.txt" report;
              write "names.json" (Yojson.Safe.pretty_to_string (Protobuf.name_map imp) ^ "\n")
          | [] -> print_string types; print_string "\n// ---- report\n"; print_string report
          | _ -> die "usage: cimport protobuf SET.binpb [--out DIR | --print types|report|names|attach]")
  | _ -> die "usage: cimport protobuf SET.binpb [--out DIR | --print types|report|names|attach]"
