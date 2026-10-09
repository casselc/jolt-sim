(* The CEL parser (cel-spec doc/langdef.md, "Syntax"), expanding the
   standard macros into comprehensions as it goes. *)

open Lexer
open Ast

exception Parse_error of string

(* raised before a match's '{': the word was the identifier match *)
exception Not_a_match

let fail fmt = Printf.ksprintf (fun s -> raise (Parse_error s)) fmt

(* [no_struct]: parsing a match's scrutinee, where `v {` opens the cases,
   not a message literal; brackets of any kind turn it back off. *)
type state = { mutable toks : token list; mutable next_id : int; macros : bool; mutable no_struct : bool }

let nested st f =
  let saved = st.no_struct in
  st.no_struct <- false;
  Fun.protect ~finally:(fun () -> st.no_struct <- saved) (fun () -> f ())

let mk st kind =
  st.next_id <- st.next_id + 1;
  { id = st.next_id; kind }

let peek st = match st.toks with t :: _ -> t | [] -> EOF
let peek2 st = match st.toks with _ :: t :: _ -> t | _ -> EOF
let advance st = match st.toks with _ :: rest -> st.toks <- rest | [] -> ()

let expect st t what =
  if peek st = t then advance st else fail "expected %s" what

(* ---- literals ------------------------------------------------------------------- *)

let int_literal ~neg digits =
  let s = (if neg then "-" else "") ^ digits in
  let is_hex = String.length digits > 2 && (digits.[1] = 'x' || digits.[1] = 'X') in
  if is_hex then begin
    (* OCaml reads hex as a 64-bit pattern; CEL reads it as a magnitude *)
    let body = String.sub digits 2 (String.length digits - 2) in
    let k = ref 0 in
    while !k < String.length body - 1 && body.[!k] = '0' do incr k done;
    let body = String.sub body !k (String.length body - !k) in
    let width = String.length body in
    let mag = match Int64.of_string_opt ("0x" ^ body) with Some m -> m | None -> fail "bad int literal %s" s in
    if width > 16 then fail "int literal out of range: %s" s
    else if Int64.compare mag 0L >= 0 then (if neg then Int64.neg mag else mag)
    else if neg && mag = Int64.min_int then Int64.min_int
    else fail "int literal out of range: %s" s
  end
  else
    match Int64.of_string_opt s with
    | Some v -> v
    | None -> fail "int literal out of range: %s" s

let uint_literal digits =
  let is_hex = String.length digits > 2 && (digits.[1] = 'x' || digits.[1] = 'X') in
  let s = if is_hex then digits else "0u" ^ digits in
  match Int64.of_string_opt s with
  | Some v -> v
  | None -> fail "uint literal out of range: %s" digits

let double_literal ~neg text =
  match float_of_string_opt text with
  | Some f -> if neg then -.f else f
  | None -> fail "bad double literal %s" text

(* ---- macros --------------------------------------------------------------------- *)

let accu = "@result"

let ident_name e = match e.kind with Ident n -> Some n | _ -> None

let comprehension st ~iter_var ?iter_var2 ~range ~init ~cond ~step ~result () =
  mk st
    (Comprehension
       { iter_var; iter_var2; range; accu_var = accu; accu_init = init; loop_cond = cond;
         loop_step = step; result })

let call st fn args = mk st (Call { fn; target = None; args })
let const st v = mk st (Const v)
let id_ st n = mk st (Ident n)

let rec expand_receiver_macro st target name args =
  let var e =
    match ident_name e with Some n -> n | None -> fail "argument is not an identifier"
  in
  let a () = id_ st accu in
  let nsf e = call st op_not_strictly_false [ e ] in
  match name, args with
  | "all", [ x; p ] ->
      Some
        (comprehension st ~iter_var:(var x) ~range:target ~init:(const st (Value.Bool true))
           ~cond:(nsf (a ())) ~step:(call st op_and [ a (); p ]) ~result:(a ()) ())
  | "exists", [ x; p ] ->
      Some
        (comprehension st ~iter_var:(var x) ~range:target ~init:(const st (Value.Bool false))
           ~cond:(nsf (call st op_not [ a () ])) ~step:(call st op_or [ a (); p ]) ~result:(a ()) ())
  | "exists_one", [ x; p ] ->
      Some
        (comprehension st ~iter_var:(var x) ~range:target ~init:(const st (Value.Int 0L))
           ~cond:(const st (Value.Bool true))
           ~step:(call st op_cond [ p; call st "_+_" [ a (); const st (Value.Int 1L) ]; a () ])
           ~result:(call st "_==_" [ a (); const st (Value.Int 1L) ]) ())
  | "map", [ x; t ] ->
      Some
        (comprehension st ~iter_var:(var x) ~range:target ~init:(mk st (List_lit []))
           ~cond:(const st (Value.Bool true))
           ~step:(call st "_+_" [ a (); mk st (List_lit [ t ]) ]) ~result:(a ()) ())
  | "map", [ x; p; t ] ->
      Some
        (comprehension st ~iter_var:(var x) ~range:target ~init:(mk st (List_lit []))
           ~cond:(const st (Value.Bool true))
           ~step:(call st op_cond [ p; call st "_+_" [ a (); mk st (List_lit [ t ]) ]; a () ])
           ~result:(a ()) ())
  | "filter", [ x; p ] ->
      let v = var x in
      Some
        (comprehension st ~iter_var:v ~range:target ~init:(mk st (List_lit []))
           ~cond:(const st (Value.Bool true))
           ~step:(call st op_cond [ p; call st "_+_" [ a (); mk st (List_lit [ id_ st v ]) ]; a () ])
           ~result:(a ()) ())
  (* two-variable comprehensions (cel-spec's comprehensions extension):
     over a list the variables are index and value, over a map key and value *)
  | ("all" | "exists" | "existsOne" | "exists_one"), [ x; y; p ]
    when ident_name x <> None && ident_name y <> None ->
      let v1 = var x and v2 = var y in
      let comp ~init ~cond ~step ~result =
        comprehension st ~iter_var:v1 ~iter_var2:v2 ~range:target ~init ~cond ~step ~result ()
      in
      Some
        (match name with
         | "all" ->
             comp ~init:(const st (Value.Bool true)) ~cond:(nsf (a ()))
               ~step:(call st op_and [ a (); p ]) ~result:(a ())
         | "exists" ->
             comp ~init:(const st (Value.Bool false)) ~cond:(nsf (call st op_not [ a () ]))
               ~step:(call st op_or [ a (); p ]) ~result:(a ())
         | _ ->
             comp ~init:(const st (Value.Int 0L)) ~cond:(const st (Value.Bool true))
               ~step:(call st op_cond [ p; call st "_+_" [ a (); const st (Value.Int 1L) ]; a () ])
               ~result:(call st "_==_" [ a (); const st (Value.Int 1L) ]))
  | "existsOne", [ x; p ] -> expand_receiver_macro st target "exists_one" [ x; p ]
  | ("transformList" | "transformMap" | "transformMapEntry"), x :: y :: rest
    when ident_name x <> None && ident_name y <> None && (List.length rest = 1 || List.length rest = 2) ->
      let v1 = var x and v2 = var y in
      let filter, t = match rest with [ t ] -> (None, t) | [ p; t ] -> (Some p, t) | _ -> assert false in
      let init, add =
        match name with
        | "transformList" -> (mk st (List_lit []), call st "_+_" [ a (); mk st (List_lit [ t ]) ])
        | "transformMap" -> (mk st (Map_lit []), call st "@map_insert" [ a (); id_ st v1; t ])
        | _ -> (mk st (Map_lit []), call st "@map_merge" [ a (); t ])
      in
      let step = match filter with None -> add | Some p -> call st op_cond [ p; add; a () ] in
      Some
        (comprehension st ~iter_var:v1 ~iter_var2:v2 ~range:target ~init
           ~cond:(const st (Value.Bool true)) ~step ~result:(a ()) ())
  | _ -> None

(* ---- grammar -------------------------------------------------------------------- *)

let rec parse_expr st =
  let c = parse_or st in
  if peek st = QUESTION then begin
    advance st;
    let t = parse_or st in
    expect st COLON "':'";
    let f = parse_expr st in
    call st op_cond [ c; t; f ]
  end
  else c

and parse_or st =
  let l = ref (parse_and st) in
  while peek st = OR do
    advance st;
    let r = parse_and st in
    l := call st op_or [ !l; r ]
  done;
  !l

and parse_and st =
  let l = ref (parse_relation st) in
  while peek st = AND do
    advance st;
    let r = parse_relation st in
    l := call st op_and [ !l; r ]
  done;
  !l

and parse_relation st =
  let l = ref (parse_additive st) in
  let rec loop () =
    let op =
      match peek st with
      | EQ -> Some "_==_" | NE -> Some "_!=_" | LT -> Some "_<_" | LE -> Some "_<=_"
      | GT -> Some "_>_" | GE -> Some "_>=_" | IN -> Some op_in | _ -> None
    in
    match op with
    | Some op ->
        advance st;
        let r = parse_additive st in
        l := call st op [ !l; r ];
        loop ()
    | None -> ()
  in
  loop ();
  !l

and parse_additive st =
  let l = ref (parse_multiplicative st) in
  let rec loop () =
    match peek st with
    | PLUS -> advance st; l := call st "_+_" [ !l; parse_multiplicative st ]; loop ()
    | MINUS -> advance st; l := call st "_-_" [ !l; parse_multiplicative st ]; loop ()
    | _ -> ()
  in
  loop ();
  !l

and parse_multiplicative st =
  let l = ref (parse_unary st) in
  let rec loop () =
    match peek st with
    | STAR -> advance st; l := call st "_*_" [ !l; parse_unary st ]; loop ()
    | SLASH -> advance st; l := call st "_/_" [ !l; parse_unary st ]; loop ()
    | PERCENT -> advance st; l := call st "_%_" [ !l; parse_unary st ]; loop ()
    | _ -> ()
  in
  loop ();
  !l

and parse_unary st =
  match peek st with
  | NOT ->
      let n = ref 0 in
      while peek st = NOT do advance st; incr n done;
      let e = parse_member st in
      (* as cel-go does, pairs cancel: !!x is x *)
      if !n mod 2 = 1 then call st op_not [ e ] else e
  | MINUS ->
      let n = ref 0 in
      while peek st = MINUS do advance st; incr n done;
      (* fold one minus into a numeric literal, so the int minimum parses *)
      let e =
        match peek st with
        | INT d when !n mod 2 = 1 ->
            advance st; decr n;
            parse_member_suffix st (const st (Value.Int (int_literal ~neg:true d)))
        | DOUBLE d when !n mod 2 = 1 ->
            advance st; decr n;
            parse_member_suffix st (const st (Value.Double (double_literal ~neg:true d)))
        | _ -> parse_member st
      in
      (* as cel-go does, pairs cancel: --x is x *)
      if !n mod 2 = 1 then call st op_neg [ e ] else e
  | _ -> parse_member st

and parse_member st = parse_member_suffix st (parse_primary st)

and parse_member_suffix st e =
  match peek st with
  | DOT -> (
      advance st;
      (* x.?f: optional selection, encoded as the reserved call _?._ *)
      let optional = peek st = QUESTION in
      if optional then advance st;
      let field =
        match peek st with
        | IDENT f -> advance st; f
        | QUOTED_IDENT f -> advance st; f
        | _ -> fail "expected a field name after '.'"
      in
      if optional then parse_member_suffix st (call st op_opt_select [ e; const st (Value.String field) ])
      else if peek st = LPAREN then begin
        advance st;
        let args = parse_args st RPAREN in
        let expanded =
          if not st.macros then None
          else
            match expand_receiver_macro st e field args with
            | Some m -> Some m
            | None -> library_macro st (Some e) field args
        in
        match expanded with
        | Some m -> parse_member_suffix st m
        | None -> parse_member_suffix st (mk st (Call { fn = field; target = Some e; args }))
      end
      else parse_member_suffix st (mk st (Select { operand = e; field; test_only = false })))
  | LBRACKET ->
      advance st;
      let optional = peek st = QUESTION in
      if optional then advance st;
      let i = nested st (fun () -> parse_expr st) in
      expect st RBRACKET "']'";
      parse_member_suffix st (call st (if optional then op_opt_index else op_index) [ e; i ])
  | LBRACE when (not st.no_struct) && (match e.kind with Ident _ | Select { test_only = false; _ } -> true | _ -> false) ->
      (* a message literal: the receiver is the (qualified) type name *)
      let rec name (e : expr) =
        match e.kind with
        | Ident n -> n
        | Select { operand; field; _ } -> name operand ^ "." ^ field
        | _ -> fail "bad message type name"
      in
      let tname = name e in
      advance st;
      let rec go acc =
        if peek st = RBRACE then (advance st; List.rev acc)
        else begin
          let optional = peek st = QUESTION in
          if optional then advance st;
          let field =
            match peek st with
            | IDENT f | QUOTED_IDENT f -> advance st; f
            | _ -> fail "expected a field name"
          in
          expect st COLON "':'";
          let v = parse_expr st in
          match peek st with
          | COMMA -> advance st; go ((field, v, optional) :: acc)
          | RBRACE -> advance st; List.rev ((field, v, optional) :: acc)
          | _ -> fail "expected ',' or '}'"
        end
      in
      parse_member_suffix st (mk st (Struct_lit { tname; fields = go [] }))
  | _ -> e

and parse_args st close = nested st (fun () -> parse_args_ st close)

and parse_args_ st close =
  if peek st = close then (advance st; [])
  else
    let rec go acc =
      let a = parse_expr st in
      match peek st with
      | COMMA ->
          advance st;
          if peek st = close then (advance st; List.rev (a :: acc)) else go (a :: acc)
      | t when t = close -> advance st; List.rev (a :: acc)
      | _ -> fail "expected ',' or closing bracket"
    in
    go []

and parse_primary st =
  match peek st with
  | INT d -> advance st; const st (Value.Int (int_literal ~neg:false d))
  | UINT d -> advance st; const st (Value.Uint (uint_literal d))
  | DOUBLE d -> advance st; const st (Value.Double (double_literal ~neg:false d))
  | STRING s -> advance st; const st (Value.String s)
  | BYTES b -> advance st; const st (Value.Bytes b)
  | TRUE -> advance st; const st (Value.Bool true)
  | FALSE -> advance st; const st (Value.Bool false)
  | NULL -> advance st; const st Value.Null
  | LPAREN ->
      advance st;
      let e = nested st (fun () -> parse_expr st) in
      expect st RPAREN "')'";
      e
  | LBRACKET -> nested st (fun () -> parse_list st)
  | LBRACE -> nested st (fun () -> parse_map st)
  | DOT -> (
      (* a leading dot names the root scope: .x *)
      advance st;
      match peek st with
      | IDENT n -> advance st; primary_ident st ("." ^ n)
      | _ -> fail "expected an identifier after '.'")
  | IDENT "match" when st.macros && starts_expr (peek2 st) -> (
      (* match v { Tag(x) -> e; Other -> e; _ -> e }, or the identifier
         match when what follows is not a match *)
      let saved = st.toks and saved_id = st.next_id in
      advance st;
      try parse_match st
      with Not_a_match ->
        st.toks <- saved;
        st.next_id <- saved_id;
        advance st;
        primary_ident st "match")
  | IDENT n ->
      (* reserved words may name fields and methods (after '.'), not values *)
      if List.mem n Lexer.reserved then fail "reserved identifier: %s" n;
      advance st;
      primary_ident st n
  | t ->
      ignore t;
      fail "unexpected token"

and parse_list st =
      advance st;
      (* [?e]: an element present only when the optional e has a value *)
      let rec go acc =
        if peek st = RBRACKET then (advance st; List.rev acc)
        else begin
          let optional = peek st = QUESTION in
          if optional then advance st;
          let e = parse_expr st in
          let e = if optional then call st op_opt_elem [ e ] else e in
          match peek st with
          | COMMA -> advance st; go (e :: acc)
          | RBRACKET -> advance st; List.rev (e :: acc)
          | _ -> fail "expected ',' or ']'"
        end
      in
      mk st (List_lit (go []))

and parse_map st =
      advance st;
      let rec go acc =
        if peek st = RBRACE then (advance st; List.rev acc)
        else begin
          (* {?k: v}: an entry present only when the optional v has a value *)
          let optional = peek st = QUESTION in
          if optional then advance st;
          let k = parse_expr st in
          let k = if optional then call st op_opt_elem [ k ] else k in
          expect st COLON "':'";
          let v = parse_expr st in
          match peek st with
          | COMMA -> advance st; go ((k, v) :: acc)
          | RBRACE -> advance st; List.rev ((k, v) :: acc)
          | _ -> fail "expected ',' or '}'"
        end
      in
      mk st (Map_lit (go []))

(* A match (RFC 0001, "Extensions"): the scrutinee, then cases
   Tag(x) -> e, Tag -> e, _ -> e, separated by ';' or ','. It expands to
   conditionals over the reserved functions @match_is (does the value have
   this tag?), @match_value (its payload) and @match_fail (no case matched,
   naming the cases covered so the checker can report the missing ones). *)
and parse_match st =
  st.no_struct <- true;
  let scrutinee =
    Fun.protect ~finally:(fun () -> st.no_struct <- false) (fun () ->
        try parse_expr st with Parse_error _ -> raise Not_a_match)
  in
  if peek st <> LBRACE then raise Not_a_match;
  advance st;
  let rec cases acc =
    match peek st with
    | RBRACE -> advance st; List.rev acc
    | IDENT tag ->
        advance st;
        let binder =
          if peek st = LPAREN then begin
            advance st;
            let b = match peek st with IDENT b -> advance st; b | _ -> fail "expected a variable in the case pattern" in
            expect st RPAREN "')'";
            Some b
          end
          else None
        in
        expect st ARROW "'->'";
        let body = nested st (fun () -> parse_expr st) in
        let acc = (tag, binder, body) :: acc in
        (match peek st with
         | SEMI | COMMA -> advance st
         | RBRACE -> ()
         | _ -> fail "expected ';' or '}' after a match case");
        cases acc
    | _ -> fail "expected a match case"
  in
  let cs = cases [] in
  if cs = [] then fail "a match needs at least one case";
  let tags = List.filter_map (fun (t, _, _) -> if t = "_" then None else Some t) cs in
  List.iter (fun t -> if List.length (List.filter (( = ) t) tags) > 1 then fail "duplicate match case %s" t) tags;
  let m = Printf.sprintf "@match%d" st.next_id in
  let mv () = id_ st m and tag t = const st (Value.String t) in
  let rec chain = function
    | [] -> call st "@match_fail" [ mv (); mk st (List_lit (List.map tag tags)) ]
    | [ ("_", None, body) ] -> body
    | ("_", _, _) :: _ -> fail "a wildcard case must come last and binds nothing"
    | (t, binder, body) :: rest ->
        let body =
          match binder with
          | None -> body
          | Some b -> Ext_cel.bind ~mk:(mk st) b (call st "@match_value" [ mv (); tag t ]) body
        in
        call st op_cond [ call st "@match_is" [ mv (); tag t ]; body; chain rest ]
  in
  Ext_cel.bind ~mk:(mk st) m scrutinee (chain cs)

and primary_ident st n =
  if peek st = LPAREN then begin
    advance st;
    let args = parse_args st RPAREN in
    match n, args with
    | "has", [ { kind = Select { operand; field; _ }; _ } ] when st.macros ->
        mk st (Select { operand; field; test_only = true })
    | "has", _ when st.macros -> fail "has() requires a field selection"
    | _ -> (
        match if st.macros then library_macro st None n args else None with
        | Some m -> m
        | None -> mk st (Call { fn = n; target = None; args }))
  end
  else mk st (Ident n)

(* A library macro: by name for a global or receiver call, or by the full
   name when the receiver is an identifier path (math.greatest). *)
and library_macro st target name args =
  let mkk k = mk st k in
  let rec path (e : expr) =
    match e.kind with
    | Ident n -> Some n
    | Select { operand; field; test_only = false } -> Option.map (fun p -> p ^ "." ^ field) (path operand)
    | _ -> None
  in
  let qualified = match target with Some t -> Option.map (fun p -> p ^ "." ^ name) (path t) | None -> None in
  match Option.bind qualified Registry.find_macro with
  | Some ex -> ex ~mk:mkk ~target:None args
  | None -> (
      match Registry.find_macro name with
      | Some ex -> ex ~mk:mkk ~target args
      | None -> None)

(* Can this token begin an expression (the scrutinee after `match`)? *)
and starts_expr = function
  | INT _ | UINT _ | DOUBLE _ | STRING _ | BYTES _ | IDENT _ | TRUE | FALSE | NULL
  | LPAREN | LBRACKET | LBRACE | DOT | NOT | MINUS ->
      true
  | _ -> false

let parse ?(macros = true) src =
  match Lexer.tokenize src with
  | exception Lexer.Lex_error m -> Error ("lex: " ^ m)
  | toks -> (
      let st = { toks; next_id = 0; macros; no_struct = false } in
      match parse_expr st with
      | exception Parse_error m -> Error ("parse: " ^ m)
      | e -> if peek st = EOF then Ok e else Error "parse: unexpected trailing input")
