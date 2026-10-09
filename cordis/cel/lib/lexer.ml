(* The CEL lexer (cel-spec doc/langdef.md, "Lexical structure"). *)

type token =
  | INT of string  (** digits, possibly 0x-prefixed, without sign *)
  | UINT of string
  | DOUBLE of string
  | STRING of string  (** decoded, UTF-8 *)
  | BYTES of string
  | IDENT of string
  | QUOTED_IDENT of string  (** `a.b-c` field names *)
  | TRUE | FALSE | NULL | IN
  | LPAREN | RPAREN | LBRACKET | RBRACKET | LBRACE | RBRACE
  | DOT | COMMA | COLON | QUESTION
  | NOT | MINUS | PLUS | STAR | SLASH | PERCENT
  | EQ | NE | LT | LE | GT | GE | AND | OR
  | EOF

exception Lex_error of string

let reserved =
  [ "as"; "break"; "const"; "continue"; "else"; "for"; "function"; "if"; "import"; "let";
    "loop"; "package"; "namespace"; "return"; "var"; "void"; "while" ]

let is_digit c = c >= '0' && c <= '9'
let is_hex c = is_digit c || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
let is_ident_start c = c = '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
let is_ident c = is_ident_start c || is_digit c
let hex_val c =
  if is_digit c then Char.code c - 48
  else if c >= 'a' && c <= 'f' then Char.code c - 87
  else Char.code c - 55

let add_utf8 buf cp =
  if cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) then
    raise (Lex_error "invalid code point in escape");
  Buffer.add_utf_8_uchar buf (Uchar.of_int cp)

(* Decode the body of a string or bytes literal. [raw] disables escapes. *)
let decode ~bytes ~raw s =
  if raw then s
  else
    let buf = Buffer.create (String.length s) in
    let n = String.length s in
    let i = ref 0 in
    while !i < n do
      let c = s.[!i] in
      if c <> '\\' then (Buffer.add_char buf c; incr i)
      else begin
        if !i + 1 >= n then raise (Lex_error "trailing backslash");
        let e = s.[!i + 1] in
        i := !i + 2;
        let hex k =
          if !i + k > n then raise (Lex_error "short escape");
          let v = ref 0 in
          for j = 0 to k - 1 do
            let h = s.[!i + j] in
            if not (is_hex h) then raise (Lex_error "bad hex escape");
            v := (!v * 16) + hex_val h
          done;
          i := !i + k;
          !v
        in
        match e with
        | 'a' -> Buffer.add_char buf '\007'
        | 'b' -> Buffer.add_char buf '\b'
        | 'f' -> Buffer.add_char buf '\012'
        | 'n' -> Buffer.add_char buf '\n'
        | 'r' -> Buffer.add_char buf '\r'
        | 't' -> Buffer.add_char buf '\t'
        | 'v' -> Buffer.add_char buf '\011'
        | '\\' | '\'' | '"' | '`' | '?' -> Buffer.add_char buf e
        | 'x' | 'X' ->
            let v = hex 2 in
            if bytes then Buffer.add_char buf (Char.chr v) else add_utf8 buf v
        | 'u' ->
            if bytes then raise (Lex_error "\\u escape in bytes literal");
            add_utf8 buf (hex 4)
        | 'U' ->
            if bytes then raise (Lex_error "\\U escape in bytes literal");
            add_utf8 buf (hex 8)
        | '0' .. '3' ->
            if !i + 2 > n then raise (Lex_error "short octal escape");
            let d1 = s.[!i] and d2 = s.[!i + 1] in
            if not (d1 >= '0' && d1 <= '7' && d2 >= '0' && d2 <= '7') then
              raise (Lex_error "bad octal escape");
            let v = ((Char.code e - 48) * 64) + ((Char.code d1 - 48) * 8) + (Char.code d2 - 48) in
            i := !i + 2;
            if bytes then Buffer.add_char buf (Char.chr v) else add_utf8 buf v
        | _ -> raise (Lex_error (Printf.sprintf "invalid escape \\%c" e))
      end
    done;
    let r = Buffer.contents buf in
    if (not bytes) && not (String.is_valid_utf_8 r) then raise (Lex_error "invalid UTF-8 in string");
    r

