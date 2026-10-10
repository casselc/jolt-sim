(* The boundary codec by example (docs/cordis/rfc/0004-descriptor-native-
   types.md, "The boundary codec"): what protobuf's binary and JSON may say
   that the codec never writes, and what reading must refuse. The agreement
   property covers what the codec writes; these cover the rest of the input
   space a trust boundary sees.

     codec_test.exe SET.binpb... *)

open Cordis_cel
module V = Value

let registry =
  let sets = Array.to_list (Array.sub Sys.argv 1 (Array.length Sys.argv - 1)) in
  match Proto.of_descriptor_sets (List.map (fun p -> In_channel.with_open_bin p In_channel.input_all) sets) with
  | Ok r -> r
  | Error m -> failwith m

let p3 = "cel.expr.conformance.proto3.TestAllTypes"
let p2 = "cel.expr.conformance.proto2.TestAllTypes"

(* ---- wire bytes, by hand ---------------------------------------------------------------- *)

let varint n =
  let b = Buffer.create 10 in
  let rec go n =
    if Int64.unsigned_compare n 0x80L < 0 then Buffer.add_char b (Char.chr (Int64.to_int n))
    else (Buffer.add_char b (Char.chr (Int64.to_int (Int64.logand n 0x7fL) lor 0x80)); go (Int64.shift_right_logical n 7))
  in
  go n;
  Buffer.contents b

let tag num wire = varint (Int64.of_int ((num lsl 3) lor wire))
let vi num n = tag num 0 ^ varint n
let ld num s = tag num 2 ^ varint (Int64.of_int (String.length s)) ^ s

(* ---- checks --------------------------------------------------------------------------------- *)

let in_registry f () = Proto.with_registry registry f

let field v name =
  match v with
  | V.Message { fields; _ } -> List.assoc_opt name fields
  | v -> Alcotest.failf "not a message: %s" (V.to_string v)

let value = Alcotest.testable (fun f v -> Format.pp_print_string f (V.to_string v)) V.equal
let some_value = Alcotest.option value

let json_reads t json name want =
  in_registry @@ fun () ->
  match Proto_codec.of_json_string t json with
  | Ok v -> Alcotest.check some_value (json ^ " ." ^ name) want (field v name)
  | Error e -> Alcotest.failf "refused %s: %s" json e

let binary_reads t bytes name want =
  in_registry @@ fun () ->
  match Proto_codec.decode t bytes with
  | Ok v -> Alcotest.check some_value ("." ^ name) want (field v name)
  | Error e -> Alcotest.failf "refused: %s" e

let mentions e fragment =
  let n = String.length fragment in
  let rec go i = i + n <= String.length e && (String.sub e i n = fragment || go (i + 1)) in
  if not (go 0) then Alcotest.failf "error %S does not mention %S" e fragment

let refused what fragment = function
  | Ok v -> Alcotest.failf "%s accepted: %s" what (V.to_string v)
  | Error e -> mentions e fragment

let json_refuses t json fragment = in_registry @@ fun () -> refused json fragment (Proto_codec.of_json_string t json)
let binary_refuses t bytes fragment = in_registry @@ fun () -> refused "the bytes" fragment (Proto_codec.decode t bytes)

let write_refuses t v fragment =
  in_registry @@ fun () ->
  match Proto_codec.to_json_string t v with Ok j -> Alcotest.failf "wrote %s" j | Error e -> mentions e fragment

let nested bb = V.Message { tname = p3 ^ ".NestedMessage"; fields = [ ("bb", V.Int bb) ] }
let i n = Some (V.Int n)

(* ---- JSON ----------------------------------------------------------------------------------- *)

