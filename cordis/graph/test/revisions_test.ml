(* Descriptor changes across revisions (docs/cordis/rfc/0004-descriptor-
   native-types.md, "Revisions"), as quint/revisions.qnt states it:

   - breakingApproved: a patch that attaches descriptors with a breaking
     change to a type the revision uses goes live only approved; a
     compatible one goes live without;
   - stateReadable: a cell's state is carried across only when the new
     descriptors read every value it can hold (old state, written by the
     codec under the old descriptors, reads under the new ones to itself),
     and migrated otherwise;
   - ownRegistry: admitting a candidate leaves the live revision's
     registry as it was;
   - identity: a definition naming a changed type changes hash, one that
     names none does not.

   singleWriter (retire before insert) is the kernel's O-Retire then
   O-Insert, checked with the kernel. Examples run the same over the shop
   corpus's real descriptor sets.

     revisions_test.exe CORPUS *)

open Cordis_graph
module V = Cordis_cel.Value
module Proto = Cordis_cel.Proto
module Pc = Cordis_cel.Proto_compat

let settings =
  let cases = match Sys.getenv_opt "GRAPH_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  Hegel.Settings.create ~test_cases:cases ~seed:37 ()

let hash s = "sha256:" ^ Cordis_cel.Sha256.digest s

let program set =
  Printf.sprintf
    "uses descriptors %s;\n\
     cell keep(m : t.M) : int state { last : t.M = t.M{}; } = { 1 with state.last := m; }\n\
     cell count(n : int) : int state { k : int = 0; } = { n with state.k := state.k + 1; }\n\
     graph g { input m : t.M; input n : int; asset a = keep(m); asset b = count(n); }\n"
    (hash set)

let load store text =
  match Patch.load ~options:{ Check.default_options with store } text with
  | Ok s -> s
  | Error es -> failwith (String.concat "; " es)

(* the patch that swaps one attached set for another *)
let swap (live : Patch.store) old_set new_set =
  let item = hash old_set in
  let expect = List.assoc item (Print.hashes live.file) in
  match Patch.parse (Printf.sprintf "remove %s@%s\nadd\nuses descriptors %s;\n" item expect (hash new_set)) with
  | Ok p -> p
  | Error m -> failwith m

let store_of sets h = List.find_map (fun s -> if hash s = h then Some s else None) sets

(* ---- the property ---------------------------------------------------------------------------------- *)

let test_revisions () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:graph:revisions" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let steps = List.init (1 + d.pick 2) (fun _ -> Typed_gen.choose d Desc_changes.changes) in
      let old_ = Desc_build.encode Desc_changes.base in
      let changed = List.fold_left (fun f (_, _, c) -> c f) Desc_changes.base steps in
      let new_ = Desc_build.encode changed in
      Hegel.note tc ("changes: " ^ String.concat ", then " (List.map (fun (n, _, _) -> n) steps));
      if new_ <> old_ then begin
        let options = { Check.default_options with store = store_of [ old_; new_ ] } in
        let live = load options.store (program old_) in
        let fresh_old = Desc_build.registry Desc_changes.base in
        let breaking = Pc.breaking (Pc.compare ~old_:fresh_old ~new_:(Desc_build.registry changed) [ "t.M" ]) <> [] in
        let approved = d.pick 2 = 0 in
        let outcome = Patch.admit ~options ~approved live (swap live old_ new_) in
        (* ownRegistry: the live revision still reads t.M as it did *)
        Hegel.require tc ~msg:"admitting a candidate changed the live revision's registry"
          (Pc.compare ~old_:fresh_old ~new_:live.checked.registry [ "t.M" ] = []);
        match outcome with
        | Patch.Refused es -> Hegel.require tc ~msg:("refused: " ^ String.concat "; " es) false
        | Patch.Awaiting_approval (_, needs) ->
            Hegel.note tc (String.concat "; " (List.concat_map snd needs));
            Hegel.require tc ~msg:"an approved patch waited" (not approved);
            Hegel.require tc ~msg:"a compatible descriptor change waited for approval" breaking
        | Patch.Live (s, _, plan) ->
            (* breakingApproved *)
            Hegel.require tc ~msg:"a breaking descriptor change went live unapproved" ((not breaking) || approved);
            (* identity *)
            let oh = Print.hashes live.file and nh = Print.hashes s.file in
            Hegel.require tc ~msg:"keep's hash did not change with its type's descriptors" (List.assoc "keep" oh <> List.assoc "keep" nh);
            Hegel.require tc ~msg:"count's hash changed, though it names no protobuf type" (List.assoc "count" oh = List.assoc "count" nh);
            Hegel.require tc ~msg:"count's state was not carried" (List.assoc_opt "count" plan = Some Compat.Carry);
            (* stateReadable *)
            match List.assoc_opt "keep" plan with
            | Some Compat.Carry ->
                Hegel.require tc ~msg:"state carried across a breaking change" (not breaking);
                for _ = 1 to 3 do
                  match Proto.with_registry live.checked.registry (fun () -> Proto_gen.message d "t.M") with
                  | None -> ()
                  | Some v -> (
                      let bytes =
                        match Proto.with_registry live.checked.registry (fun () -> Cordis_cel.Proto_codec.encode "t.M" v) with
                        | Ok b -> b
                        | Error e -> failwith e
                      in
                      match Proto.with_registry s.checked.registry (fun () -> Cordis_cel.Proto_codec.decode "t.M" bytes) with
                      | Ok x -> Hegel.require tc ~msg:("carried state reads differently: " ^ V.to_string v) (Proto_gen.same x v)
                      | Error e -> Hegel.require tc ~msg:("carried state the new revision cannot read: " ^ V.to_string v ^ ": " ^ e) false)
                done
            | Some (Compat.Migrate rs) ->
                Hegel.note tc (String.concat "; " rs);
                Hegel.require tc ~msg:"state migrated across a compatible change" breaking
            | None -> Hegel.require tc ~msg:"no plan for keep's state" false
      end)

