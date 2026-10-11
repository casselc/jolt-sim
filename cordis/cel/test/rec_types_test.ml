(* Recursive types in the CEL checker (docs/cordis/rfc/0002-graph-language.md,
   revision 3): a recursive type is nominal (Checker.Rec, resolved through
   its scope), and two are compared structurally, by a bisimulation. Hegel
   draws mutually recursive definitions (each reference guarded by a list,
   an option or a variant payload): renamed into another scope they stay
   assignable both ways, and a changed leaf in a root's body tells them
   apart. Cases: unfolding, field selection, a cycle against a finite type,
   an unguarded cycle refused rather than looping. *)

open Cordis_cel
module C = Checker

let leaves = [ C.Int; C.String; C.Bool ]

(* a body over the names [n0..]: references only where [guarded] (under list, option, a variant payload) *)
let rec body pick names scope depth guarded =
  let k = pick (if depth <= 0 then 4 else 8) in
  match k with
  | 0 | 1 | 2 -> List.nth leaves k
  | 3 -> if guarded then C.Rec (List.nth names (pick (List.length names)), scope) else List.nth leaves (pick 3)
  | 4 -> C.List (body pick names scope (depth - 1) true)
  | 5 -> C.Opt (body pick names scope (depth - 1) true)
  | 6 ->
      let n = 1 + pick 3 in
      C.Record (List.init n (fun i -> (Printf.sprintf "f%d" i, body pick names scope (depth - 1) guarded)), false)
  | _ ->
      let n = 1 + pick 3 in
      C.Variant (List.init n (fun i -> (Printf.sprintf "C%d" i, if pick 3 = 0 then None else Some (body pick names scope (depth - 1) true))))

(* the same definitions in another scope, under other names *)
let rec rename f scope = function
  | C.Rec (n, _) -> C.Rec (f n, scope)
  | C.List t -> C.List (rename f scope t)
  | C.Opt t -> C.Opt (rename f scope t)
  | C.Record (fs, o) -> C.Record (List.map (fun (k, t) -> (k, rename f scope t)) fs, o)
  | C.Variant cs -> C.Variant (List.map (fun (k, t) -> (k, Option.map (rename f scope) t)) cs)
  | t -> t

(* the first leaf outside a reference changed to another; None if the body has none *)
let mutate t =
  let changed = ref false in
  let other = function C.Int -> C.String | C.String -> C.Bool | _ -> C.Int in
  let rec go t =
    if !changed then t
    else
      match t with
      | C.Int | C.String | C.Bool -> changed := true; other t
      | C.List t -> C.List (go t)
      | C.Opt t -> C.Opt (go t)
      | C.Record (fs, o) -> C.Record (List.map (fun (k, t) -> (k, go t)) fs, o)
      | C.Variant cs -> C.Variant (List.map (fun (k, t) -> (k, Option.map go t)) cs)
      | t -> t
  in
  let t' = go t in
  if !changed then Some t' else None

let both env a b = C.assignable env a b && C.assignable env b a

