(* cimport: schema importers (docs/cordis/rfc/0003-types-importers-scaffolds.md).

     cimport protobuf SET.binpb [--out DIR | --print types|report|names]
     cimport jsonschema SCHEMA.json [--out DIR | --print types|report|names]

   reads a FileDescriptorSet and writes DIR/types.cg (the types and their
   check_ cells), DIR/report.txt (what was approximated) and DIR/names.json
   (the name map); without --out it prints the types file and the report.

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
  | _ -> die "usage: cimport (protobuf SET.binpb | jsonschema SCHEMA.json) [--out DIR | --print types|report|names]"

let () =
  match Array.to_list Sys.argv with
  | _ :: "jsonschema" :: path :: rest -> (
      match Yojson.Safe.from_string (read path) with
      | exception Yojson.Json_error m -> die "%s: not JSON: %s" path m
      | doc -> (
          let doc = match Schema_draft.normalize doc with Ok d -> d | Error m -> die "%s: %s" path m in
          let stem = Filename.remove_extension (Filename.remove_extension (Filename.basename path)) in
          match Jsonschema.import ~name:stem doc with
          | exception Jsonschema.Refused m -> die "%s: %s" path m
          | imp ->
              let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
              check_output types;
              emit rest types
                (String.concat "" (List.map (fun (l, m) -> Printf.sprintf "%s: %s\n" l m) imp.report))
                (Yojson.Safe.pretty_to_string (Jsonschema.name_map imp) ^ "\n")))
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
