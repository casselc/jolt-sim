(* Compilation, replacement compatibility and admission
   (docs/cordis/rfc/0002-graph-language.md, "Compilation to the kernel",
   "Storage and edits"; cordis/quint/admission.qnt).

   Properties:
   - every checked graph compiles to components the kernel accepts, and any
     interleaving of their steps reaches quiet with every asset provided,
     only allowed intents emitted, and each before-invariant held; over the
     example, the accepted corpus, and random graphs whose before-invariants
     are random, so the static check is tested against runs;
   - admission, as admission.qnt states it: two agents patching from the
     same snapshot cannot lose each other's updates (the later is refused as
     stale, never merged), every live revision checks, and a change that
     needs approval never goes live unapproved.
   Examples: what compatibility allows and what it sends for approval. *)

open Cordis_graph

let settings =
  let cases = match Sys.getenv_opt "GRAPH_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  Hegel.Settings.create ~test_cases:cases ~seed:13 ()

let run name f = Hegel.run_hegel_test ~settings ~database_key:("cordis:graph:" ^ name) f
let read path = In_channel.with_open_text path In_channel.input_all

let load_ok text =
  match Patch.load text with Ok s -> s | Error es -> failwith (String.concat "; " es)

(* ---- compiled graphs run ---------------------------------------------------------------------------- *)

let corpus_stores =
  lazy
    (let corpus = Sys.argv.(1) and example = Sys.argv.(2) in
     let options = { Check.default_options with store = Corpus_store.store corpus } in
     let oks =
       Array.to_list (Sys.readdir corpus)
       |> List.filter (fun f -> String.length f > 3 && String.sub f 0 3 = "ok_")
       |> List.map (Filename.concat corpus)
     in
     List.map
       (fun p ->
         match Patch.load ~options (Corpus_store.substitute corpus (read p)) with
         | Ok s -> (p, s)
         | Error es -> failwith (p ^ ": " ^ String.concat "; " es))
       (example :: oks))

let runs_cleanly tc (store : Patch.store) =
  let pick n = (Typed_gen.of_hegel tc).pick n in
  List.iter
    (fun (g : Check.graph_info) ->
      let c = Compile.compile store.checked.cells g in
      let r = Compile.simulate ~pick c in
      let problems = Compile.verify store.checked.cells g c r in
      Hegel.note tc (Printf.sprintf "graph %s, %d steps: %s" g.gname r.steps (String.concat "; " problems));
      Hegel.require tc ~msg:"a compiled graph's run went wrong" (problems = []))
    store.checked.graphs

let test_corpus_runs () =
  run "corpus-runs" (fun tc ->
      let stores = Lazy.force corpus_stores in
      let path, store = List.nth stores ((Typed_gen.of_hegel tc).pick (List.length stores)) in
      Hegel.note tc path;
      runs_cleanly tc store)

(* A random graph: cells over ints with random uses, assets wired to
   earlier names, and random before-invariants, which the checker accepts
   or not. Whatever it accepts must hold in every run. *)
