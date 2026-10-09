(* The canonical text of a CEL expression: its tokens, printed with fixed
   spacing. Comments, whitespace and the spelling of string literals (quote
   style, raw or escaped) do not survive, so they never change a hash; the
   text re-lexes to the same tokens, so it parses to the same tree. *)

open Cordis_cel.Lexer

let escape_string s =
  let b = Buffer.create (String.length s + 2) in
  Buffer.add_char b '"';
  String.iter
    (fun c ->
      match c with
      | '"' -> Buffer.add_string b "\\\""
      | '\\' -> Buffer.add_string b "\\\\"
      | '\n' -> Buffer.add_string b "\\n"
      | '\r' -> Buffer.add_string b "\\r"
      | '\t' -> Buffer.add_string b "\\t"
      | c when Char.code c < 0x20 || Char.code c = 0x7f -> Buffer.add_string b (Printf.sprintf "\\x%02x" (Char.code c))
      | c -> Buffer.add_char b c (* UTF-8 passes through *))
    s;
  Buffer.add_char b '"';
  Buffer.contents b

let escape_bytes s =
  let b = Buffer.create (String.length s + 3) in
  Buffer.add_string b "b\"";
  String.iter
    (fun c ->
      match c with
      | '"' -> Buffer.add_string b "\\\""
      | '\\' -> Buffer.add_string b "\\\\"
      | c when Char.code c < 0x20 || Char.code c >= 0x7f -> Buffer.add_string b (Printf.sprintf "\\x%02x" (Char.code c))
      | c -> Buffer.add_char b c)
    s;
  Buffer.add_char b '"';
  Buffer.contents b

let token_text = function
  | INT d -> d
  | UINT d -> d ^ "u"
  | DOUBLE d -> d
  | STRING s -> escape_string s
  | BYTES s -> escape_bytes s
  | IDENT n -> n
  | QUOTED_IDENT n -> "`" ^ n ^ "`"
  | TRUE -> "true" | FALSE -> "false" | NULL -> "null" | IN -> "in"
  | LPAREN -> "(" | RPAREN -> ")" | LBRACKET -> "[" | RBRACKET -> "]" | LBRACE -> "{" | RBRACE -> "}"
  | DOT -> "." | COMMA -> "," | COLON -> ":" | QUESTION -> "?"
  | ARROW -> "->" | SEMI -> ";"
  | NOT -> "!" | MINUS -> "-" | PLUS -> "+" | STAR -> "*" | SLASH -> "/" | PERCENT -> "%"
  | EQ -> "==" | NE -> "!=" | LT -> "<" | LE -> "<=" | GT -> ">" | GE -> ">=" | AND -> "&&" | OR -> "||"
  | EOF -> ""

(* No space after an opener, a dot or a unary operator; none before a
   closer, a dot, a comma, a ';' or an entry's colon; none between a name
   and its call, index or message literal. A conditional's ':' and a
   match's '{' are spaced, which needs to know, per bracket depth, how many
   '?' are open and whether a 'match' is waiting for its '{'. Everything
   else is separated by one space. *)
let print tokens =
  let b = Buffer.create 64 in
  (* per depth: open conditionals, a match awaiting its brace *)
  let stack = ref [ (ref 0, ref false) ] in
  let top () = List.hd !stack in
  let rec go prev = function
    | [] | EOF :: _ -> ()
    | t :: rest ->
        let unary_prev =
          (* ! and - are unary after an operator, an opener or at the start *)
          match prev with
          | Some ((NOT | MINUS), before) -> (
              match before with
              | None -> true
              | Some (INT _ | UINT _ | DOUBLE _ | STRING _ | BYTES _ | IDENT _ | QUOTED_IDENT _ | TRUE | FALSE | NULL
                     | RPAREN | RBRACKET | RBRACE) ->
                  false
              | Some _ -> true)
          | _ -> false
        in
        let conds, matching = top () in
        let ternary_colon = t = COLON && !conds > 0 in
        let match_brace = t = LBRACE && !matching in
        let space =
          match prev with
          | None -> false
          | Some (p, _) -> (
              match p, t with
              | (LPAREN | LBRACKET | LBRACE | DOT), _ -> false
              | _, COLON -> ternary_colon
              | _, LBRACE when match_brace -> true
              | _, (RPAREN | RBRACKET | RBRACE | DOT | COMMA | SEMI) -> false
              | (IDENT _ | QUOTED_IDENT _), (LPAREN | LBRACE) -> false
              | (IDENT _ | QUOTED_IDENT _ | RPAREN | RBRACKET | STRING _), LBRACKET -> false
              | (NOT | MINUS), _ when unary_prev -> false
              | QUESTION, _ when (match Option.bind (Option.map snd prev) Fun.id with Some (DOT | LBRACKET) -> true | _ -> false) -> false
              | _ -> true)
        in
        (* never glue two minus signs: -- is not a token, but keep it legible *)
        let space = space || (match prev, t with Some (MINUS, _), MINUS -> true | _ -> false) in
        if space then Buffer.add_char b ' ';
        Buffer.add_string b (token_text t);
        (* the bookkeeping for what follows *)
        (match t with
         | QUESTION -> (match prev with Some ((DOT | LBRACKET), _) -> () | _ -> incr conds)
         | COLON when ternary_colon -> decr conds
         | IDENT "match" -> matching := true
         | _ -> ());
        (match t with
         | LPAREN | LBRACKET | LBRACE ->
             if match_brace then matching := false;
             stack := (ref 0, ref false) :: !stack
         | RPAREN | RBRACKET | RBRACE -> (match !stack with _ :: (_ :: _ as rest) -> stack := rest | _ -> ())
         | _ -> ());
        go (Some (t, Option.map fst prev)) rest
  in
  go None tokens;
  Buffer.contents b

let canonical src =
  match tokenize src with
  | toks -> Ok (print toks)
  | exception Lex_error m -> Error ("lex: " ^ m)
