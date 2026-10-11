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
  let check_error = match str "stage" theirs with Some "check" -> str "error" theirs | _ -> str "check_error" theirs in
  match ours, check_error, Option.map normalise_type (str "type" theirs) with
  | Result.Ok t, None, Some t' ->
      Hegel.note tc (Printf.sprintf "type: ours %s, cel-go %s" t t');
      Hegel.require tc ~msg:"checked types differ" (t = t')
  | Result.Error _, Some _, _ -> ()
  (* indexing null: this checker has the optional library's overloads, where null fits
     optional_type(list(A)) (cel-spec's legacy nullable types); cel/celgo runs cel-go without that
     library, so it has no overload for it *)
  | Result.Ok _, Some e, _ when Conformance_values.mentions e "'_[_]' applied to '(null" -> ()
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

(* The static estimates, side by side with cel-go's: ours must bound our
   actual cost (the soundness property); how it compares with cel-go's
   estimate is reported, not required, since the two estimators differ
   (cel-go has no input bounds and treats some calls more coarsely).
   CEL_DIFF_VERBOSE=1 prints each expression whose estimates differ. *)
let estimate_stats = Hashtbl.create 4
let tally k = Hashtbl.replace estimate_stats k (1 + Option.value (Hashtbl.find_opt estimate_stats k) ~default:0)

let compare_estimates tc src ast theirs our_cost =
  match Cost.estimate (Checker.empty ()) ast, field "estimate" theirs with
  | Result.Ok est, Some e -> (
      (match our_cost with
       | Some c ->
           Hegel.note tc (Printf.sprintf "actual %d, estimate [%d, %d]" c est.min est.max);
           Hegel.require tc ~msg:"actual cost exceeds the static estimate" (c <= est.max)
       | None -> ());
      match field "max" e with
      | Some (`Int m) ->
          if Sys.getenv_opt "CEL_DIFF_VERBOSE" <> None && est.max <> m then
            Printf.eprintf "estimate: ours %d, cel-go %d: %s\n" est.max m src;
          tally
            (if est.max = Cost.inf then "ours unbounded"
             else if est.max = m then "equal"
             else if est.max < m then "ours tighter"
             else "ours looser")
      | Some (`Intlit _) -> tally (if est.max = Cost.inf then "both unbounded" else "ours tighter (cel-go unbounded)")
      | _ -> ())
  | _ -> ()

(* ---- regular expressions (RE2 syntax, matched by code point: re2.ml) ------------------------- *)

(* a CEL string literal of any text: every character escaped by its code point *)
let cel_lit s =
  let b = Buffer.create (String.length s * 10) in
  Buffer.add_char b '"';
  let i = ref 0 in
  while !i < String.length s do
    let cp, len = Re2.decode s !i in
    i := !i + len;
    if (cp >= 0x61 && cp <= 0x7A) || (cp >= 0x41 && cp <= 0x5A) || (cp >= 0x30 && cp <= 0x39) then Buffer.add_char b (Char.chr cp)
    else Buffer.add_string b (Printf.sprintf "\\U%08x" cp)
  done;
  Buffer.add_char b '"';
  Buffer.contents b

(* text to match: ASCII, two-byte, three-byte and four-byte characters, a newline *)
let subject_chars = [ "a"; "b"; "c"; "A"; "1"; "_"; " "; "-"; "\n"; "\r"; "\xc3\xa9"; "\xc3\xbc"; "\xe2\x80\xa8"; "\xf0\x9f\x98\x80" ]

let subject tc =
  let n = pick tc 5 in
  String.concat "" (List.init n (fun _ -> choose tc subject_chars))

(* a pattern; [ascii] keeps it to ASCII, for under the i flag (no folding beyond ASCII here) *)
let rec pattern ?(ascii = false) tc depth =
  let lits = if ascii then [ "a"; "b"; "A"; "\\."; "-"; "1" ] else [ "a"; "b"; "A"; "\xc3\xa9"; "\xf0\x9f\x98\x80"; "\\."; "-"; "1"; "\\n" ] in
  let classes =
    [ "[a-c]"; "[^a-c]"; "[[:alpha:]]"; "[\\d_]"; "[^\\s]"; "[-a]"; "[]a]"; "\\d"; "\\w"; "\\s"; "\\D"; "\\W"; "\\S" ]
    @ if ascii then [] else [ "[\xc3\xa0-\xc3\xbf]"; "[^\xc3\xa9]"; "[\\x{1F600}-\\x{1F64F}]"; "[\\x{80}-\\x{10FFFF}]"; "\\x{e9}"; "\\x61"; "[\\x{2028}\\n]" ]
  in
  let atom () =
    match pick tc (if depth <= 0 then 5 else 10) with
    | 0 | 1 -> choose tc lits
    | 2 -> choose tc classes
    | 3 -> "."
    | 4 -> choose tc [ "^"; "$"; "\\A"; "\\z"; "\\b"; "\\B" ]
    | 5 -> "(" ^ pattern ~ascii tc (depth - 1) ^ ")"
    | 6 -> "(?:" ^ pattern ~ascii tc (depth - 1) ^ ")"
    | 7 -> "(?i:" ^ pattern ~ascii:true tc (depth - 1) ^ ")"
    | 8 -> choose tc [ "(?s:"; "(?m:"; "(?P<n>" ] ^ pattern ~ascii tc (depth - 1) ^ ")"
    | _ -> pattern ~ascii tc (depth - 1) ^ "|" ^ pattern ~ascii tc (depth - 1)
  in
  let quant () = choose tc [ ""; ""; ""; "*"; "+"; "?"; "{2}"; "{1,}"; "{0,2}"; "*?"; "+?"; "{1,2}?"; "{"; "{,2}" ] in
  (* now and then something RE2 rejects: both must reject it *)
  let junk () = choose tc [ "("; ")"; "\\1"; "(?=a)"; "[b-a]"; "a**"; "\\"; "[z" ] in
  let n = 1 + pick tc 3 in
  String.concat "" (List.init n (fun _ -> if pick tc 40 = 0 then junk () else atom () ^ quant ()))

let regex_cases path settings =
  let count = ref 0 and errors = ref 0 in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:differential-regex" (fun tc ->
      (* anchored half the time, so a character's length in bytes shows (a byte-wise . or class would
         still find something to match unanchored) *)
      let p = pattern tc 2 in
      let p = if pick tc 2 = 0 then "^(?:" ^ p ^ ")$" else p in
      let s = subject tc in
      let src = Printf.sprintf "%s.matches(%s)" (cel_lit s) (cel_lit p) in
      let ours = match Cel.eval src with Ok r -> r.value | Error e -> Value.Error e in
      let theirs = ask path src in
      incr count;
      if Value.is_error ours then (incr errors; if Sys.getenv_opt "CEL_DIFF_VERBOSE" <> None then Printf.eprintf "rejected %S: %s\n" p (Value.to_string ours));
      (* \b is ocaml-re's word boundary, whose words include Latin-1 letters: RE2's only on ASCII *)
      let ascii = String.for_all (fun c -> Char.code c < 0x80) s in
      let has_b = let re = Re.compile (Re.str "\\b") and re' = Re.compile (Re.str "\\B") in Re.execp re p || Re.execp re' p in
      if ascii || not has_b then begin
        Hegel.note tc (Printf.sprintf "pattern %S, text %S\nours: %s\ncel-go: %s" p s (Value.to_string ours) (Yojson.Safe.to_string theirs));
        Hegel.require tc ~msg:"cel-go's RE2 disagrees" (agree ours theirs)
      end);
  Printf.printf "%d patterns matched against cel-go's RE2 (%d rejected by both)\n" !count !errors

let () =
  let path = ref "" and cases = ref 500 in
  (* cel-go has no fold *)
  Typed_gen.extensions := false;
  Arg.parse [ ("--cases", Arg.Set_int cases, "test cases") ] (fun p -> path := p) "differential.exe CELGO";
  if !path = "" then (prerr_endline "usage: differential.exe CELGO"; exit 2);
  let checked = ref 0 and known = ref 0 in
  let settings = Hegel.Settings.create ~test_cases:!cases ~seed:7 () in
  regex_cases !path settings;
  Hegel.run_hegel_test ~settings ~database_key:"cordis:cel:differential" (fun tc ->
      iterates_map := false;
      let src = expr tc ~scope:[] 4 in
      let ours, our_cost =
        match Cel.eval src with Ok r -> (r.value, Some r.cost) | Error e -> (Value.Error e, None)
      in
      (* checked first: cel-go's checked program charges by overload, as
         this implementation always does; cel-go's unchecked programs charge
         1 for every call, so their cost is not compared *)
      let checked_resp = request !path (`Assoc [ ("expr", `String src) ]) in
      let was_checked = str "stage" checked_resp <> Some "check" in
      let theirs = if was_checked then checked_resp else ask !path src in
      incr checked;
      (match Cel.parse src with
       | Result.Ok ast when str "stage" theirs <> Some "parse" ->
           Hegel.note tc ("expr: " ^ src);
           compare_types tc (our_type (Checker.empty ()) ast) theirs;
           if was_checked then compare_estimates tc src ast theirs our_cost
       | _ -> ());
      if known_divergence ours theirs then incr known
      else begin
        Hegel.note tc (Printf.sprintf "expr: %s\nours: %s\ncel-go: %s" src (Value.to_string ours) (Yojson.Safe.to_string theirs));
        Hegel.require tc ~msg:"cel-go disagrees" (agree ours theirs);
        (* actual cost must match wherever both evaluated to a value *)
        match ours, theirs, our_cost, cost_of theirs with
        | v, `Assoc kv, Some mine, Some cg
          when was_checked && (not (Value.is_error v)) && List.mem_assoc "value" kv && not !iterates_map ->
            Hegel.note tc (Printf.sprintf "cost: ours %d, cel-go %d" mine cg);
            Hegel.require tc ~msg:"cost differs from cel-go" (mine = cg)
        | _ -> ()
      end);
  Printf.printf "%d expressions checked against cel-go; %d were known cel-go divergences (duplicate map keys, planning-time rejections)\n" !checked !known;
  Printf.printf "static estimate maximum against cel-go's: %s\n"
    (String.concat ", " (Hashtbl.fold (fun k v acc -> Printf.sprintf "%s %d" k v :: acc) estimate_stats [] |> List.sort compare));
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
               let v, cost = match Cel.eval ~vars:(Typed_gen.bindings c) c.src with Result.Ok r -> (r.value, Some r.cost) | Result.Error e -> (Value.Error e, None) in
               Hegel.note tc ("ours: " ^ Value.to_string v);
               if not (known_divergence v theirs) then begin
                 Hegel.require tc ~msg:"cel-go disagrees" (agree v theirs);
                 match cost, cost_of theirs with
                 | Some mine, Some cg when not (Value.is_error v) ->
                     Hegel.note tc (Printf.sprintf "cost: ours %d, cel-go %d" mine cg);
                     Hegel.require tc ~msg:"cost differs from cel-go" (mine = cg)
                 | _ -> ()
               end)
           | Result.Error e, _ -> Hegel.require tc ~msg:("this checker rejects a well-typed expression: " ^ e) false));
  Printf.printf "%d typed expressions checked against cel-go's checker\n" !typed