let json_reading =
  [ ("null is absent", `Quick, json_reads p3 {|{"single_int32": null}|} "single_int32" None);
    ("a field by its JSON name", `Quick, json_reads p3 {|{"singleInt32": 5}|} "single_int32" (i 5L));
    ("an int64 as a number", `Quick, json_reads p3 {|{"single_int64": 12}|} "single_int64" (i 12L));
    ("an int64 as a string", `Quick, json_reads p3 {|{"single_int64": "-9223372036854775808"}|} "single_int64" (i Int64.min_int));
    ("an int32 as a string", `Quick, json_reads p3 {|{"single_int32": "-7"}|} "single_int32" (i (-7L)));
    ("an int32 as an integral float", `Quick, json_reads p3 {|{"single_int32": 1e3}|} "single_int32" (i 1000L));
    ("a uint64 as a string", `Quick, json_reads p3 {|{"single_uint64": "18446744073709551615"}|} "single_uint64" (Some (V.Uint (-1L))));
    ("a double as \"NaN\"", `Quick, in_registry @@ fun () ->
        match Proto_codec.of_json_string p3 {|{"single_double": "NaN"}|} with
        | Ok v -> (match field v "single_double" with Some (V.Double d) when Float.is_nan d -> () | _ -> Alcotest.fail "not NaN")
        | Error e -> Alcotest.fail e);
    ("a float at its largest, as protobuf prints it", `Quick, json_reads p3 {|{"single_float": 3.4028235e+38}|} "single_float" (Some (V.Double 3.4028234663852886e38)));
    ("bytes, URL-safe and unpadded", `Quick, json_reads p3 {|{"single_bytes": "-_8"}|} "single_bytes" (Some (V.Bytes "\xfb\xff")));
    ("an enum by number", `Quick, json_reads p3 {|{"standalone_enum": 2}|} "standalone_enum" (i 2L));
    ("an enum by name", `Quick, json_reads p3 {|{"standalone_enum": "BAZ"}|} "standalone_enum" (i 2L));
    ("NullValue elements as null", `Quick, json_reads p3 {|{"repeated_null_value": [null, "NULL_VALUE"]}|} "repeated_null_value" (Some (V.List [| V.Int 0L; V.Int 0L |])));
    ("NullValue map values as null", `Quick, json_reads p3 {|{"map_int32_null_value": {"4": null}}|} "map_int32_null_value" (Some (V.make_map [ (V.Int 4L, V.Int 0L) ])));
    ("a Value's null is a value", `Quick, json_reads p3 {|{"single_value": null}|} "single_value" (Some V.Null));
    ("a duration with a fraction", `Quick, json_reads p3 {|{"single_duration": "-1.5s"}|} "single_duration" (Some (V.Duration (-1L, -500_000_000))));
    ("a timestamp with an offset", `Quick, json_reads p3 {|{"single_timestamp": "1970-01-01T01:00:00+01:00"}|} "single_timestamp" (Some (V.Timestamp (0L, 0))));
    ("an Any holding a well-known type", `Quick, json_reads p3 {|{"single_any": {"@type": "type.googleapis.com/google.protobuf.Int32Value", "value": 7}}|} "single_any" (i 7L));
    ("an Any holding a message", `Quick, json_reads p3 {|{"single_any": {"@type": "type.googleapis.com/cel.expr.conformance.proto3.TestAllTypes.NestedMessage", "bb": 3}}|} "single_any" (Some (nested 3L)));
    ("a FieldMask in lowerCamel", `Quick, json_reads p3 {|{"field_mask": "fooBar,a.bC"}|} "field_mask"
       (Some (V.Message { tname = "google.protobuf.FieldMask"; fields = [ ("paths", V.List [| V.String "foo_bar"; V.String "a.b_c" |]) ] })));
    ("a group by its field name", `Quick, json_reads p2 {|{"nestedgroup": {"single_id": 4}}|} "nestedgroup"
       (Some (V.Message { tname = p2 ^ ".NestedGroup"; fields = [ ("single_id", V.Int 4L) ] }))) ]

let json_refusing =
  [ ("an unknown field", `Quick, json_refuses p3 {|{"nope": 1}|} "has no field nope");
    ("a field given twice, by both names", `Quick, json_refuses p3 {|{"single_int32": 1, "singleInt32": 2}|} "given twice");
    ("two members of one oneof", `Quick, json_refuses p3 {|{"oneof_bool": true, "oneof_msg": {}}|} "two members of oneof kind");
    ("an unknown enum name", `Quick, json_refuses p3 {|{"standalone_enum": "QUX"}|} "has no value QUX");
    ("an unknown enum number", `Quick, json_refuses p3 {|{"standalone_enum": 9}|} "has no value numbered 9");
    ("an int32 out of range", `Quick, json_refuses p3 {|{"single_int32": 2147483648}|} "out of range");
    ("an int32 that is not integral", `Quick, json_refuses p3 {|{"single_int32": 1.5}|} "not an integer");
    ("a uint32 out of range", `Quick, json_refuses p3 {|{"single_uint32": 4294967296}|} "out of range");
    ("a negative uint64", `Quick, json_refuses p3 {|{"single_uint64": -1}|} "out of range");
    ("a float out of range", `Quick, json_refuses p3 {|{"single_float": 1e39}|} "out of range for float");
    ("a bool as a string", `Quick, json_refuses p3 {|{"single_bool": "true"}|} "not a bool");
    ("a null element", `Quick, json_refuses p3 {|{"repeated_int32": [1, null]}|} "null element");
    ("a duration in Go's form", `Quick, json_refuses p3 {|{"single_duration": "1h"}|} "not a Duration");
    ("a duration out of range", `Quick, json_refuses p3 {|{"single_duration": "315576000001s"}|} "out of range");
    ("a timestamp with ten fraction digits", `Quick, json_refuses p3 {|{"single_timestamp": "1970-01-01T00:00:00.0000000001Z"}|} "fraction");
    ("an Any of an unknown type", `Quick, json_refuses p3 {|{"single_any": {"@type": "type.googleapis.com/no.Such"}}|} "unknown message type no.Such");
    ("an Any without @type", `Quick, json_refuses p3 {|{"single_any": {"bb": 1}}|} "without @type");
    ("a duplicate map key", `Quick, json_refuses p3 {|{"map_string_string": {"a": "x", "a": "y"}}|} "duplicate key");
    ("a missing required field", `Quick, json_refuses "cel.expr.conformance.proto2.TestRequired" {|{}|} "required field required_int32");
    ("a FieldMask path with an underscore", `Quick, json_refuses p3 {|{"field_mask": "foo_bar"}|} "underscore") ]

let json_writing =
  [ ("a Value cannot hold NaN", `Quick, write_refuses "google.protobuf.Value" (V.Double Float.nan) "cannot hold");
    ("a FieldMask path with no JSON form", `Quick,
     write_refuses "google.protobuf.FieldMask"
       (V.Message { tname = "google.protobuf.FieldMask"; fields = [ ("paths", V.List [| V.String "fooBar" |]) ] })
       "no JSON form");
    ("fields with no presence are printed; others are not", `Quick, in_registry @@ fun () ->
        match Proto_codec.to_json p3 (V.Message { tname = p3; fields = [] }) with
        | Ok (`Assoc kv) ->
            Alcotest.(check bool) "single_int32 printed" true (List.assoc_opt "single_int32" kv = Some (`Int 0));
            Alcotest.(check bool) "single_int64 as a string" true (List.assoc_opt "single_int64" kv = Some (`String "0"));
            Alcotest.(check bool) "repeated_int32 printed" true (List.assoc_opt "repeated_int32" kv = Some (`List []));
            Alcotest.(check bool) "optional_string not printed" false (List.mem_assoc "optional_string" kv);
            Alcotest.(check bool) "a message not printed" false (List.mem_assoc "single_nested_message" kv)
        | Ok j -> Alcotest.failf "not an object: %s" (Yojson.Safe.to_string j)
        | Error e -> Alcotest.fail e) ]