let renamed () =
  let settings = Hegel.Settings.create ~test_cases:1000 ~seed:11 () in
  let compared = ref 0 and told_apart = ref 0 in
  Fun.protect ~finally:(fun () -> Printf.printf "%d renamings compared equal, %d changed bodies told apart\n" !compared !told_apart) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:rec-types" (fun tc ->
      let pick n = if n <= 1 then 0 else Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let k = 1 + pick 3 in
      let ts = List.init k (fun i -> Printf.sprintf "T%d" i) in
      let s1 = C.new_scope () and s2 = C.new_scope () and s3 = C.new_scope () in
      let bodies = List.map (fun _ -> body pick ts s1 3 false) ts in
      List.iter2 (C.define s1) ts bodies;
      let u n = "U" ^ String.sub n 1 (String.length n - 1) in
      List.iter2 (fun n b -> C.define s2 (u n) (rename u s2 b)) ts bodies;
      Hegel.note tc (String.concat "\n" (List.map2 (fun n b -> n ^ " = " ^ C.to_string b) ts bodies));
      let env = C.empty () in
      List.iter
        (fun n -> Hegel.require tc ~msg:("a renaming is not equal: " ^ n) (both env (C.Rec (n, s1)) (C.Rec (u n, s2))))
        ts;
      incr compared;
      (* change T0's own body: the copy is no longer equal *)
      match mutate (List.hd bodies) with
      | None -> ()
      | Some b0 ->
          List.iteri (fun i (n, b) -> C.define s3 (u n) (rename u s3 (if i = 0 then b0 else b))) (List.combine ts bodies);
          Hegel.note tc ("changed: U0 = " ^ C.to_string (rename u s3 b0));
          Hegel.require tc ~msg:"a changed body compared equal" (not (both env (C.Rec ("T0", s1)) (C.Rec ("U0", s3))));
          incr told_apart)

let cases () =
  let s = C.new_scope () in
  (* type Tree = { label : string; kids : list(Tree) }; type Expr = Num(int) | Add(Pair); type Pair = { l : Expr; r : Expr } *)
  C.define s "Tree" (C.Record ([ ("label", C.String); ("kids", C.List (C.Rec ("Tree", s))) ], false));
  C.define s "Expr" (C.Variant [ ("Num", Some C.Int); ("Add", Some (C.Rec ("Pair", s))) ]);
  C.define s "Pair" (C.Record ([ ("l", C.Rec ("Expr", s)); ("r", C.Rec ("Expr", s)) ], false));
  C.define s "Loop" (C.Rec ("Loop2", s));
  C.define s "Loop2" (C.Rec ("Loop", s));
  let env = C.empty () |> fun e -> C.declare_ident e "t" (C.Rec ("Tree", s)) |> fun e -> C.declare_ident e "e" (C.Rec ("Expr", s)) in
  let check src = match Parser.parse src with Ok ast -> Result.map C.to_string (C.check env ast) | Error m -> Error m in
  Alcotest.(check (result string string)) "a field of a recursive record" (Ok "list(Tree)") (check "t.kids");
  Alcotest.(check (result string string)) "two levels down" (Ok "string") (check "t.kids[0].label");
  Alcotest.(check (result string string)) "a mutual recursion's match" (Ok "int")
    (check "match e { Num(n) -> n; Add(p) -> match p.l { Num(m) -> m; _ -> 0 } }");
  Alcotest.(check bool) "a recursive type does not fit a finite one" false (C.assignable env (C.Record ([ ("label", C.String); ("kids", C.List C.Int) ], false)) (C.Rec ("Tree", s)));
  Alcotest.(check bool) "a finite tree fits the recursive type's list" true (C.assignable env (C.Rec ("Tree", s)) (C.Record ([ ("label", C.String); ("kids", C.List (C.Rec ("Tree", s))) ], false)));
  (* the same name in another revision's scope, defined otherwise: not the same type *)
  let s' = C.new_scope () in
  C.define s' "Tree" (C.Record ([ ("label", C.Int); ("kids", C.List (C.Rec ("Tree", s'))) ], false));
  Alcotest.(check bool) "one name, two definitions" false (C.assignable env (C.Rec ("Tree", s)) (C.Rec ("Tree", s')));
  match C.assignable env (C.Rec ("Loop", s)) C.Int with
  | _ -> Alcotest.fail "an unguarded cycle compared"
  | exception C.Check_error m -> Alcotest.(check bool) ("refused: " ^ m) true (String.length m > 0)

let () =
  Alcotest.run "rec-types"
    [ ("cases", [ Alcotest.test_case "recursive records and variants" `Quick cases ]);
      ("properties", [ Alcotest.test_case "renamed definitions are equal, changed ones are not" `Quick renamed ]) ]
