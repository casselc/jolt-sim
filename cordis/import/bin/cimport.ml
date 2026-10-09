(* cimport: schema importers (docs/cordis/rfc/0003-types-importers-scaffolds.md).

     cimport protobuf SET.binpb [--out DIR | --print types|report|names]

   reads a FileDescriptorSet and writes DIR/types.cg (the types and their
   check_ cells), DIR/report.txt (what was approximated) and DIR/names.json
   (the name map); without --out it prints the types file and the report. *)

open Cordis_import

let read path = In_channel.with_open_bin path In_channel.input_all

let die fmt = Printf.ksprintf (fun m -> prerr_endline m; exit 1) fmt

let () =
  match Array.to_list Sys.argv with
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
          | _ -> die "usage: cimport protobuf SET.binpb [--out DIR | --print types|report|names]")
  | _ -> die "usage: cimport protobuf SET.binpb [--out DIR | --print types|report|names]"