(* ---- binary --------------------------------------------------------------------------------- *)

let binary_reading =
  [ ("repeated scalars unpacked", `Quick, binary_reads p3 (vi 31 1L ^ vi 31 2L) "repeated_int32" (Some (V.List [| V.Int 1L; V.Int 2L |])));
    ("repeated scalars packed", `Quick, binary_reads p3 (ld 31 (varint 1L ^ varint 2L)) "repeated_int32" (Some (V.List [| V.Int 1L; V.Int 2L |])));
    ("a singular field's last value wins", `Quick, binary_reads p3 (vi 1 1L ^ vi 1 2L) "single_int32" (i 2L));
    ("a message's occurrences merge", `Quick, in_registry @@ fun () ->
        match Proto_codec.decode "cel.expr.conformance.proto2.NestedTestAllTypes"
                (ld 2 (vi 1 5L) ^ ld 2 (ld 14 "s")) with
        | Ok v -> (
            match field v "payload" with
            | Some (V.Message { fields; _ }) ->
                Alcotest.(check bool) "single_int32 kept" true (List.assoc_opt "single_int32" fields = Some (V.Int 5L));
                Alcotest.(check bool) "single_string added" true (List.assoc_opt "single_string" fields = Some (V.String "s"))
            | _ -> Alcotest.fail "no payload")
        | Error e -> Alcotest.fail e);
    ("a negative int32 in ten bytes", `Quick, binary_reads p3 (vi 1 (-1L)) "single_int32" (i (-1L)));
    ("a group", `Quick, binary_reads p2 (tag 403 3 ^ vi 404 4L ^ tag 403 4) "nestedgroup"
       (Some (V.Message { tname = p2 ^ ".NestedGroup"; fields = [ ("single_id", V.Int 4L) ] })));
    ("a map entry without its key", `Quick, binary_reads p3 (ld 61 (ld 2 "v")) "map_string_string" (Some (V.make_map [ (V.String "", V.String "v") ]))) ]

let binary_refusing =
  [ ("an unknown field number", `Quick, binary_refuses p3 (vi 999 1L) "has no field numbered 999");
    ("two members of one oneof", `Quick, binary_refuses p3 (vi 402 1L ^ ld 401 "") "two members of oneof kind");
    ("an unknown enum number", `Quick, binary_refuses p3 (vi 24 9L) "has no value numbered 9");
    ("an int32 beyond 32 bits", `Quick, binary_refuses p3 (vi 1 2147483648L) "out of range for int32");
    ("a uint32 beyond 32 bits", `Quick, binary_refuses p3 (vi 3 4294967296L) "out of range for uint32");
    ("a string that is not UTF-8", `Quick, binary_refuses p3 (ld 14 "\xff") "not UTF-8");
    ("a wire type that does not fit", `Quick, binary_refuses p3 (ld 1 "x") "wire type 2, expected 0");
    ("truncated bytes", `Quick, binary_refuses p3 (tag 14 2 ^ varint 5L ^ "ab") "truncated");
    ("a missing required field", `Quick, binary_refuses "cel.expr.conformance.proto2.TestRequired" "" "required field required_int32");
    ("a group ended by the wrong tag", `Quick, binary_refuses p2 (tag 403 3 ^ vi 404 4L ^ tag 405 4) "wrong tag");
    ("a timestamp out of range", `Quick, binary_refuses p3 (ld 102 (vi 1 253402300800L)) "out of range") ]

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "cel-codec"
    [ ("json reading", json_reading); ("json refusing", json_refusing); ("json writing", json_writing);
      ("binary reading", binary_reading); ("binary refusing", binary_refusing) ]
