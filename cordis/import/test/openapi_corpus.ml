(* The OpenAPI importer over a sample of the APIs.guru OpenAPI directory
   (docs/cordis/rfc/0003-types-importers-scaffolds.md, revision 4,
   "Testing"): every sixth OpenAPI 3 document at a pinned commit
   (fetch_openapi_corpus.sh).

   Every document either imports, its types file passing the graph
   checker, or is refused with a reason; within an imported document,
   every operation is imported or skipped with a reason. The output is one
   line per document and a summary of the reasons, diffed against the
   checked-in expectation.

     openapi_corpus.exe DIR LIST [--expect GOLDEN] *)

open Cordis_import

let dir = Sys.argv.(1)
let list = Sys.argv.(2)
let read path = In_channel.with_open_bin path In_channel.input_all
let has e sub = let n = String.length sub in let rec go k = k + n <= String.length e && (String.sub e k n = sub || go (k + 1)) in go 0

(* a reason's kind, for the summary: the phrase that names it *)
let kinds =
  [ "uses a Unicode property class"; "uses lookaround"; "uses a backreference"; "has a lone surrogate"; "is not ASCII"; "does not compile"; "a recursive $ref"; "a remote $ref";
    "a dangling reference"; "a reference outside the document"; "described by content"; "a request body in"; "the style"; "two parameters named";
    "a parameter named body"; "neither JSON nor YAML"; "Swagger 2.0"; "only 3.0 and 3.1"; "no such anchor"; "keyword"; "a predicate" ]

let kind why = match List.find_opt (fun k -> has why k) kinds with Some k -> k | None -> why

let () =
  let paths = String.split_on_char '\n' (read list) |> List.filter (( <> ) "") in
  let lines = Buffer.create 4096 in
  let refused = Hashtbl.create 16 and skipped = Hashtbl.create 16 in
  let bump t k = Hashtbl.replace t k (1 + Option.value (Hashtbl.find_opt t k) ~default:0) in
  let docs_ok = ref 0 and ops_ok = ref 0 and ops_skipped = ref 0 and v30 = ref 0 and v31 = ref 0 in
  List.iter
    (fun p ->
      let t0 = Sys.time () in
      let line =
        match Openapi.document (read (Filename.concat dir p)) with
        | Error m -> bump refused (kind m); Printf.sprintf "%s: refused: %s" p (kind m)
        | Ok doc -> (
            (match doc with
             | `Assoc kv -> (match List.assoc_opt "openapi" kv with Some (`String v) when String.length v >= 3 && String.sub v 0 3 = "3.1" -> incr v31 | _ -> incr v30)
             | _ -> ());
            match Openapi.import doc with
            | exception Openapi.Refused m -> bump refused (kind m); Printf.sprintf "%s: refused: %s" p (kind m)
            | t -> (
                let skips = List.filter (fun (_, m) -> String.length m > 9 && String.sub m 0 9 = "skipped: ") t.report in
                List.iter (fun (_, m) -> bump skipped (kind m)) skips;
                let types = Cordis_graph.Print.file (Openapi.types_file t) in
                match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
                | Error es -> bump refused "the types do not check"; Printf.sprintf "%s: the types do not check: %s" p (List.hd es)
                | Ok _ ->
                    incr docs_ok;
                    ops_ok := !ops_ok + List.length t.ops;
                    ops_skipped := !ops_skipped + List.length skips;
                    Printf.sprintf "%s: ok, %d operations, %d skipped" p (List.length t.ops) (List.length skips)))
      in
      if Sys.getenv_opt "OPENAPI_CORPUS_PROGRESS" <> None then Printf.eprintf "%.1fs %s\n%!" (Sys.time () -. t0) line;
      Buffer.add_string lines (line ^ "\n"))
    paths;
  let table t = Hashtbl.fold (fun k n acc -> (k, n) :: acc) t [] |> List.sort (fun (a, n) (b, m) -> compare (m, a) (n, b)) in
  Buffer.add_string lines
    (Printf.sprintf "\nsummary: %d documents (%d 3.0, %d 3.1), %d imported, %d refused; %d operations imported, %d skipped\n" (List.length paths) !v30 !v31
       !docs_ok (List.length paths - !docs_ok) !ops_ok !ops_skipped);
  List.iter (fun (k, n) -> Buffer.add_string lines (Printf.sprintf "  refused %4d  %s\n" n k)) (table refused);
  List.iter (fun (k, n) -> Buffer.add_string lines (Printf.sprintf "  skipped %4d  %s\n" n k)) (table skipped);
  let out = Buffer.contents lines in
  match Array.to_list Sys.argv with
  | [ _; _; _; "--expect"; golden ] ->
      if out <> read golden then begin
        Out_channel.with_open_text "openapi_corpus.out" (fun oc -> output_string oc out);
        prerr_endline ("differs from " ^ golden ^ ": see openapi_corpus.out");
        exit 1
      end
  | _ -> print_string out
