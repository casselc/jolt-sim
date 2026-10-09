(* Differential test against cel-go (docs/cordis/rfc/0001-cel.md, "Testing"):
   Hegel draws CEL expressions from a grammar of core CEL; each is evaluated
   here and by cordis/cel/celgo (unchecked, so both sides use dynamic
   semantics). They must agree on the value, or both report an error. A
   disagreement shrinks to a minimal expression.

   The type checkers are compared too. Every expression above is also
   checked on both sides: both must accept or both reject it, and where both
   accept, the checked types must print the same. A second property draws
   well-typed expressions over typed variables (Typed_gen), declares the
   variables' types to both checkers, and compares the checked type and the
   value of the checked program.

     differential.exe CELGO [--cases N] *)

open Cordis_cel

(* ---- generating expressions --------------------------------------------------------- *)

let pick tc n = if n <= 1 then 0 else Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ())
let choose tc l = List.nth l (pick tc (List.length l))

let int_lits = [ "0"; "1"; "-1"; "2"; "7"; "-13"; "42"; "9223372036854775807"; "-9223372036854775808"; "0x10" ]
let uint_lits = [ "0u"; "1u"; "3u"; "18446744073709551615u" ]
let double_lits = [ "0.0"; "1.5"; "-2.25"; "1e10"; "-0.0"; "3.0"; "1e300"; ".5" ]
let string_lits = [ "''"; "'a'"; "'abc'"; "'héllo'"; "'b'"; "\"\\u00e9\""; "'ab' + 'c'" ]
let vars = [ "x"; "y" ]

(* Set when an expression iterates a map: cel-go iterates Go maps in random
   order, while this implementation fixes sorted order (RFC 0001,
   "Determinism"), so costs of short-circuiting macros over maps can differ
   without either being wrong. *)
let iterates_map = ref false

let rec expr tc ~scope depth =
  let leaf () =
    match pick tc 8 with
    | 0 | 1 -> choose tc int_lits
    | 2 -> choose tc uint_lits
    | 3 -> choose tc double_lits
    | 4 -> choose tc string_lits
    | 5 -> choose tc [ "true"; "false"; "null" ]
    | 6 -> if scope = [] then choose tc int_lits else choose tc scope
    | _ -> choose tc [ "b'ab'"; "b'\\x00'"; "[]"; "{}" ]
  in
  if depth <= 0 then leaf ()
  else
    let sub () = expr tc ~scope (depth - 1) in
    match pick tc 12 with
    | 0 | 1 -> leaf ()
    | 2 ->
        let op = choose tc [ "+"; "-"; "*"; "/"; "%" ] in
        Printf.sprintf "(%s %s %s)" (sub ()) op (sub ())
    | 3 ->
        let op = choose tc [ "=="; "!="; "<"; "<="; ">"; ">=" ] in
        Printf.sprintf "(%s %s %s)" (sub ()) op (sub ())
    | 4 -> Printf.sprintf "(%s %s %s)" (sub ()) (choose tc [ "&&"; "||" ]) (sub ())
    | 5 -> Printf.sprintf "(%s ? %s : %s)" (sub ()) (sub ()) (sub ())
    | 6 -> Printf.sprintf "%s(%s)" (choose tc [ "!"; "-"; "!!"; "--" ]) (sub ())
    | 7 ->
        let n = pick tc 4 in
        "[" ^ String.concat ", " (List.init n (fun _ -> sub ())) ^ "]"
    | 8 ->
        let n = pick tc 3 in
        let key () = choose tc [ "1"; "2"; "'k'"; "'j'"; "true"; "3u" ] in
        "{" ^ String.concat ", " (List.init n (fun _ -> key () ^ ": " ^ sub ())) ^ "}"
    | 9 ->
        let fn = choose tc [ "size"; "int"; "uint"; "double"; "string"; "type"; "dyn"; "bool"; "bytes" ] in
        Printf.sprintf "%s(%s)" fn (sub ())
    | 10 -> (
        match pick tc 4 with
        | 0 -> Printf.sprintf "(%s in %s)" (sub ()) (sub ())
        | 1 -> Printf.sprintf "%s[%s]" (sub ()) (sub ())
        | 2 ->
            Printf.sprintf "%s.%s(%s)" (sub ()) (choose tc [ "contains"; "startsWith"; "endsWith"; "matches" ]) (sub ())
        | _ -> Printf.sprintf "%s.size()" (sub ()))
    | _ ->
        (* a macro over a list or map literal, binding a fresh variable *)
        let v = choose tc vars in
        let over_map = pick tc 2 = 1 in
        let range =
          if over_map then (iterates_map := true; "{1: 'a', 'k': 2, true: 3.5}")
          else "[" ^ String.concat ", " (List.init (pick tc 4) (fun _ -> expr tc ~scope (depth - 2))) ^ "]"
        in
        let body = expr tc ~scope:(v :: scope) (depth - 1) in
        (* over a map only the order-independent macros: map and filter
           build lists in iteration order *)
        let m =
          if over_map then choose tc [ "all"; "exists"; "exists_one" ]
          else choose tc [ "all"; "exists"; "exists_one"; "map"; "filter" ]
        in
        Printf.sprintf "%s.%s(%s, %s)" range m v body

