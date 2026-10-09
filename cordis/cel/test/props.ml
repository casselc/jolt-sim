(* Hegel properties for CEL, beside the conformance suite: the checker's
   algebra and soundness, the lexer and parser, values, determinism, time,
   and protobuf messages. Each property shrinks a failure to a minimal case. *)

open Cordis_cel

(* CEL_PROPS_CASES raises the case count for a longer run. *)
let cases = match Sys.getenv_opt "CEL_PROPS_CASES" with Some n -> int_of_string n | None -> 300
let settings = Hegel.Settings.create ~test_cases:cases ~seed:11 ()
let run name f = Hegel.run_hegel_test ~settings ~database_key:("cordis:cel:" ^ name) f
let pick tc n = (Typed_gen.of_hegel tc).pick n
let choose tc l = List.nth l (pick tc (List.length l))
let note = Hegel.note

(* ---- the checker's algebra ------------------------------------------------------------ *)

let rec ground_type tc depth : Checker.t =
  match pick tc (if depth <= 0 then 9 else 15) with
  | 0 -> Checker.Dyn | 1 -> Checker.Null | 2 -> Checker.Bool | 3 -> Checker.Int | 4 -> Checker.Uint
  | 5 -> Checker.Double | 6 -> Checker.String | 7 -> Checker.Bytes | 8 -> Checker.Timestamp
  | 9 -> Checker.List (ground_type tc (depth - 1))
  | 10 -> Checker.Map (choose tc [ Checker.Int; Checker.String ], ground_type tc (depth - 1))
  | 11 -> Checker.Type (ground_type tc (depth - 1))
  | 12 -> Checker.Wrapper (choose tc [ Checker.Int; Checker.String; Checker.Bool ])
  | 13 -> Checker.Opt (ground_type tc (depth - 1))
  | _ -> Checker.Opaque ("tuple", [ ground_type tc (depth - 1); ground_type tc (depth - 1) ])

let test_join_upper_bound () =
  run "join-upper-bound" (fun tc ->
      let a = ground_type tc 3 and b = ground_type tc 3 in
      let env = Checker.empty () in
      let j = Checker.join env a b in
      note tc (Printf.sprintf "join(%s, %s) = %s" (Checker.to_string a) (Checker.to_string b) (Checker.to_string j));
      Hegel.require tc ~msg:"the join accepts its left side" (Checker.assignable (Checker.empty ()) j a);
      Hegel.require tc ~msg:"the join accepts its right side" (Checker.assignable (Checker.empty ()) j b))

let test_join_commutative_idempotent () =
  run "join-commutative" (fun tc ->
      let a = ground_type tc 3 and b = ground_type tc 3 in
      let ab = Checker.join (Checker.empty ()) a b and ba = Checker.join (Checker.empty ()) b a in
      note tc (Printf.sprintf "a = %s, b = %s" (Checker.to_string a) (Checker.to_string b));
      Hegel.require tc ~msg:"join is commutative" (ab = ba);
      Hegel.require tc ~msg:"join is idempotent" (Checker.join (Checker.empty ()) a a = a);
      Hegel.require tc ~msg:"assignable is reflexive" (Checker.assignable (Checker.empty ()) a a))

let test_most_general () =
  run "most-general" (fun tc ->
      let a = ground_type tc 3 and b = ground_type tc 3 in
      let fits = Checker.assignable (Checker.empty ()) a b in
      note tc (Printf.sprintf "a = %s, b = %s, b fits a: %b" (Checker.to_string a) (Checker.to_string b) fits);
      if fits then begin
        let g = Checker.most_general (Checker.empty ()) a b in
        note tc ("most general: " ^ Checker.to_string g);
        Hegel.require tc ~msg:"the most general type accepts the first" (Checker.assignable (Checker.empty ()) g a);
        Hegel.require tc ~msg:"the most general type accepts the second" (Checker.assignable (Checker.empty ()) g b)
      end)

(* ---- checker soundness ------------------------------------------------------------------ *)

(* Does a value inhabit a checked type? *)
let rec admits (t : Checker.t) (v : Value.t) =
  match t, v with
  | Checker.Dyn, _ -> true
  | (Checker.Wrapper _ | Checker.Message _ | Checker.Timestamp | Checker.Duration | Checker.Null | Checker.Opt _), Value.Null -> true
  | Checker.Wrapper t, v -> admits t v
  | Checker.Bool, Value.Bool _ | Checker.Int, Value.Int _ | Checker.Uint, Value.Uint _ | Checker.Double, Value.Double _
  | Checker.String, Value.String _ | Checker.Bytes, Value.Bytes _ | Checker.Type _, Value.Type _ -> true
  | Checker.List t, Value.List xs -> Array.for_all (admits t) xs
  | Checker.Map (k, w), Value.Map m -> List.for_all (fun (a, b) -> admits k a && admits w b) m
  | Checker.Opt t, Value.Optional o -> (match o with None -> true | Some x -> admits t x)
  | _ -> false

(* Well-typed expressions are accepted, and evaluate to a value of the
   checked type, or to an error (overflow, division by zero, bad index). *)
let test_soundness () =
  run "checker-soundness" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      note tc (Printf.sprintf "intended %s: %s" (Typed_gen.ty_text c.ty) c.src);
      match Cel.parse c.src with
      | Error e -> Hegel.require tc ~msg:("generated expression does not parse: " ^ e) false
      | Ok ast -> (
          match Checker.check (Typed_gen.checker_env c) ast with
          | Error e -> Hegel.require tc ~msg:("a well-typed expression was rejected: " ^ e) false
          | Ok t -> (
              note tc ("checked: " ^ Checker.to_string t);
              Hegel.require tc ~msg:"the checked type accepts the intended type"
                (Checker.assignable (Checker.empty ()) t (Typed_gen.to_checker c.ty));
              match Cel.eval ~vars:(Typed_gen.bindings c) c.src with
              | Error e -> Hegel.require tc ~msg:("evaluation failed to start: " ^ e) false
              | Ok r ->
                  note tc ("value: " ^ Value.to_string r.value);
                  Hegel.require tc ~msg:"the value inhabits the checked type"
                    (Value.is_error r.value || admits t r.value))))