let tokenize (src : string) : token list =
  let n = String.length src in
  let toks = ref [] in
  let push t = toks := t :: !toks in
  let i = ref 0 in
  let peek k = if !i + k < n then src.[!i + k] else '\000' in
  let starts_with s = !i + String.length s <= n && String.sub src !i (String.length s) = s in
  (* read a quoted literal starting at the opening quote; returns its body *)
  let read_quoted () =
    let q = src.[!i] in
    let triple = !i + 2 < n && src.[!i + 1] = q && src.[!i + 2] = q in
    let close = if triple then String.make 3 q else String.make 1 q in
    i := !i + String.length close;
    let start = !i in
    let rec find () =
      if !i >= n then raise (Lex_error "unterminated string")
      else if starts_with close then ()
      else if src.[!i] = '\\' then (i := !i + 2; find ())
      else if (not triple) && (src.[!i] = '\n' || src.[!i] = '\r') then
        raise (Lex_error "newline in string")
      else (incr i; find ())
    in
    find ();
    let body = String.sub src start (!i - start) in
    i := !i + String.length close;
    body
  in
  while !i < n do
    let c = src.[!i] in
    if c = ' ' || c = '\t' || c = '\n' || c = '\r' || c = '\012' then incr i
    else if c = '/' && peek 1 = '/' then begin
      while !i < n && src.[!i] <> '\n' do incr i done
    end
    else if is_digit c || (c = '.' && is_digit (peek 1)) then begin
      let start = !i in
      if c = '0' && (peek 1 = 'x' || peek 1 = 'X') then begin
        i := !i + 2;
        let hs = !i in
        while !i < n && is_hex src.[!i] do incr i done;
        if !i = hs then raise (Lex_error "bad hex literal");
        let digits = String.sub src start (!i - start) in
        if !i < n && (src.[!i] = 'u' || src.[!i] = 'U') then (incr i; push (UINT digits))
        else push (INT digits)
      end
      else begin
        while !i < n && is_digit src.[!i] do incr i done;
        let is_double = ref false in
        if !i < n && src.[!i] = '.' && is_digit (peek 1) then begin
          is_double := true;
          incr i;
          while !i < n && is_digit src.[!i] do incr i done
        end;
        if !i < n && (src.[!i] = 'e' || src.[!i] = 'E') then begin
          let save = !i in
          incr i;
          if !i < n && (src.[!i] = '+' || src.[!i] = '-') then incr i;
          if !i < n && is_digit src.[!i] then begin
            is_double := true;
            while !i < n && is_digit src.[!i] do incr i done
          end
          else i := save
        end;
        let text = String.sub src start (!i - start) in
        if !is_double then push (DOUBLE text)
        else if !i < n && (src.[!i] = 'u' || src.[!i] = 'U') then (incr i; push (UINT text))
        else push (INT text)
      end
    end
    else if c = '"' || c = '\'' then push (STRING (decode ~bytes:false ~raw:false (read_quoted ())))
    else if (c = 'r' || c = 'R' || c = 'b' || c = 'B') && (peek 1 = '"' || peek 1 = '\''
             || ((peek 1 = 'r' || peek 1 = 'R' || peek 1 = 'b' || peek 1 = 'B') && (peek 2 = '"' || peek 2 = '\'')))
    then begin
      let raw = ref false and bytes = ref false in
      while src.[!i] <> '"' && src.[!i] <> '\'' do
        (match src.[!i] with 'r' | 'R' -> raw := true | _ -> bytes := true);
        incr i
      done;
      let body = read_quoted () in
      let v = decode ~bytes:!bytes ~raw:!raw body in
      push (if !bytes then BYTES v else STRING v)
    end
    else if is_ident_start c then begin
      let start = !i in
      while !i < n && is_ident src.[!i] do incr i done;
      let id = String.sub src start (!i - start) in
      match id with
      | "true" -> push TRUE
      | "false" -> push FALSE
      | "null" -> push NULL
      | "in" -> push IN
      | _ -> push (IDENT id)
    end
    else if c = '`' then begin
      incr i;
      let start = !i in
      while !i < n && src.[!i] <> '`' do incr i done;
      if !i >= n then raise (Lex_error "unterminated quoted identifier");
      let id = String.sub src start (!i - start) in
      incr i;
      push (QUOTED_IDENT id)
    end
    else begin
      let two = if !i + 1 < n then String.sub src !i 2 else "" in
      let tok2 =
        match two with
        | "==" -> Some EQ | "!=" -> Some NE | "<=" -> Some LE | ">=" -> Some GE
        | "&&" -> Some AND | "||" -> Some OR | _ -> None
      in
      match tok2 with
      | Some t -> push t; i := !i + 2
      | None ->
          let t =
            match c with
            | '(' -> LPAREN | ')' -> RPAREN | '[' -> LBRACKET | ']' -> RBRACKET
            | '{' -> LBRACE | '}' -> RBRACE | '.' -> DOT | ',' -> COMMA | ':' -> COLON
            | '?' -> QUESTION | '!' -> NOT | '-' -> MINUS | '+' -> PLUS | '*' -> STAR
            | '/' -> SLASH | '%' -> PERCENT | '<' -> LT | '>' -> GT
            | _ -> raise (Lex_error (Printf.sprintf "unexpected character %C" c))
          in
          push t;
          incr i
    end
  done;
  List.rev (EOF :: !toks)
