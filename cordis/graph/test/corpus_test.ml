(* The checker's corpus (docs/cordis/rfc/0002-graph-language.md, "Testing"):
   each corpus/*.cg file starts with "// expect: ok" or "// expect: <text>",
   a fragment the checker's errors must contain. Libraries and descriptor
   sets the programs attach are corpus/libs/*, found by their hash; a
   program names one as HASH(file) (corpus_store.ml). *)

open Cordis_graph

let dir = Sys.argv.(1)
let read path = In_channel.with_open_text path In_channel.input_all


let contains s frag =
  let n = String.length frag in
  let rec go i = i + n <= String.length s && (String.sub s i n = frag || go (i + 1)) in
  go 0

let case file () =
  let text = read (Filename.concat dir file) in
  let first = List.hd (String.split_on_char '\n' text) in
  let expect = String.trim (String.sub first 11 (String.length first - 11)) in
  let text = Corpus_store.substitute dir text in
  let outcome =
    match Parse.file text with
    | Error m -> Error [ "parse: " ^ m ]
    | Ok f -> Result.map (fun _ -> ()) (Check.check ~options:{ Check.default_options with store = Corpus_store.store dir } f)
  in
  match expect, outcome with
  | "ok", Ok () -> ()
  | "ok", Error es -> Alcotest.failf "%s: expected to check, got:\n  %s" file (String.concat "\n  " es)
  | frag, Ok () -> Alcotest.failf "%s: accepted, expected an error mentioning %S" file frag
  | frag, Error es ->
      if not (List.exists (fun e -> contains e frag) es) then
        Alcotest.failf "%s: no error mentions %S:\n  %s" file frag (String.concat "\n  " es)

let () =
  let files = Array.to_list (Sys.readdir dir) |> List.filter (fun f -> Filename.check_suffix f ".cg") |> List.sort compare in
  let ok, bad = List.partition (fun f -> String.length f > 3 && String.sub f 0 3 = "ok_") files in
  let tests fs = List.map (fun f -> Alcotest.test_case (Filename.chop_suffix f ".cg") `Quick (case f)) fs in
  Alcotest.run ~argv:[| Sys.argv.(0) |] "graph-corpus" [ ("accepted", tests ok); ("rejected", tests bad) ]
