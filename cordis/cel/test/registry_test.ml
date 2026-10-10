(* Registries are values (docs/cordis/rfc/0004-descriptor-native-types.md,
   "The registry"): two registries defining the same full name differently
   are checked and evaluated side by side, each seeing only its own, also
   from two domains at once; and two sets that define one name differently
   cannot be merged into one registry. The descriptor sets are encoded here,
   so the test needs no protoc. *)

open Cordis_cel

(* ---- a FileDescriptorSet, by hand -------------------------------------------------------- *)

let varint n =
  let b = Buffer.create 4 in
  let rec go n = if n < 0x80 then Buffer.add_char b (Char.chr n) else (Buffer.add_char b (Char.chr (n land 0x7f lor 0x80)); go (n lsr 7)) in
  go n;
  Buffer.contents b

let ld num s = varint ((num lsl 3) lor 2) ^ varint (String.length s) ^ s
let vi num n = varint (num lsl 3) ^ varint n

(* type numbers: descriptor.proto's FieldDescriptorProto.Type *)
type fty = { num : int; name : string; lit : string; cel : Checker.t; value : Value.t }

let types =
  [ { num = 5; name = "int32"; lit = "7"; cel = Checker.Int; value = Value.Int 7L };
    { num = 3; name = "int64"; lit = "7"; cel = Checker.Int; value = Value.Int 7L };
    { num = 9; name = "string"; lit = "'s'"; cel = Checker.String; value = Value.String "s" };
    { num = 8; name = "bool"; lit = "true"; cel = Checker.Bool; value = Value.Bool true };
    { num = 1; name = "double"; lit = "1.5"; cel = Checker.Double; value = Value.Double 1.5 } ]

let set_of (fields : fty list) =
  let field i (t : fty) = ld 2 (ld 1 (Printf.sprintf "f%d" i) ^ vi 3 (i + 1) ^ vi 4 1 ^ vi 5 t.num ^ ld 10 (Printf.sprintf "f%d" i)) in
  let message = ld 1 "M" ^ String.concat "" (List.mapi field fields) in
  ld 1 (ld 1 "t.proto" ^ ld 2 "t" ^ ld 4 message ^ ld 12 "proto3")

let registry fields = match Proto.of_descriptor_sets [ set_of fields ] with Ok r -> r | Error m -> failwith m

(* What t.M{fI: lit}.fI gives under a registry where fI has type [t]: the
   literal's value when it fits the field, else an error. *)
let expected (field : fty) (lit : fty) =
  if field.cel = lit.cel then Some lit.value else None

let outcome r src =
  match Cel.eval ~registry:r src with
  | Ok { value; _ } when Value.is_error value -> None
  | Ok { value; _ } -> Some value
  | Error _ -> None

let same a b = match a, b with Some x, Some y -> Value.equal x y | None, None -> true | _ -> false

let settings =
  let cases = match Sys.getenv_opt "CEL_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  Hegel.Settings.create ~test_cases:cases ~seed:23 ()

let test_isolation () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:registries" (fun tc ->
      let pick n = Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let choose l = List.nth l (pick (List.length l)) in
      let f1 = List.init 3 (fun _ -> choose types) and f2 = List.init 3 (fun _ -> choose types) in
      let r1 = registry f1 and r2 = registry f2 in
      (* the cases: a field and the literal assigned to it *)
      let cases =
        List.init 20 (fun _ ->
            let i = pick 3 and lit = choose types in
            (Printf.sprintf "t.M{f%d: %s}.f%d" i lit.lit i, expected (List.nth f1 i) lit, expected (List.nth f2 i) lit))
      in
      Hegel.note tc
        (Printf.sprintf "r1: %s\nr2: %s" (String.concat ", " (List.map (fun t -> t.name) f1)) (String.concat ", " (List.map (fun t -> t.name) f2)));
      (* interleaved on one domain *)
      List.iter
        (fun (src, e1, e2) ->
          Hegel.require tc ~msg:("r1 saw another registry: " ^ src) (same (outcome r1 src) e1);
          Hegel.require tc ~msg:("r2 saw another registry: " ^ src) (same (outcome r2 src) e2))
        cases;
      (* the checker, per registry *)
      List.iteri
        (fun i _ ->
          let src = Printf.sprintf "t.M{}.f%d" i in
          let ty r = match Cel.parse src with Ok ast -> Proto.with_registry r (fun () -> Checker.check (Checker.empty ()) ast) | Error e -> Error e in
          Hegel.require tc ~msg:("r1's checker saw another registry: " ^ src) (ty r1 = Ok (List.nth f1 i).cel);
          Hegel.require tc ~msg:("r2's checker saw another registry: " ^ src) (ty r2 = Ok (List.nth f2 i).cel))
        f1;
      (* two domains at once, each with its own registry *)
      let run r pick_expected =
        Domain.spawn (fun () ->
            List.for_all (fun (src, e1, e2) -> List.for_all (fun _ -> same (outcome r src) (pick_expected (e1, e2))) [ 1; 2; 3 ]) cases)
      in
      let d1 = run r1 fst and d2 = run r2 snd in
      let ok1 = Domain.join d1 and ok2 = Domain.join d2 in
      Hegel.require tc ~msg:"a domain saw the other's registry" (ok1 && ok2))

let test_conflicts () =
  let a = set_of [ List.nth types 0 ] and b = set_of [ List.nth types 2 ] in
  (match Proto.of_descriptor_sets [ a; b ] with
   | Ok _ -> Alcotest.fail "two definitions of t.M were merged"
   | Error m -> if not (String.length m > 0) then Alcotest.fail "no message");
  match Proto.of_descriptor_sets [ a; a ] with
  | Ok _ -> ()
  | Error m -> Alcotest.failf "an identical definition was refused: %s" m

let test_default_untouched () =
  (* binding a registry for one evaluation leaves the default as it was *)
  let r = registry [ List.nth types 2 ] in
  ignore (Cel.eval ~registry:r "t.M{f0: 's'}.f0");
  match Cel.eval "t.M{f0: 's'}.f0" with
  | Ok { value; _ } when Value.is_error value -> ()
  | Ok { value; _ } -> Alcotest.failf "the default registry saw t.M: %s" (Value.to_string value)
  | Error _ -> ()

let () =
  Alcotest.run "cel-registries"
    [ ( "registries",
        [ Alcotest.test_case "each check and evaluation sees its own registry" `Quick test_isolation;
          Alcotest.test_case "conflicting definitions cannot be merged" `Quick test_conflicts;
          Alcotest.test_case "binding a registry leaves the default alone" `Quick test_default_untouched ] ) ]
