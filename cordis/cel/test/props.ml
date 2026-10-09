(* Hegel properties for CEL, beside the conformance suite: the checker's
   algebra and soundness, the lexer and parser, values, determinism, time,
   and protobuf messages. Each property shrinks a failure to a minimal case. *)

open Cordis_cel

let settings = Hegel.Settings.create ~test_cases:300 ~seed:11 ()
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

let test_determinism () =
  run "determinism" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      note tc c.src;
      let once () = match Cel.eval ~vars:(Typed_gen.bindings c) c.src with Ok r -> Some (Value.to_string r.value, r.cost) | Error _ -> None in
      Hegel.require tc ~msg:"evaluation is a function of the expression and inputs" (once () = once ()))

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
      ( "syntax",
        [ Alcotest.test_case "string literals round-trip" `Quick test_string_literals;
          Alcotest.test_case "number literals round-trip" `Quick test_number_literals;
          Alcotest.test_case "parentheses and whitespace change nothing" `Quick test_parse_invariance ] );
      ("values", [ Alcotest.test_case "equality and map order" `Quick test_values ]);
      ("time", [ Alcotest.test_case "round trips and arithmetic" `Quick test_time ]);
      ("protobuf", [ Alcotest.test_case "fields, presence, order" `Quick test_proto_fields ]);
    ]