let random_graph tc =
  let pick n = (Typed_gen.of_hegel tc).pick n in
  let n_cells = 1 + pick 4 and n_inputs = 1 + pick 2 and n_assets = 1 + pick 6 in
  let cells =
    List.init n_cells (fun i ->
        let params = 1 + pick 2 in
        let uses = List.init (pick 3) (fun u -> (Printf.sprintf "r%d" u, Printf.sprintf "k%d" (pick 3))) in
        let effs = List.sort_uniq compare (List.map snd uses) in
        Printf.sprintf "cell c%d(%s) : int%s = {\n%s  %s;\n}" i
          (String.concat ", " (List.init params (fun p -> Printf.sprintf "p%d : int" p)))
          (if effs = [] then "" else " ! { " ^ String.concat ", " effs ^ " }")
          (String.concat "" (List.map (fun (x, k) -> Printf.sprintf "  use %s = %s(p0);\n" x k) uses))
          (String.concat " + " (List.init params (Printf.sprintf "p%d") @ List.map fst uses)),
        params)
  in
  let names = ref (List.init n_inputs (Printf.sprintf "i%d")) in
  let assets =
    List.init n_assets (fun a ->
        let ci = pick n_cells in
        let _, params = List.nth cells ci in
        let args = List.init params (fun _ -> List.nth !names (pick (List.length !names))) in
        let line = Printf.sprintf "  asset a%d = c%d(%s);" a ci (String.concat ", " args) in
        names := !names @ [ Printf.sprintf "a%d" a ];
        line)
  in
  let befores = List.init (pick 3) (fun _ -> Printf.sprintf "  invariant c%d before c%d;" (pick n_cells) (pick n_cells)) in
  String.concat "\n"
    ([ "effect k0(int) : int;"; "effect k1(int) : int;"; "effect k2(int) : int;" ]
     @ List.map fst cells
     @ [ "graph g {" ] @ List.init n_inputs (Printf.sprintf "  input i%d : int;") @ assets @ befores @ [ "}" ])

let test_random_runs () =
  run "random-runs" (fun tc ->
      let text = random_graph tc in
      Hegel.note tc text;
      match Patch.load text with
      | Error es -> Hegel.note tc ("rejected: " ^ String.concat "; " es) (* a before that cannot hold, a cell no asset uses *)
      | Ok store -> runs_cleanly tc store)

(* ---- compatibility -------------------------------------------------------------------------------- *)

(* Each replacement changes one thing and leaves the revision checking, so
   what is tested is the classification, not the checker. *)
let base =
  {|type Diff = { path : string; patch : string };
type V = A(int) | B;
type W = P | Q(int);
type Tree = { label : string; kids : list(Tree)<max 8>; };
type E = Num(int) | Neg(E);
effect k(int) : int;
effect j(int) : int;
effect unused(int) : int;
cell f(x : int, d : { path : string; .. }) : int ! { k } = { use r = k(x); r; }
cell h(v : V) : int = { match v { A(n) -> n; B -> 0 }; }
cell spare(n : int) : int = { n; }
graph g { input x : int; input d : Diff; asset y = f(x, d); }
|}

let classify replacement =
  let live = load_ok base in
  let name = match Parse.file replacement with Ok [ d ] -> Syntax.item_name d | _ -> failwith "one definition" in
  let expect = List.assoc name (Print.hashes live.file) in
  match Patch.parse (Printf.sprintf "replace %s@%s\n%s" name expect replacement) with
  | Error m -> `Error m
  | Ok p -> (
      match Patch.admit live p with
      | Patch.Live (_, _, _) -> `Live
      | Patch.Awaiting_approval (_, needs) -> `Approval (String.concat "; " (List.concat_map snd needs))
      | Patch.Refused es -> `Error (String.concat "; " es))

let contains s frag =
  let n = String.length frag in
  let rec go i = i + n <= String.length s && (String.sub s i n = frag || go (i + 1)) in
  go 0

let expect_live replacement () =
  match classify replacement with
  | `Live -> ()
  | `Approval why -> Alcotest.failf "needs approval (%s)" why
  | `Error e -> Alcotest.failf "refused: %s" e

let expect_approval frag replacement () =
  match classify replacement with
  | `Approval why -> if not (contains why frag) then Alcotest.failf "approval, but for %S, not %S" why frag
  | `Live -> Alcotest.fail "went live"
  | `Error e -> Alcotest.failf "refused: %s" e