(* ---- cost soundness -------------------------------------------------------------------------- *)

(* The largest size anywhere in a value, as the estimator's input bound. *)
let rec max_size (v : Value.t) =
  match v with
  | Value.String s -> Eval.utf8_length s
  | Value.Bytes b -> String.length b
  | Value.List xs -> Array.fold_left (fun a x -> max a (max_size x)) (Array.length xs) xs
  | Value.Map m -> List.fold_left (fun a (k, x) -> max a (max (max_size k) (max_size x))) (List.length m) m
  | _ -> 1

(* RFC 0001's soundness property: for every checked expression and every
   input within its declared size bounds, actual cost <= estimated maximum. *)
let test_cost_soundness () =
  run "cost-soundness" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      let sizes = List.map (fun (n, _, v) -> (n, max 1 (max_size v))) c.vars in
      note tc (Printf.sprintf "%s\nvars: %s" c.src
        (String.concat ", " (List.map (fun (n, _, v) -> n ^ " = " ^ Value.to_string v) c.vars)));
      match Cel.parse c.src with
      | Error e -> Hegel.require tc ~msg:("generated expression does not parse: " ^ e) false
      | Ok ast -> (
          match Cost.estimate ~sizes (Typed_gen.checker_env c) ast with
          | Error e -> Hegel.require tc ~msg:("a well-typed expression was rejected: " ^ e) false
          | Ok est -> (
              match Cel.eval ~vars:(Typed_gen.bindings c) c.src with
              | Error e -> Hegel.require tc ~msg:("evaluation failed to start: " ^ e) false
              | Ok r ->
                  note tc (Printf.sprintf "actual %d, estimate [%d, %d]" r.cost est.min est.max);
                  Hegel.require tc ~msg:"the estimate's minimum exceeds its maximum" (est.min <= est.max);
                  Hegel.require tc ~msg:"actual cost exceeds the estimated maximum" (r.cost <= est.max))))