(* ---- the shop corpus ------------------------------------------------------------------------------- *)

let corpus = Sys.argv.(1)
let shop f = In_channel.with_open_bin (Filename.concat (Filename.concat corpus "libs") f) In_channel.input_all

let shop_program set =
  Printf.sprintf
    "uses descriptors %s;\n\
     cell last(o : acme.shop.v1.Order) : bool state { seen : acme.shop.v1.Order = acme.shop.v1.Order{}; } = { has(o.card_token) with state.seen := o; }\n\
     graph g { input o : acme.shop.v1.Order; asset a = last(o); }\n"
    (hash set)

let shop_admit ~approved to_ =
  let from = shop "shop.binpb" and to_ = shop to_ in
  let options = { Check.default_options with store = store_of [ from; to_ ] } in
  let live = load options.store (shop_program from) in
  Patch.admit ~options ~approved live (swap live from to_)

let contains s sub =
  let n = String.length sub in
  let rec go i = i + n <= String.length s && (String.sub s i n = sub || go (i + 1)) in
  go 0

let shop_tests =
  [ ( "a field added: live, state carried",
      `Quick,
      fun () ->
        match shop_admit ~approved:false "shop_added.binpb" with
        | Patch.Live (_, _, plan) -> Alcotest.(check bool) "carried" true (List.assoc_opt "last" plan = Some Compat.Carry)
        | Patch.Awaiting_approval (_, needs) -> Alcotest.failf "waited: %s" (String.concat "; " (List.concat_map snd needs))
        | Patch.Refused es -> Alcotest.failf "refused: %s" (String.concat "; " es) );
    ( "a field removed: waits for approval, says why",
      `Quick,
      fun () ->
        match shop_admit ~approved:false "shop_removed.binpb" with
        | Patch.Awaiting_approval (_, needs) ->
            let why = String.concat "; " (List.concat_map snd needs) in
            if not (contains why "acme.shop.v1.Order: field note (6) removed") then Alcotest.failf "the reason: %s" why
        | Patch.Live _ -> Alcotest.fail "went live unapproved"
        | Patch.Refused es -> Alcotest.failf "refused: %s" (String.concat "; " es) );
    ( "a field removed, approved: live, state migrated",
      `Quick,
      fun () ->
        match shop_admit ~approved:true "shop_removed.binpb" with
        | Patch.Live (_, _, plan) -> (
            match List.assoc_opt "last" plan with
            | Some (Compat.Migrate _) -> ()
            | _ -> Alcotest.fail "the state was not migrated")
        | _ -> Alcotest.fail "did not go live" ) ]

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "graph-revisions"
    [ ("revisions", [ Alcotest.test_case "as revisions.qnt states it" `Quick test_revisions ]); ("shop", shop_tests) ]