let compat_tests =
  [ ("a new body", `Quick, expect_live "cell f(x : int, d : { path : string; .. }) : int ! { k } = { use r = k(x + 1); r * 2; }");
    ("fewer effects", `Quick, expect_live "cell f(x : int, d : { path : string; .. }) : int = { x; }");
    ("a wider input (dyn)", `Quick, expect_live "cell f(x : dyn, d : { path : string; .. }) : int ! { k } = { use r = k(int(x)); r; }");
    ("a wider input (fewer fields required)", `Quick, expect_live "cell f(x : int, d : { .. }) : int ! { k } = { use r = k(x); r; }");
    ("a wider effect row", `Quick, expect_approval "widens its effects: + j" "cell f(x : int, d : { path : string; .. }) : int ! { k, j } = { use r = k(x); use s = j(r); s; }");
    ("a narrower input (closed record)", `Quick, expect_approval "narrows input d" "cell f(x : int, d : Diff) : int ! { k } = { use r = k(x); r; }");
    ("an output of another type", `Quick, expect_approval "changes its output" "cell spare(n : int) : string = { string(n); }");
    ("an arity change", `Quick, expect_approval "takes 2 inputs" "cell spare(n : int, m : int) : int = { n + m; }");
    ("a variant's cases", `Quick, expect_approval "changes a variant" "type W = P | Q(int) | R;");
    (* recursive types, compared by bisimulation with the cycle cut at the name (RFC 0002 revision 3) *)
    ("a recursive type, its fields reordered", `Quick, expect_live "type Tree = { kids : list(Tree)<max 8>; label : string; };");
    ("a recursive type, a node bound given", `Quick, expect_live "type Tree = { label : string; kids : list(Tree)<max 8>; } <nodes 64>;");
    ("a recursive type, a level down changed", `Quick, expect_approval "changes the type" "type Tree = { label : string; kids : list(Tree)<max 8>; extra : int; };");
    ("a recursive type, no longer recursive", `Quick, expect_approval "changes the type" "type Tree = { label : string; kids : list(string)<max 8>; };");
    ("a recursive variant's cases", `Quick, expect_approval "changes a variant" "type E = Num(int) | Neg(E) | Zero;");
    ("a recursive variant, a payload changed", `Quick, expect_approval "changes the type" "type E = Num(string) | Neg(E);");
    ("an effect's types", `Quick, expect_approval "request or receipt" "effect unused(string) : int;") ]

(* ---- bounds of recursive types (RFC 0002 revision 3) ----------------------------------------------- *)

module V = Cordis_cel.Value

let bounds_file =
  {|type Tree = { label : string; note : option(int); kids : list(Tree)<max 50>; } <nodes 40>;
type Narrow = { kids : list(Narrow)<max 2>; };
type E = Num(string<max 3>) | Neg(E);
type In = { xs : list(In); } <nodes 4>;
type Out = { ins : list(In); next : option(Out); } <nodes 100>;
cell tree(t : Tree) : int = { t.kids.size(); }
cell narrow(t : Narrow) : int = { t.kids.size(); }
cell e(x : E) : int = { 1; }
cell out(o : Out) : int = { o.ins.size(); }
|}

let param_bound cell =
  let s = load_ok bounds_file in
  match List.find_opt (fun (c : Check.cell_info) -> c.cname = cell) s.checked.cells with
  | Some c -> snd (List.hd c.param_bounds)
  | None -> failwith cell

let record fs = match V.make_map (List.map (fun (k, v) -> (V.String k, v)) fs) with V.Map _ as m -> m | e -> e
let case tag p = record (("tag", V.String tag) :: (match p with Some v -> [ ("value", v) ] | None -> []))
let tree ?(note = false) kids =
  record [ ("label", V.String "x"); ("note", V.Optional (if note then Some (V.Int 1L) else None)); ("kids", V.List (Array.of_list kids)) ]

(* the count the node bound is of: the value and every element, map value and optional content *)
let rec count = function
  | V.List xs -> Array.fold_left (fun a x -> a + count x) 1 xs
  | V.Map m -> List.fold_left (fun a (_, x) -> a + count x) 1 m
  | V.Optional (Some x) -> 1 + count x
  | _ -> 1