let test_determinism () =
  run "determinism" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      note tc c.src;
      let once () = match Cel.eval ~vars:(Typed_gen.bindings c) c.src with Ok r -> Some (Value.to_string r.value, r.cost) | Error _ -> None in
      Hegel.require tc ~msg:"evaluation is a function of the expression and inputs" (once () = once ()))

(* ---- harness extensions: fold, match, libraries ------------------------------------------- *)

let int_list tc = List.init (pick tc 6) (fun _ -> pick tc 41 - 20)
let int_list_value l = Value.List (Array.of_list (List.map (fun i -> Value.Int (Int64.of_int i)) l))
let value_eq a b = (Value.is_error a && Value.is_error b) || Value.to_string a = Value.to_string b

let eval_with vars src = match Cel.eval ~vars src with Ok r -> r.value | Error e -> Value.Error e

(* fold and fold_while against an OCaml model of the same loop *)
let test_fold () =
  run "fold" (fun tc ->
      let l = int_list tc and k = pick tc 30 in
      let vars = [ ("xs", int_list_value l) ] in
      let sum = List.fold_left ( + ) 0 l in
      let rec while_ acc = function x :: rest when acc < k -> while_ (acc + x) rest | _ -> acc in
      let indexed = List.fold_left ( + ) 0 (List.mapi ( * ) l) in
      let cases =
        [ ("xs.fold(a, 0, x, a + x)", Value.Int (Int64.of_int sum));
          ("xs.fold(a, [], x, a + [x])", int_list_value l);
          ("xs.fold(a, [], x, [x] + a)", int_list_value (List.rev l));
          (Printf.sprintf "xs.fold_while(a, 0, x, a < %d, a + x)" k, Value.Int (Int64.of_int (while_ 0 l)));
          ("xs.fold(a, 0, i, x, a + i * x)", Value.Int (Int64.of_int indexed));
          ("xs.fold(a, 0, x, a + x) == xs.fold(a, 0, i, x, a + x)", Value.Bool true) ]
      in
      List.iter
        (fun (src, want) ->
          let got = eval_with vars src in
          note tc (Printf.sprintf "%s with xs = %s: %s, want %s" src (Value.to_string (int_list_value l)) (Value.to_string got) (Value.to_string want));
          Hegel.require tc ~msg:"fold disagrees with the model" (value_eq got want))
        cases)

(* A match without a wildcard is accepted exactly when it covers every
   case, and picks the case the model picks. *)
let test_match () =
  run "match" (fun tc ->
      let all = [ "A"; "B"; "C"; "D" ] in
      let cases = List.filteri (fun i _ -> i < 1 + pick tc 4) all in
      let unit = List.filter (fun _ -> pick tc 3 = 0) cases in
      let decl =
        "variant(" ^ String.concat ", " (List.map (fun c -> if List.mem c unit then c else c ^ "(int)") cases) ^ ")"
      in
      let covered = List.filter (fun _ -> pick tc 4 > 0) cases in
      let wildcard = pick tc 3 = 0 in
      let arm c = if List.mem c unit then Printf.sprintf "%s -> %d" c (Hashtbl.hash c mod 100) else Printf.sprintf "%s(v) -> v * %d" c (1 + Hashtbl.hash c mod 7) in
      let arms = List.map arm covered @ (if wildcard || covered = [] then [ "_ -> -1" ] else []) in
      let src = "match r { " ^ String.concat "; " arms ^ " }" in
      let env = Checker.declare_ident (Checker.empty ()) "r" (Checker.parse_type decl) in
      note tc (Printf.sprintf "r : %s\n%s" decl src);
      let exhaustive = wildcard || covered = [] || List.length covered = List.length cases in
      match Cel.parse src with
      | Error e -> Hegel.require tc ~msg:("parse: " ^ e) false
      | Ok ast -> (
          (match Checker.check env ast with
           | Ok _ -> Hegel.require tc ~msg:"a match missing a case was accepted" exhaustive
           | Error e -> note tc e; Hegel.require tc ~msg:"an exhaustive match was rejected" (not exhaustive));
          (* the value: the model picks the case by tag *)
          let tag = choose tc cases and payload = pick tc 21 - 10 in
          let r =
            Value.make_map
              ((Value.String "tag", Value.String tag)
               :: (if List.mem tag unit then [] else [ (Value.String "value", Value.Int (Int64.of_int payload)) ]))
          in
          let want =
            if List.mem tag covered then
              Value.Int (Int64.of_int (if List.mem tag unit then Hashtbl.hash tag mod 100 else payload * (1 + Hashtbl.hash tag mod 7)))
            else if wildcard || covered = [] then Value.Int (-1L)
            else Value.Error "no case"
          in
          let got = eval_with [ ("r", r) ] src in
          note tc (Printf.sprintf "tag %s, payload %d: %s, want %s" tag payload (Value.to_string got) (Value.to_string want));
          Hegel.require tc ~msg:"match picked the wrong case" (value_eq got want)))

