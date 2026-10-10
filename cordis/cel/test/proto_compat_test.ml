(* Descriptor compatibility (docs/cordis/rfc/0004-descriptor-native-
   types.md, "Revisions"):

   - each row of the compatibility table, by example, classified as the
     table says;
   - soundness (Hegel): for a sequence of changes to a schema, when the
     classifier finds nothing breaking, every message the old descriptors
     write (binary and JSON, by the boundary codec) the new ones read to
     the same message. This is revisions.qnt's reads(new, old), which
     stateReadable depends on. *)

open Cordis_cel
open Desc_build
module V = Value

open Desc_changes

let classify old_ new_ = Proto_compat.compare ~old_:(registry old_) ~new_:(registry new_) [ "t.M" ]

let example (name, breaking, change) =
  ( name,
    `Quick,
    fun () ->
      let fs = classify base (change base) in
      let got = Proto_compat.breaking fs <> [] in
      if got <> breaking then
        Alcotest.failf "%s: expected %s, found: %s" name (if breaking then "breaking" else "compatible")
          (match fs with [] -> "nothing" | fs -> String.concat "; " (List.map Proto_compat.describe fs)) )

(* a proto2 field made required, and a required field added *)
let proto2 = { base with syntax = "proto2"; messages = List.map (fun m -> { m with fields = List.map (fun f -> { f with optional = false }) m.fields }) base.messages }

let required_examples =
  [ ("a field made required", `Quick, fun () ->
        let fs = classify proto2 (on_field "M" "s" (fun f -> { f with label = 2 }) proto2) in
        Alcotest.(check bool) "breaking" true (Proto_compat.breaking fs <> []));
    ("a required field added", `Quick, fun () ->
        let fs = classify proto2 (on_message "M" (fun m -> { m with fields = m.fields @ [ field "req" 20 5 ~label:2 ] }) proto2) in
        Alcotest.(check bool) "breaking" true (Proto_compat.breaking fs <> []));
    ("a required field relaxed", `Quick, fun () ->
        let req = on_field "M" "s" (fun f -> { f with label = 2 }) proto2 in
        let fs = classify req proto2 in
        Alcotest.(check bool) "compatible" true (Proto_compat.breaking fs = [])) ]

(* ---- soundness ------------------------------------------------------------------------------------ *)

let settings =
  let cases = match Sys.getenv_opt "CEL_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:31 ()

(* When the classifier finds nothing breaking from [old_] to [new_], old
   messages read under the new descriptors to themselves. *)
let check_reads tc d ~what old_ new_ =
  let ro = registry old_ and rn = registry new_ in
  let fs = Proto_compat.compare ~old_:ro ~new_:rn [ "t.M" ] in
  if Proto_compat.breaking fs = [] then
    for _ = 1 to 3 do
      match Proto.with_registry ro (fun () -> Proto_gen.message d "t.M") with
      | None -> ()
      | Some v ->
          let enc f = match Proto.with_registry ro f with Ok x -> x | Error e -> failwith e in
          let binary = enc (fun () -> Proto_codec.encode "t.M" v) and json = enc (fun () -> Proto_codec.to_json_string "t.M" v) in
          let reads how r =
            match r with
            | Ok x -> Hegel.require tc ~msg:(Printf.sprintf "%s: the %s of %s read as %s" what how (V.to_string v) (V.to_string x)) (Proto_gen.same x v)
            | Error e ->
                Hegel.require tc
                  ~msg:(Printf.sprintf "%s, called compatible (%s): the new descriptors refused the old %s of %s: %s" what
                          (String.concat "; " (List.map Proto_compat.describe fs)) how (V.to_string v) e)
                  false
          in
          reads "binary" (Proto.with_registry rn (fun () -> Proto_codec.decode "t.M" binary));
          reads "JSON" (Proto.with_registry rn (fun () -> Proto_codec.of_json_string "t.M" json))
    done

let test_soundness () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:proto-compat" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      (* every change on its own, so each is covered in every case *)
      List.iter (fun (name, _, c) -> check_reads tc d ~what:name base (c base)) changes;
      (* and a sequence of them *)
      let steps = List.init (1 + d.pick 3) (fun _ -> Typed_gen.choose d changes) in
      check_reads tc d ~what:(String.concat ", then " (List.map (fun (n, _, _) -> n) steps)) base
        (List.fold_left (fun f (_, _, c) -> c f) base steps))

let () =
  Alcotest.run "cel-proto-compat"
    [ ("table", List.map example changes @ required_examples);
      ("soundness", [ Alcotest.test_case "compatible changes read every old message" `Quick test_soundness ]) ]