let refused_with frag b v () =
  match Check.exceeds b v with
  | Some m -> if not (contains m frag) then Alcotest.failf "refused as %s, expected %s" m frag
  | None -> Alcotest.failf "admitted, expected %s" frag

let admitted b v () = match Check.exceeds b v with Some m -> Alcotest.failf "refused: %s" m | None -> ()

let bound_tests =
  let leaf = tree [] in
  let nest n = let rec go k = if k = 0 then V.List [||] else V.List [| record [ ("xs", go (k - 1)) ] |] in record [ ("xs", go n) ] in
  let rec neg k x = if k = 0 then x else case "Neg" (Some (neg (k - 1) x)) in
  [ ("a tree within its node bound", `Quick, admitted (param_bound "tree") (tree [ leaf; leaf; tree [ leaf ] ]));
    ("a tree over its node bound", `Quick, refused_with "more than 40 nodes" (param_bound "tree") (tree (List.init 14 (fun _ -> leaf))));
    (* 4 + 9 leaves of 4 = 40, and one note more is 41 *)
    ("a tree of exactly its node bound", `Quick, admitted (param_bound "tree") (tree (List.init 9 (fun _ -> leaf))));
    ("a tree one node over it", `Quick, refused_with "more than 40 nodes" (param_bound "tree") (tree ~note:true (List.init 9 (fun _ -> leaf))));
    ("a level's <max N>, three levels down", `Quick,
     refused_with "kids: kids: kids: a list of 3, over its bound of 2" (param_bound "narrow")
       (record [ ("kids", V.List [| record [ ("kids", V.List [| record [ ("kids", V.List (Array.make 3 (record [ ("kids", V.List [||]) ]))) ] |]) ] |]) ]));
    ("a variant's payload, beneath its recursion", `Quick, refused_with "Neg: Neg: Neg: Num: a string of 4" (param_bound "e") (neg 3 (case "Num" (Some (V.String "abcd")))));
    ("a variant's payload within its bound", `Quick, admitted (param_bound "e") (neg 3 (case "Num" (Some (V.String "abc")))));
    ("a recursive type inside another holds its own node bound", `Quick,
     refused_with "more than 4 nodes" (param_bound "out") (record [ ("ins", V.List [| nest 2 |]); ("next", V.Optional None) ]));
    ("the same, within it", `Quick, admitted (param_bound "out") (record [ ("ins", V.List [| nest 1; nest 1 |]); ("next", V.Optional None) ])) ]

(* drawn trees: refused for their nodes exactly when they hold more than the bound *)
let test_node_bound () =
  let b = param_bound "tree" in
  let seen = ref (0, 0) in
  Fun.protect ~finally:(fun () -> Printf.printf "%d trees within the node bound, %d over\n" (fst !seen) (snd !seen)) @@ fun () ->
  run "node-bound" (fun tc ->
      let pick n = (Typed_gen.of_hegel tc).pick n in
      let rec draw depth = tree ~note:(pick 2 = 0) (List.init (if depth = 0 then 0 else pick 4) (fun _ -> draw (depth - 1))) in
      let v = draw (1 + pick 4) in
      let n = count v in
      Hegel.note tc (Printf.sprintf "%d nodes: %s" n (V.to_string v));
      let over = Check.exceeds b v in
      seen := if n > 40 then (fst !seen, snd !seen + 1) else (fst !seen + 1, snd !seen);
      Hegel.require tc ~msg:"refused within its node bound, or admitted over it" ((n > 40) = (over <> None)))

(* ---- admission, as admission.qnt states it -------------------------------------------------------- *)

let bodies = [ "x"; "x + 1"; "x * 2"; "x - 3"; "x + x" ]

let test_admission () =
  run "admission" (fun tc ->
      let pick n = (Typed_gen.of_hegel tc).pick n in
      let live =
        ref
          (load_ok
             "type R = { n : int; m : int };\ncell f(x : { n : int; .. }) : int = { x.n; }\ncell h(x : int) : int = { x; }\ngraph g { input x : R; asset y = f(x); asset z = h(y); }\n")
      in
      let history = ref [] in
      (* rounds: two agents take the same snapshot, each patches one
         definition (sometimes narrowing it), submitted in either order *)
      for _ = 1 to 1 + pick 4 do
        let snapshot = !live in
        let patch_for () =
          let name = if pick 2 = 0 then "f" else "h" in
          (* a narrowing that still checks: f's open record parameter closed *)
          let closes = name = "f" && pick 3 = 0 in
          let f_is_open =
            List.exists (function Syntax.Cell c -> c.name = "f" && (match c.params with [ (_, Syntax.Record (_, true)) ] -> true | _ -> false) | _ -> false) snapshot.file
          in
          (* narrowing relative to what the agent saw (and, if it goes live, to what was live) *)
          let narrow = closes && f_is_open in
          let body = List.nth bodies (pick (List.length bodies)) in
          let def =
            if name = "h" then Printf.sprintf "cell h(x : int) : int = { %s; }" body
            else Printf.sprintf "cell f(x : %s) : int = { %s; }" (if closes then "R" else "{ n : int; .. }")
                   (Str_replace.all ~sub:"x" ~by:"x.n" body)
          in
          let expect = List.assoc name (Print.hashes snapshot.file) in
          (name, narrow, Printf.sprintf "replace %s@%s\n%s" name expect def)
        in
        let a = patch_for () and b = patch_for () in
        let first, second = if pick 2 = 0 then (a, b) else (b, a) in
        List.iter
          (fun (name, narrow, text) ->
            let approved = pick 2 = 0 in
            let before = !live in
            match Patch.parse text with
            | Error m -> Hegel.require tc ~msg:("an agent's patch does not parse: " ^ m) false
            | Ok p -> (
                match Patch.admit ~approved before p with
                | Patch.Live (s, _, _) ->
                    (* it was based on what was live: nothing was lost *)
                    Hegel.require tc ~msg:"a patch went live over a revision it did not see"
                      (Print.hashes before.file = Print.hashes snapshot.file || List.assoc name (Print.hashes before.file) = List.assoc name (Print.hashes snapshot.file));
                    Hegel.require tc ~msg:"a narrowing change went live unapproved" ((not narrow) || approved || Print.hashes s.file = Print.hashes before.file);
                    Hegel.require tc ~msg:"a live revision does not check" (Result.is_ok (Check.check s.file));
                    history := (name, Patch.revision s.file) :: !history;
                    live := s
                | Patch.Awaiting_approval (_, _) ->
                    Hegel.require tc ~msg:"an approved patch waited" (not approved);
                    Hegel.require tc ~msg:"a compatible patch waited" narrow
                | Patch.Refused es ->
                    (* refused only as stale: the other agent changed this definition first *)
                    Hegel.note tc (String.concat "; " es);
                    Hegel.require tc ~msg:"a patch was refused though its base was live"
                      (List.assoc name (Print.hashes before.file) <> List.assoc name (Print.hashes snapshot.file))))
          [ first; second ]
      done;
      Hegel.note tc (String.concat " " (List.rev_map fst !history)))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "graph-stage3"
    [ ( "compilation",
        [ Alcotest.test_case "corpus graphs run under the kernel" `Quick test_corpus_runs;
          Alcotest.test_case "random graphs: accepted invariants hold in every run" `Quick test_random_runs ] );
      ("compatibility", compat_tests);
      ("bounds", List.map (fun (n, s, f) -> Alcotest.test_case n s f) bound_tests @ [ Alcotest.test_case "drawn trees: refused exactly over the node bound" `Quick test_node_bound ]);
      ("admission", [ Alcotest.test_case "no lost update, no unapproved narrowing" `Quick test_admission ]) ]