(* Inlining preserves meaning: a generated expression, made the body of a
   library function over its variables, is called with generated argument
   expressions over the same variable names (L.f(v1 + 1, v0)), so a
   parameter that captured a caller's variable would show. The inlined call
   must equal the body evaluated with each parameter bound to its
   argument's value, and its cost stay within the estimate. *)
let test_inlining () =
  run "inlining" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let c = Typed_gen.case d in
      let scope = List.map (fun (n, t, _) -> (n, t)) c.vars in
      (* half the arguments are another variable of the same type: L.f(v1, v0) *)
      let args =
        List.map
          (fun (_, t, _) ->
            match List.filter (fun (_, u) -> u = t) scope with
            | _ :: _ as same when d.pick 2 = 0 -> fst (Typed_gen.choose d same)
            | _ -> Typed_gen.expr d scope t 2)
          c.vars
      in
      let params = String.concat ", " (List.map (fun (n, t, _) -> n ^ ": " ^ Typed_gen.ty_text t) c.vars) in
      let text = Printf.sprintf "f(%s): %s = %s;" params (Typed_gen.ty_text c.ty) c.src in
      let call = Printf.sprintf "L.f(%s)" (String.concat ", " args) in
      note tc (text ^ "\n" ^ call);
      match Library.parse text with
      | Error e -> Hegel.require tc ~msg:("a well-typed body was rejected: " ^ e) false
      | Ok lib -> (
          let env = Typed_gen.checker_env c in
          match Result.bind (Cel.parse call) (Library.compile env [ ("L", lib) ]) with
          | Error e -> Hegel.require tc ~msg:("the call was rejected: " ^ e) false
          | Ok (_, inlined) -> (
              let arg_values = List.map (eval_with (Typed_gen.bindings c)) args in
              let want =
                match List.find_opt Value.is_error arg_values with
                | Some e -> e (* a call is strict *)
                | None -> eval_with (List.map2 (fun (n, _, _) v -> (n, v)) c.vars arg_values) c.src
              in
              let r = Eval.run ~vars:(Typed_gen.bindings c) inlined in
              note tc (Printf.sprintf "want %s, inlined %s" (Value.to_string want) (Value.to_string r.value));
              Hegel.require tc ~msg:"inlining changed the value" (value_eq want r.value);
              let sizes = List.map (fun (n, _, v) -> (n, max 1 (max_size v))) c.vars in
              match Cost.estimate ~sizes env inlined with
              | Error e -> Hegel.require tc ~msg:("the inlined expression does not check: " ^ e) false
              | Ok est ->
                  note tc (Printf.sprintf "actual %d, estimate [%d, %d]" r.cost est.min est.max);
                  Hegel.require tc ~msg:"actual cost exceeds the estimate" (r.cost <= est.max))))

(* ---- lexer and parser ------------------------------------------------------------------------ *)