(* ---- cel-go, as a long-lived subprocess ------------------------------------------- *)

let celgo_proc = ref None

let celgo path =
  match !celgo_proc with
  | Some p -> p
  | None ->
      let p = Unix.open_process_args (path) [| path |] in
      celgo_proc := Some p;
      p

let request path (req : Yojson.Safe.t) =
  let ic, oc = celgo path in
  output_string oc (Yojson.Safe.to_string req);
  output_char oc '\n';
  flush oc;
  Yojson.Safe.from_string (input_line ic)

let ask path expr = request path (`Assoc [ ("expr", `String expr); ("disable_check", `Bool true) ])

let field name = function `Assoc kv -> List.assoc_opt name kv | _ -> None
let str name j = match field name j with Some (`String s) -> Some s | _ -> None

(* cel-go's FormatCELType spells a few types differently from cel-spec's
   type names, which this checker prints. *)
let normalise_type s =
  Re.replace (Re.compile (Re.Perl.re {|[A-Za-z_][A-Za-z0-9_.]*|})) s ~f:(fun g ->
      match Re.Group.get g 0 with "null" -> "null_type" | w -> w)

(* The checked type of [ast] here, as text, or the checker's error. *)
let our_type env ast = Result.map Checker.to_string (Checker.check env ast)

(* Both checkers accept or both reject; where both accept, the types agree.
   cel-go reports the checked type with disable_check set, so this rides on
   the same request as the value comparison. *)
let compare_types tc ours theirs =
  match ours, str "check_error" theirs, Option.map normalise_type (str "type" theirs) with
  | Result.Ok t, None, Some t' ->
      Hegel.note tc (Printf.sprintf "type: ours %s, cel-go %s" t t');
      Hegel.require tc ~msg:"checked types differ" (t = t')
  | Result.Error _, Some _, _ -> ()
  | Result.Ok t, Some e, _ ->
      Hegel.note tc (Printf.sprintf "ours: %s; cel-go: %s" t e);
      Hegel.require tc ~msg:"this checker accepts what cel-go's rejects" false
  | Result.Error e, None, t' ->
      Hegel.note tc (Printf.sprintf "ours: %s; cel-go: %s" e (Option.value t' ~default:"?"));
      Hegel.require tc ~msg:"this checker rejects what cel-go's accepts" false
  | Result.Ok _, None, None -> ()

let decode = Conformance_values.decode

let cost_of theirs = match theirs with `Assoc kv -> (match List.assoc_opt "cost" kv with Some (`Int n) -> Some n | _ -> None) | _ -> None

let agree ours theirs =
  match ours, theirs with
  | Value.Error _, `Assoc kv when List.mem_assoc "error" kv -> true
  | v, `Assoc kv when List.mem_assoc "value" kv -> (
      match decode (List.assoc "value" kv) with
      | w -> Conformance_values.same v w
      | exception Conformance_values.Unsupported _ -> true)
  | _ -> false

(* Divergences that are cel-go's: duplicate map keys (on cel-go's own
   conformance skip list), and expressions cel-go rejects while planning the
   program (e.g. a constant null index), before the evaluation that the spec
   says would absorb the error. *)
let known_divergence ours theirs =
  match ours, theirs with
  | Value.Error e, `Assoc kv when List.mem_assoc "value" kv -> Conformance_values.mentions e "repeated key"
  | _, `Assoc kv -> List.assoc_opt "stage" kv = Some (`String "program")
  | _ -> false

let () =
  let path = ref "" and cases = ref 500 in
  Arg.parse [ ("--cases", Arg.Set_int cases, "test cases") ] (fun p -> path := p) "differential.exe CELGO";
  if !path = "" then (prerr_endline "usage: differential.exe CELGO"; exit 2);
  let checked = ref 0 and known = ref 0 in
  let settings = Hegel.Settings.create ~test_cases:!cases ~seed:7 () in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:differential" (fun tc ->
      iterates_map := false;
      let src = expr tc ~scope:[] 4 in
      let ours, our_cost =
        match Cel.eval src with Ok r -> (r.value, Some r.cost) | Error e -> (Value.Error e, None)
      in
      let theirs = ask !path src in
      incr checked;
      (match Cel.parse src with
       | Result.Ok ast when str "stage" theirs <> Some "parse" ->
           Hegel.note tc ("expr: " ^ src);
           compare_types tc (our_type (Checker.empty ()) ast) theirs
       | _ -> ());
      if known_divergence ours theirs then incr known
      else begin
        Hegel.note tc (Printf.sprintf "expr: %s\nours: %s\ncel-go: %s" src (Value.to_string ours) (Yojson.Safe.to_string theirs));
        Hegel.require tc ~msg:"cel-go disagrees" (agree ours theirs);
        (* actual cost must match wherever both evaluated to a value *)
        match ours, theirs, our_cost, cost_of theirs with
        | v, `Assoc kv, Some mine, Some cg
          when (not (Value.is_error v)) && List.mem_assoc "value" kv && not !iterates_map ->
            Hegel.note tc (Printf.sprintf "cost: ours %d, cel-go %d" mine cg);
            Hegel.require tc ~msg:"cost differs from cel-go" (mine = cg)
        | _ -> ()
      end);
  Printf.printf "%d expressions checked against cel-go; %d were known cel-go divergences (duplicate map keys, planning-time rejections)\n" !checked !known;
  (* well-typed expressions over typed variables, checked on both sides *)
  let typed = ref 0 in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:differential-typed" (fun tc ->
      let c = Typed_gen.case (Typed_gen.of_hegel tc) in
      let req =
        `Assoc
          [ ("expr", `String c.src);
            ("types", `Assoc (List.map (fun (n, t, _) -> (n, `String (Typed_gen.ty_text t))) c.vars));
            ("bindings", `Assoc (List.map (fun (n, _, v) -> (n, Typed_gen.encode v)) c.vars)) ]
      in
      let theirs = request !path req in
      incr typed;
      Hegel.note tc (Printf.sprintf "expr: %s\nvars: %s\ncel-go: %s" c.src
        (String.concat ", " (List.map (fun (n, t, v) -> Printf.sprintf "%s: %s = %s" n (Typed_gen.ty_text t) (Value.to_string v)) c.vars))
        (Yojson.Safe.to_string theirs));
      match Cel.parse c.src with
      | Result.Error e -> Hegel.require tc ~msg:("generated expression does not parse: " ^ e) false
      | Result.Ok ast ->
          let ours = our_type (Typed_gen.checker_env c) ast in
          (match ours, str "stage" theirs with
           | Result.Ok _, Some "check" -> Hegel.require tc ~msg:"cel-go's checker rejects a well-typed expression" false
           | Result.Ok t, _ -> (
               (match Option.map normalise_type (str "type" theirs) with
                | Some t' -> Hegel.note tc ("type: ours " ^ t ^ ", cel-go " ^ t'); Hegel.require tc ~msg:"checked types differ" (t = t')
                | None -> ());
               let v = match Cel.eval ~vars:(Typed_gen.bindings c) c.src with Result.Ok r -> r.value | Result.Error e -> Value.Error e in
               Hegel.note tc ("ours: " ^ Value.to_string v);
               if not (known_divergence v theirs) then Hegel.require tc ~msg:"cel-go disagrees" (agree v theirs))
           | Result.Error e, _ -> Hegel.require tc ~msg:("this checker rejects a well-typed expression: " ^ e) false));
  Printf.printf "%d typed expressions checked against cel-go's checker\n" !typed
