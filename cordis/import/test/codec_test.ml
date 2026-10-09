(* The protobuf-JSON codec, by example: what protobuf JSON may say that the
   agreement property never writes (null for an absent field, JSON names,
   enum numbers, int64 as numbers), and what reading must refuse.

     codec_test.exe SET.binpb *)

open Cordis_import
module V = Cordis_cel.Value

let imp =
  match Descriptor.set_of_string (In_channel.with_open_bin Sys.argv.(1) In_channel.input_all) with
  | Ok files -> Protobuf.import files
  | Error m -> failwith m

let read name json = Codec.of_json_string imp name json

let field name json f =
  match read name json with
  | Ok (V.Map m) -> (match V.map_find m (V.String f) with Some v -> v | None -> Alcotest.failf "no field %s" f)
  | Ok v -> Alcotest.failf "not a record: %s" (V.to_string v)
  | Error e -> Alcotest.failf "refused: %s" e

let value = Alcotest.testable (fun f v -> Format.pp_print_string f (V.to_string v)) V.equal

let reads name json f want () = Alcotest.check value (json ^ " ." ^ f) want (field name json f)

let refuses name json fragment () =
  match read name json with
  | Ok v -> Alcotest.failf "accepted: %s" (V.to_string v)
  | Error e ->
      let n = String.length fragment in
      let rec go i = i + n <= String.length e && (String.sub e i n = fragment || go (i + 1)) in
      if not (go 0) then Alcotest.failf "error %S does not mention %S" e fragment

let tag t = V.make_map [ (V.String "tag", V.String t) ]

let tests =
  [ ("null is absent", `Quick, reads "Order" {|{"note": null}|} "note" (V.Optional None));
    ("an absent option", `Quick, reads "Order" {|{}|} "note" (V.Optional None));
    ("a present option", `Quick, reads "Order" {|{"note": "hi"}|} "note" (V.Optional (Some (V.String "hi"))));
    ("an implicit field's default", `Quick, reads "Order" {|{}|} "id" (V.String ""));
    ("an enum's default is its zero value", `Quick, reads "Order" {|{}|} "status" (tag "STATUS_UNSPECIFIED"));
    ("an enum by number", `Quick, reads "Order" {|{"status": 2}|} "status" (tag "STATUS_CLOSED"));
    ("a field by its JSON name", `Quick, reads "Order" {|{"cardToken": "t"}|} "payment"
       (V.Optional (Some (V.make_map [ (V.String "tag", V.String "CardToken"); (V.String "value", V.String "t") ]))));
    ("int64 as a number", `Quick, reads "acme_shop_v1_Money" {|{"units": 12}|} "units" (V.Int 12L));
    ("int64 as a string", `Quick, reads "acme_shop_v1_Money" {|{"units": "-9223372036854775808"}|} "units" (V.Int Int64.min_int));
    ("map keys as text", `Quick, reads "Order" {|{"labels": {"7": "x"}}|} "labels" (V.make_map [ (V.Int 7L, V.String "x") ]));
    ("a CEL reserved word as a field", `Quick, reads "Order" {|{"in": true}|} "in" (V.Bool true));
    ("an unknown field", `Quick, refuses "Order" {|{"nope": 1}|} "has no field nope");
    ("two members of one oneof", `Quick, refuses "Order" {|{"card_token": "t", "points": 3}|} "two members");
    ("an unknown enum name", `Quick, refuses "Order" {|{"status": "STATUS_GONE"}|} "has no value");
    ("an unknown enum number", `Quick, refuses "Order" {|{"status": 9}|} "has no value numbered 9");
    ("an int32 out of range", `Quick, refuses "acme_shop_v1_Money" {|{"nanos": 2147483648}|} "out of range");
    ("a uint32 out of range", `Quick, refuses "Line" {|{"quantity": 4294967296}|} "out of range");
    ("a proto2 required field", `Quick, refuses "legacy_Money" {|{}|} "required") ]

let () = Alcotest.run ~argv:[| Sys.argv.(0) |] "import-codec" [ ("codec", tests) ]