(* A CEL string literal for s, escaping as cel-spec allows. *)
let string_literal tc s =
  let b = Buffer.create 16 in
  Buffer.add_char b '"';
  let rec walk i =
    if i < String.length s then begin
      let d = String.get_utf_8_uchar s i in
      (if Uchar.utf_decode_is_valid d then
         let u = Uchar.utf_decode_uchar d in
         let cp = Uchar.to_int u in
         match pick tc 3, Uchar.to_char (if cp < 128 then u else Uchar.of_int 0) with
         | _, ('"' | '\\') when cp < 128 -> Buffer.add_char b '\\'; Buffer.add_char b (Char.chr cp)
         | _, _ when cp < 32 -> Buffer.add_string b (Printf.sprintf "\\x%02x" cp)
         | 0, _ when cp < 0x10000 -> Buffer.add_string b (Printf.sprintf "\\u%04x" cp)
         | 1, _ -> Buffer.add_string b (Printf.sprintf "\\U%08x" cp)
         | _ -> Buffer.add_utf_8_uchar b u);
      walk (i + Uchar.utf_decode_length d)
    end
  in
  walk 0;
  Buffer.add_char b '"';
  Buffer.contents b

let test_string_literals () =
  run "string-literals" (fun tc ->
      let n = pick tc 8 in
      let s =
        let b = Buffer.create 8 in
        for _ = 1 to n do
          Buffer.add_utf_8_uchar b
            (Uchar.of_int (choose tc [ 0x41; 0x22; 0x5c; 0x0a; 0x00; 0xe9; 0x4e2d; 0x1f600; 0x27; 0x7f ]))
        done;
        Buffer.contents b
      in
      let lit = string_literal tc s in
      note tc lit;
      match Cel.parse lit with
      | Ok { kind = Ast.Const (Value.String s'); _ } -> Hegel.require tc ~msg:"the literal decodes to its string" (s = s')
      | _ -> Hegel.require tc ~msg:"the literal parses as a string" false)

let test_number_literals () =
  run "number-literals" (fun tc ->
      let i = Hegel.draw_silent tc (Hegel.Generators.integers ()) in
      let d = float_of_int (pick tc 2000001 - 1000000) /. float_of_int (1 + pick tc 1000) in
      let parses src v = match Cel.parse src with Ok { kind = Ast.Const v'; _ } -> Value.equal v v' && Value.type_of v = Value.type_of v' | _ -> false in
      let hex = if i >= 0 then Printf.sprintf "0x%x" i else Printf.sprintf "-0x%x" (- i) in
      note tc (Printf.sprintf "%d %s %s" i hex (Value.format_double d));
      Hegel.require tc ~msg:"decimal int literal" (parses (string_of_int i) (Value.Int (Int64.of_int i)));
      Hegel.require tc ~msg:"hex int literal" (i = min_int || parses hex (Value.Int (Int64.of_int i)));
      Hegel.require tc ~msg:"double literal" (Float.is_integer d || parses (Value.format_double d) (Value.Double d)))

(* The tree without node ids, to compare parses. *)
let rec shape (e : Ast.expr) : string =
  match e.kind with
  | Ast.Const v -> Value.to_string v
  | Ast.Ident n -> n
  | Ast.Select { operand; field; test_only } -> Printf.sprintf "(%s.%s%s)" (shape operand) field (if test_only then "?" else "")
  | Ast.Call { fn; target; args } ->
      Printf.sprintf "%s%s(%s)" (match target with Some t -> shape t ^ "." | None -> "") fn (String.concat "," (List.map shape args))
  | Ast.List_lit es -> "[" ^ String.concat "," (List.map shape es) ^ "]"
  | Ast.Map_lit kvs -> "{" ^ String.concat "," (List.map (fun (k, v) -> shape k ^ ":" ^ shape v) kvs) ^ "}"
  | Ast.Struct_lit { tname; fields } -> tname ^ "{" ^ String.concat "," (List.map (fun (f, e, o) -> (if o then "?" else "") ^ f ^ ":" ^ shape e) fields) ^ "}"
  | Ast.Comprehension c ->
      Printf.sprintf "comp(%s,%s,%s,%s,%s,%s,%s,%s)" c.iter_var (Option.value c.iter_var2 ~default:"") (shape c.range) c.accu_var
        (shape c.accu_init) (shape c.loop_cond) (shape c.loop_step) (shape c.result)

(* Parenthesising, and whitespace and comments between tokens, never change the tree. *)
let test_parse_invariance () =
  run "parse-invariance" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      let spaced =
        String.concat "" (List.map (fun ch -> match ch with '(' -> "( " | ')' -> " )" | ',' -> " ,\n" | c -> String.make 1 c)
                            (List.init (String.length c.src) (String.get c.src)))
      in
      note tc c.src;
      match Cel.parse c.src, Cel.parse ("(" ^ c.src ^ ")"), Cel.parse (spaced ^ " // trailing comment") with
      | Ok a, Ok b, Ok s ->
          Hegel.require tc ~msg:"parentheses change nothing" (shape a = shape b);
          Hegel.require tc ~msg:"whitespace and comments change nothing" (shape a = shape s)
      | _ -> Hegel.require tc ~msg:"all three parse" false)

(* ---- values ------------------------------------------------------------------------------------- *)

let test_values () =
  run "values" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let t = Typed_gen.gen_ty d 2 in
      let a = Typed_gen.value d t and b = Typed_gen.value d t in
      note tc (Value.to_string a ^ " / " ^ Value.to_string b);
      let rec has_nan = function
        | Value.Double f -> Float.is_nan f
        | Value.List xs -> Array.exists has_nan xs
        | Value.Map m -> List.exists (fun (_, v) -> has_nan v) m
        | _ -> false
      in
      Hegel.require tc ~msg:"equality is reflexive" (has_nan a || Value.equal a a);
      Hegel.require tc ~msg:"equality is symmetric" (Value.equal a b = Value.equal b a);
      match a with
      | Value.Map m ->
          let shuffled = List.rev m in
          Hegel.require tc ~msg:"maps do not depend on insertion order" (Value.make_map shuffled = a)
      | _ -> ())

