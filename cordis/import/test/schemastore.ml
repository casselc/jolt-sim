(* The JSON Schema importer over SchemaStore (https://www.schemastore.org;
   its repository's src/schemas/json and the instances in src/test and
   src/negative_test), the largest public corpus of real schemas
   (docs/cordis/rfc/0003-types-importers-scaffolds.md, "Testing"):

   - every schema either imports, its types file passing the graph
     checker, or is refused with a reason;
   - every instance SchemaStore keeps for an imported schema is read by
     the codec exactly when Python's jsonschema accepts it (each schema
     under its own draft), but for the refusals the RFC documents, which
     are counted.

   The output is one line per schema and a summary, diffed against the
   checked-in expectation at a pinned commit (fetch_schemastore.sh).

     schemastore.exe SCHEMASTORE_DIR JSVALID.py [--expect GOLDEN] *)

open Cordis_import
module J = Yojson.Safe

let dir = Sys.argv.(1)
let jsvalid = Sys.argv.(2)
let proc = lazy (Unix.open_process_args "python3" [| "python3"; jsvalid |])

let jsonschema schema instance =
  let ic, oc = Lazy.force proc in
  output_string oc (J.to_string (`Assoc [ ("schema", schema); ("instance", instance) ]) ^ "\n");
  flush oc;
  match J.from_string (input_line ic) with
  | `Assoc [ ("valid", `Bool b) ] -> Ok b
  | `Assoc [ ("error", `String e) ] -> Error e
  | j -> Error (J.to_string j)

let has e sub = let n = String.length sub in let rec go k = k + n <= String.length e && (String.sub e k n = sub || go (k + 1)) in go 0
let documented e = has e " has no property " || has e " is a record: " || has e " is beyond int64" || has e " given twice"

let instances sub stem =
  let d = Filename.concat (Filename.concat dir sub) stem in
  if Sys.file_exists d && Sys.is_directory d then
    Sys.readdir d |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare
    |> List.filter_map (fun f -> match J.from_file (Filename.concat d f) with j -> Some (f, j) | exception _ -> None)
  else []

(* a refusal's kind, for the summary: the reason without its location *)
let kind why =
  let why = match String.index_opt why ':' with Some i when i < 60 && not (has (String.sub why 0 i) " ") -> String.trim (String.sub why (i + 1) (String.length why - i - 1)) | _ -> why in
  let cut = [ "("; "\""; "#"; "http" ] in
  let first = List.fold_left (fun acc c -> match String.index_opt why c.[0] with Some i when i < acc -> i | _ -> acc) (String.length why) cut in
  String.trim (String.sub why 0 first)

let () =
  let schemas_dir = Filename.concat dir "src/schemas/json" in
  let files = Sys.readdir schemas_dir |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare in
  let imported = ref 0 and refused = Hashtbl.create 16 and failures = ref [] in
  let agree = ref 0 and documented_n = ref 0 and positive = ref 0 and negative = ref 0 and unvalidated = ref 0 in
  let lines =
    List.map
      (fun f ->
        let stem = Filename.chop_suffix f ".json" in
        let t0 = Unix.gettimeofday () in
        Fun.protect ~finally:(fun () -> if Sys.getenv_opt "SCHEMASTORE_PROGRESS" <> None then Printf.eprintf "%s %.1fs\n%!" stem (Unix.gettimeofday () -. t0)) @@ fun () ->
        let refuse why =
          let k = kind why in
          Hashtbl.replace refused k (1 + Option.value (Hashtbl.find_opt refused k) ~default:0);
          Printf.sprintf "%s: refused: %s" stem why
        in
        match J.from_file (Filename.concat schemas_dir f) with
        | exception e -> refuse ("not JSON: " ^ Printexc.to_string e)
        | original -> (
            match Schema_draft.normalize original with
            | Error why -> refuse why
            | Ok doc -> (
                match Jsonschema.import ~name:stem doc with
                | exception (Jsonschema.Refused why | Schema_pred.Unsupported why) -> refuse why
                | exception e -> failures := Printf.sprintf "%s: the importer raised %s" stem (Printexc.to_string e) :: !failures; stem ^ ": raised"
                | imp -> (
                    let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
                    match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
                    | Error es ->
                        failures := Printf.sprintf "%s: the imported types do not check: %s" stem (String.concat "; " es) :: !failures;
                        stem ^ ": the types do not check"
                    | Ok _ ->
                        incr imported;
                        let tried = ref 0 in
                        List.iter
                          (fun (sub, expect) ->
                            List.iter
                              (fun (name, inst) ->
                                incr tried;
                                if expect then incr positive else incr negative;
                                match jsonschema original inst with
                                | Error _ -> incr unvalidated
                                | Ok valid -> (
                                    match Jsonschema_codec.read imp imp.root inst, valid with
                                    | Ok _, true | Error _, false -> incr agree
                                    | Error e, true when documented e -> incr documented_n
                                    | Ok _, false -> failures := Printf.sprintf "%s: %s/%s: the codec read an instance jsonschema refuses" stem sub name :: !failures
                                    | Error e, true -> failures := Printf.sprintf "%s: %s/%s: the codec refused an instance jsonschema accepts: %s" stem sub name e :: !failures))
                              (instances sub stem))
                          [ ("src/test", true); ("src/negative_test", false) ];
                        Printf.sprintf "%s: imported, %d types, %d instances" stem (List.length imp.defs) !tried))))
      files
  in
  let out = Buffer.create 65536 in
  let printf fmt = Printf.bprintf out fmt in
  printf "# SchemaStore: %d schemas; %d import (their types check); %d are refused.\n" (List.length files) !imported (List.length files - !imported);
  printf "# Instances of imported schemas: %d positive, %d negative; %d agree with jsonschema; %d valid ones are refused as RFC 0003 documents; %d jsonschema could not validate.\n"
    !positive !negative !agree !documented_n !unvalidated;
  printf "# Refusals by kind:\n";
  Hashtbl.fold (fun k n acc -> (n, k) :: acc) refused [] |> List.sort (fun a b -> compare b a) |> List.iter (fun (n, k) -> printf "#   %4d  %s\n" n k);
  List.iter (fun l -> printf "%s\n" l) lines;
  let text = Buffer.contents out in
  print_string text;
  (* with --expect, the output must be the checked-in one *)
  (match Array.to_list Sys.argv with
   | [ _; _; _; "--expect"; golden ] ->
       let want = In_channel.with_open_bin golden In_channel.input_all in
       if want <> text then failures := Printf.sprintf "the output differs from %s (rerun without --expect to see it)" golden :: !failures
   | _ -> ());
  if !failures <> [] then begin
    prerr_endline (Printf.sprintf "%d disagreements:" (List.length !failures));
    List.iter prerr_endline (List.rev !failures);
    exit 1
  end
