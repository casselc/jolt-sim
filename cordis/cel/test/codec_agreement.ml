(* The boundary codec's agreement with protobuf itself
   (docs/cordis/rfc/0004-descriptor-native-types.md, "Testing"). For
   messages generated from their descriptors:

   - the codec reads back what it writes, in binary and in JSON;
   - protobuf (pbwire.py) reads both, strictly, to equal messages;
   - what protobuf writes back, in binary and in JSON, the codec reads to
     the message it started from.

     codec_agreement.exe PBWIRE.py SET.binpb... *)

open Cordis_cel
module V = Value

let settings =
  let cases = match Sys.getenv_opt "CODEC_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:29 ()

let pbwire = Sys.argv.(1)
let sets = Array.to_list (Array.sub Sys.argv 2 (Array.length Sys.argv - 2))

let registry =
  match Proto.of_descriptor_sets (List.map (fun p -> In_channel.with_open_bin p In_channel.input_all) sets) with
  | Ok r -> r
  | Error m -> failwith m

(* the messages under test: every feature of protobuf the corpus has *)
let types =
  [ "cel.expr.conformance.proto3.TestAllTypes"; "cel.expr.conformance.proto3.NestedTestAllTypes";
    "cel.expr.conformance.proto2.TestAllTypes"; "cel.expr.conformance.proto2.TestRequired";
    "acme.shop.v1.Order"; "acme.shop.v1.Category"; "legacy.Ledger"; "legacy.Money";
    "google.protobuf.Struct"; "google.protobuf.Timestamp"; "google.protobuf.FieldMask" ]

let proc = lazy (Unix.open_process_args "python3" (Array.of_list ("python3" :: pbwire :: sets)))

let protobuf t binary json =
  let ic, oc = Lazy.force proc in
  output_string oc
    (Yojson.Safe.to_string (`Assoc [ ("type", `String t); ("binary", `String (Ext_encoders.encode binary)); ("json", `String json) ]) ^ "\n");
  flush oc;
  match Yojson.Safe.from_string (input_line ic) with
  | `Assoc kv -> (
      match List.assoc_opt "ok" kv with
      | Some (`Bool true) ->
          let str k = match List.assoc_opt k kv with Some (`String s) -> s | _ -> "" in
          let b = match Ext_encoders.decode (str "binary") with Ok b -> b | Error e -> failwith e in
          let diff = match List.assoc_opt "diff" kv with Some (`List ds) -> List.map Yojson.Safe.to_string ds | _ -> [] in
          Ok ((List.assoc_opt "same" kv = Some (`Bool true), diff), b, str "json")
      | _ -> Error (match List.assoc_opt "error" kv with Some (`String e) -> e | _ -> "?"))
  | _ -> Error "?"

let same = Proto_gen.same

let test_agreement () =
  Proto_gen.null_value_elements := false;
  Proto.with_registry registry @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:codec-agreement" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let t = Typed_gen.choose d types in
      match Proto_gen.message d t with
      | None -> ()
      | Some v ->
          let req what = function Ok x -> x | Error e -> Hegel.require tc ~msg:(what ^ ": " ^ e) false; assert false in
          let binary = req "the codec could not write binary" (Proto_codec.encode t v) in
          let json = req "the codec could not write JSON" (Proto_codec.to_json_string t v) in
          Hegel.note tc (Printf.sprintf "%s\nvalue: %s\njson: %s\nbinary: %s" t (V.to_string v) json (Ext_encoders.encode binary));
          let back what r = match r with Ok x -> Hegel.require tc ~msg:(what ^ ": " ^ V.to_string x) (same x v) | Error e -> Hegel.require tc ~msg:(what ^ " refused: " ^ e) false in
          back "the codec's binary read back differently" (Proto_codec.decode t binary);
          back "the codec's JSON read back differently" (Proto_codec.of_json_string t json);
          match protobuf t binary json with
          | Error e -> Hegel.require tc ~msg:("protobuf refused what the codec wrote: " ^ e) false
          | Ok ((agree, diff), pb_binary, pb_json) ->
              Hegel.note tc ("protobuf's json: " ^ pb_json);
              Hegel.require tc ~msg:("protobuf read the binary and the JSON to different messages: " ^ String.concat "; " diff) agree;
              back "protobuf's binary read differently" (Proto_codec.decode t pb_binary);
              back "protobuf's JSON read differently" (Proto_codec.of_json_string t pb_json))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "codec-agreement"
    [ ("codec", [ Alcotest.test_case "the codec agrees with protobuf, both ways, binary and JSON" `Quick test_agreement ]) ]