(* ---- time ---------------------------------------------------------------------------------------- *)

let test_time () =
  run "time" (fun tc ->
      let secs = Int64.add Ctime.min_ts (Int64.of_int (pick tc 1_000_000_000) |> Int64.mul 317L) in
      let secs = if secs > Ctime.max_ts then Ctime.max_ts else secs in
      let nanos = pick tc 1_000_000_000 in
      let dur = (Int64.of_int (pick tc 2_000_000 - 1_000_000), 0) in
      let dur = Ctime.normalize (fst dur) (if fst dur >= 0L then pick tc 1_000_000_000 else - pick tc 1_000_000_000) in
      let text = Ctime.format_timestamp (secs, nanos) in
      note tc (Printf.sprintf "%s %s" text (Ctime.format_duration dur));
      Hegel.require tc ~msg:"timestamps round-trip through RFC 3339" (Ctime.parse_timestamp text = Ok (secs, nanos));
      Hegel.require tc ~msg:"durations round-trip through their text"
        (Ctime.parse_duration (Ctime.format_duration dur) = Ok dur);
      (match Ctime.ts_add (secs, nanos) dur with
       | Ok t -> Hegel.require tc ~msg:"adding then subtracting a duration is the identity" (Ctime.ts_add t (Ctime.dur_neg dur) = Ok (secs, nanos))
       | Error _ -> ());
      let days = Ctime.floor_div secs 86400L in
      let y, m, dd = Ctime.civil_from_days days in
      Hegel.require tc ~msg:"civil dates round-trip" (Ctime.days_from_civil y m dd = days))

(* ---- protobuf messages ---------------------------------------------------------------------------- *)

let scalar_fields =
  [ ("single_int32", `I32); ("single_int64", `I64); ("single_uint32", `U32); ("single_uint64", `U64);
    ("single_float", `F); ("single_double", `D); ("single_bool", `B); ("single_string", `S); ("single_bytes", `By) ]

let field_value tc = function
  | `I32 -> Value.Int (Int64.of_int (pick tc 200001 - 100000))
  | `I64 -> Value.Int (Int64.of_int (Hegel.draw_silent tc (Hegel.Generators.integers ())))
  | `U32 -> Value.Uint (Int64.of_int (pick tc 100001))
  | `U64 -> Value.Uint (Int64.of_int (pick tc 1_000_000_000))
  | `F | `D -> Value.Double (float_of_int (pick tc 2001 - 1000) /. 8.0)
  | `B -> Value.Bool (pick tc 2 = 0)
  | `S -> Value.String (choose tc [ ""; "x"; "héllo" ])
  | `By -> Value.Bytes (choose tc [ ""; "\000"; "ab" ])

let test_proto_fields () =
  run "proto-fields" (fun tc ->
      let pkg = choose tc [ "proto2"; "proto3" ] in
      let tname = "cel.expr.conformance." ^ pkg ^ ".TestAllTypes" in
      let chosen = List.filter (fun _ -> pick tc 2 = 0) scalar_fields in
      let assigns = List.map (fun (f, k) -> (f, field_value tc k)) chosen in
      note tc (tname ^ " " ^ String.concat ", " (List.map (fun (f, v) -> f ^ "=" ^ Value.to_string v) assigns));
      match Proto.make tname assigns with
      | Value.Message { fields; _ } as m ->
          List.iter
            (fun (f, v) ->
              let got = Proto.select tname fields f ~test_only:false in
              Hegel.require tc ~msg:(f ^ " reads back what was assigned") (Value.equal got v);
              let zero = Value.equal v (Proto.select tname [] f ~test_only:false) in
              let has = Proto.select tname fields f ~test_only:true = Value.Bool true in
              (* proto2 fields have explicit presence; proto3 scalars are set when not zero *)
              Hegel.require tc ~msg:(f ^ ": has() follows the presence rules") (has = (pkg = "proto2" || not zero)))
            assigns;
          Hegel.require tc ~msg:"construction does not depend on assignment order"
            (Value.equal (Proto.make tname (List.rev assigns)) m)
      | v -> Hegel.require tc ~msg:("construction failed: " ^ Value.to_string v) false)

let () =
  Proto.load (Sys.argv.(1));
  Extensions.load ();
  let argv = Array.append [| Sys.argv.(0) |] (Array.sub Sys.argv 2 (Array.length Sys.argv - 2)) in
  Alcotest.run ~argv "cel-properties"
    [
      ( "checker",
        [ Alcotest.test_case "join is an upper bound" `Quick test_join_upper_bound;
          Alcotest.test_case "join is commutative and idempotent" `Quick test_join_commutative_idempotent;
          Alcotest.test_case "the most general of compatible types accepts both" `Quick test_most_general;
          Alcotest.test_case "well-typed expressions evaluate within their type" `Quick test_soundness ] );
      ("evaluation", [ Alcotest.test_case "deterministic" `Quick test_determinism ]);
      ("cost", [ Alcotest.test_case "actual cost is within the estimate" `Quick test_cost_soundness ]);
      ( "extensions",
        [ Alcotest.test_case "fold agrees with the model" `Quick test_fold;
          Alcotest.test_case "match: exhaustiveness and the chosen case" `Quick test_match;
          Alcotest.test_case "inlining preserves value, cost within the estimate" `Quick test_inlining ] );
      ( "syntax",
        [ Alcotest.test_case "string literals round-trip" `Quick test_string_literals;
          Alcotest.test_case "number literals round-trip" `Quick test_number_literals;
          Alcotest.test_case "parentheses and whitespace change nothing" `Quick test_parse_invariance ] );
      ("values", [ Alcotest.test_case "equality and map order" `Quick test_values ]);
      ("time", [ Alcotest.test_case "round trips and arithmetic" `Quick test_time ]);
      ("protobuf", [ Alcotest.test_case "fields, presence, order" `Quick test_proto_fields ]);
    ]
