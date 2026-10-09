(* The graph language's parser (docs/cordis/rfc/0002-graph-language.md,
   "Concrete syntax"). A hand-written scanner over the text: the graph
   syntax is read token by token, and each CEL expression is taken whole, up
   to its terminator at bracket depth 0 outside string literals and
   comments, then handed to the CEL parser. *)

open Syntax

exception Error of string

type st = { src : string; mutable pos : int }

let line_col st p =
  let line = ref 1 and col = ref 1 in
  for i = 0 to min p (String.length st.src) - 1 do
    if st.src.[i] = '\n' then (incr line; col := 1) else incr col
  done;
  (!line, !col)

let fail st fmt =
  Printf.ksprintf
    (fun m ->
      let l, c = line_col st st.pos in
      raise (Error (Printf.sprintf "%d:%d: %s" l c m)))
    fmt

let eof st = st.pos >= String.length st.src
let cur st = if eof st then '\000' else st.src.[st.pos]
let at st k = if st.pos + k >= String.length st.src then '\000' else st.src.[st.pos + k]

let rec skip st =
  if eof st then ()
  else
    match cur st with
    | ' ' | '\t' | '\n' | '\r' -> st.pos <- st.pos + 1; skip st
    | '/' when at st 1 = '/' ->
        while (not (eof st)) && cur st <> '\n' do st.pos <- st.pos + 1 done;
        skip st
    | _ -> ()

let is_ident_start = function 'a' .. 'z' | 'A' .. 'Z' | '_' -> true | _ -> false
let is_ident_char = function 'a' .. 'z' | 'A' .. 'Z' | '_' | '0' .. '9' -> true | _ -> false

let looking st s =
  skip st;
  let n = String.length s in
  st.pos + n <= String.length st.src && String.sub st.src st.pos n = s

(* a keyword: the word, not a prefix of a longer identifier *)
let looking_word st w =
  looking st w && not (is_ident_char (at st (String.length w)))

let expect st s =
  if looking st s then st.pos <- st.pos + String.length s else fail st "expected '%s'" s

let expect_word st w = if looking_word st w then st.pos <- st.pos + String.length w else fail st "expected '%s'" w

let accept st s = if looking st s then (st.pos <- st.pos + String.length s; true) else false
let accept_word st w = if looking_word st w then (st.pos <- st.pos + String.length w; true) else false

let ident st =
  skip st;
  if not (is_ident_start (cur st)) then fail st "expected a name";
  let start = st.pos in
  while is_ident_char (cur st) do st.pos <- st.pos + 1 done;
  String.sub st.src start (st.pos - start)

(* a.b.c, for effect kinds *)
let dotted st =
  let first = ident st in
  let rec go acc = if cur st = '.' && is_ident_start (at st 1) then (st.pos <- st.pos + 1; go (acc ^ "." ^ ident st)) else acc in
  go first

let int_lit st =
  skip st;
  let start = st.pos in
  while match cur st with '0' .. '9' -> true | _ -> false do st.pos <- st.pos + 1 done;
  if st.pos = start then fail st "expected a number";
  int_of_string (String.sub st.src start (st.pos - start))

(* ---- CEL spans ----------------------------------------------------------------------------- *)

(* The raw text of a CEL expression, from here to the first terminator at
   depth 0 outside strings and comments: one of [chars], or the word
   [with] when [stop_with]. *)
let cel_span st ~chars ~stop_with =
  skip st;
  let start = st.pos and depth = ref 0 in
  (* the word itself, not part of a longer name or a field (x.with) *)
  let looking_word_here w =
    let n = String.length w in
    st.pos + n <= String.length st.src
    && String.sub st.src st.pos n = w
    && (not (is_ident_char (at st n)))
    && (st.pos = 0 || not (is_ident_char st.src.[st.pos - 1]))
    && (st.pos = 0 || st.src.[st.pos - 1] <> '.')
  in
  let stop () = !depth = 0 && (List.mem (cur st) chars || (stop_with && looking_word_here "with")) in
  while not (eof st || stop ()) do
    match cur st with
    | '(' | '[' | '{' -> incr depth; st.pos <- st.pos + 1
    | ')' | ']' | '}' ->
        if !depth = 0 then fail st "unbalanced '%c'" (cur st);
        decr depth;
        st.pos <- st.pos + 1
    | '/' when at st 1 = '/' -> while (not (eof st)) && cur st <> '\n' do st.pos <- st.pos + 1 done
    | ('"' | '\'') as q ->
        (* r'..' leaves backslashes alone *)
        let raw =
          let j = ref (st.pos - 1) and r = ref false in
          while !j >= 0 && (match st.src.[!j] with 'r' | 'R' | 'b' | 'B' -> true | _ -> false) do
            if st.src.[!j] = 'r' || st.src.[!j] = 'R' then r := true;
            decr j
          done;
          !r
        in
        let triple = at st 1 = q && at st 2 = q in
        let close = if triple then String.make 3 q else String.make 1 q in
        st.pos <- st.pos + String.length close;
        let fin = ref false in
        while not !fin do
          if eof st then fail st "unterminated string literal";
          if (not raw) && cur st = '\\' then st.pos <- st.pos + 2
          else if st.pos + String.length close <= String.length st.src
                  && String.sub st.src st.pos (String.length close) = close then (
            st.pos <- st.pos + String.length close;
            fin := true)
          else st.pos <- st.pos + 1
        done
    | _ -> st.pos <- st.pos + 1
  done;
  if eof st && chars <> [] then fail st "expected one of %s" (String.concat " " (List.map (String.make 1) chars));
  (start, String.trim (String.sub st.src start (st.pos - start)))

let cel st ~chars ?(stop_with = false) () =
  let start, text = cel_span st ~chars ~stop_with in
  if text = "" then (st.pos <- start; fail st "expected an expression");
  (* a hole is the two tokens ?name, whatever the spacing and comments *)
  let hole =
    match Cordis_cel.Lexer.tokenize text with
    | [ Cordis_cel.Lexer.QUESTION; Cordis_cel.Lexer.IDENT h; Cordis_cel.Lexer.EOF ] -> Some h
    | _ | (exception Cordis_cel.Lexer.Lex_error _) -> None
  in
  match hole with
  | Some h -> Hole h
  | None ->
    let here () = st.pos <- start in
    match Cordis_cel.Cel.parse text with
    | Error m -> here (); fail st "%s" m
    | Ok ast -> (
        match Celtext.canonical text with
        | Ok canon -> Cel { text = canon; ast }
        | Error m -> here (); fail st "%s" m)

(* ---- types --------------------------------------------------------------------------------- *)

let scalars = [ "int"; "uint"; "double"; "string"; "bytes"; "bool"; "timestamp"; "duration"; "dyn" ]

let bound st =
  if looking st "<" then begin
    expect st "<";
    expect_word st "max";
    let n = int_lit st in
    expect st ">";
    Some n
  end
  else None

let rec ty st =
  skip st;
  if accept st "'" then Var (ident st)
  else if looking st "{" then begin
    expect st "{";
    let rec fields acc =
      if accept st "}" then (List.rev acc, false)
      else if accept st ".." then (expect st "}"; (List.rev acc, true))
      else begin
        let f = ident st in
        expect st ":";
        let t = ty st in
        if not (accept st ";") then (if not (looking st "}") then fail st "expected ';' or '}' in a record type");
        fields ((f, t) :: acc)
      end
    in
    let fs, open_ = fields [] in
    let names = List.map fst fs in
    List.iter (fun n -> if List.length (List.filter (( = ) n) names) > 1 then fail st "duplicate field '%s'" n) names;
    Record (fs, open_)
  end
  else
    let n = ident st in
    match n with
    | "list" ->
        expect st "(";
        let t = ty st in
        expect st ")";
        List (t, bound st)
    | "map" ->
        expect st "(";
        let k = ty st in
        expect st ",";
        let v = ty st in
        expect st ")";
        Map (k, v, bound st)
    | "option" ->
        expect st "(";
        let t = ty st in
        expect st ")";
        Option t
    | n when List.mem n scalars -> Scalar (n, bound st)
    | n -> Named n

(* ---- cells --------------------------------------------------------------------------------- *)

let params st =
  expect st "(";
  if accept st ")" then []
  else
    let rec go acc =
      let p = ident st in
      expect st ":";
      let t = ty st in
      if accept st "," then go ((p, t) :: acc) else (expect st ")"; List.rev ((p, t) :: acc))
    in
    go []

let cell st =
  let name = ident st in
  let params = params st in
  expect st ":";
  let result = ty st in
  let effects =
    if accept st "!" then begin
      expect st "{";
      if accept st "}" then []
      else
        let rec go acc = let k = dotted st in if accept st "," then go (k :: acc) else (expect st "}"; List.rev (k :: acc)) in
        go []
    end
    else []
  in
  let state =
    if accept_word st "state" then begin
      expect st "{";
      let rec go acc =
        if accept st "}" then List.rev acc
        else begin
          let f = ident st in
          expect st ":";
          let t = ty st in
          expect st "=";
          let init = cel st ~chars:[ ';' ] () in
          expect st ";";
          go ((f, t, init) :: acc)
        end
      in
      go []
    end
    else []
  in
  expect st "=";
  expect st "{";
  let rec body acc =
    if accept_word st "let" then begin
      let x = ident st in
      expect st "=";
      let e = cel st ~chars:[ ';' ] () in
      expect st ";";
      body (Let (x, e) :: acc)
    end
    else if accept_word st "use" then begin
      let x = ident st in
      expect st "=";
      let k = dotted st in
      expect st "(";
      let e = cel st ~chars:[ ')' ] () in
      expect st ")";
      expect st ";";
      body (Use (x, k, e) :: acc)
    end
    else begin
      let final = cel st ~chars:[ ';' ] ~stop_with:true () in
      let updates =
        if accept_word st "with" then
          let rec go acc =
            expect_word st "state";
            expect st ".";
            let f = ident st in
            expect st ":=";
            let e = cel st ~chars:[ ';'; ',' ] () in
            if accept st "," then go ((f, e) :: acc) else List.rev ((f, e) :: acc)
          in
          go []
        else []
      in
      expect st ";";
      expect st "}";
      (List.rev acc, final, updates)
    end
  in
  let body, final, updates = body [] in
  { name; params; result; effects; state; body; final; updates }

(* ---- graphs -------------------------------------------------------------------------------- *)

let duration st =
  skip st;
  let start = st.pos in
  while is_ident_char (cur st) || cur st = '.' do st.pos <- st.pos + 1 done;
  let d = String.sub st.src start (st.pos - start) in
  if d = "" then fail st "expected a duration";
  d

let pattern st =
  if accept_word st "_" then Wild
  else
    let tag = ident st in
    if accept st "(" then begin
      let b = if accept_word st "_" then None else Some (ident st) in
      expect st ")";
      Case (tag, match b with None -> Some "_" | some -> some)
    end
    else Case (tag, None)

let graph st =
  let name = ident st in
  expect st "{";
  let rec items acc =
    if accept st "}" then List.rev acc
    else if accept_word st "input" then begin
      let n = ident st in
      expect st ":";
      let t = ty st in
      expect st ";";
      items (Input (n, t) :: acc)
    end
    else if accept_word st "stream" then begin
      let n = ident st in
      expect st ":";
      let t = ty st in
      expect st ";";
      items (Stream (n, t) :: acc)
    end
    else if accept_word st "asset" then begin
      let n = ident st in
      expect st "=";
      let c = ident st in
      expect st "(";
      let args =
        if accept st ")" then []
        else
          let rec go acc = let a = ident st in if accept st "," then go (a :: acc) else (expect st ")"; List.rev (a :: acc)) in
          go []
      in
      let fresh =
        if accept st "~fresh:" then
          if accept_word st "on_change" then Some On_change
          else if accept_word st "always" then Some Always
          else if accept_word st "every" then Some (Every (duration st))
          else fail st "expected on_change, always or every"
        else None
      in
      expect st ";";
      items (Asset { name = n; cell = c; args; fresh } :: acc)
    end
    else if accept_word st "route" then begin
      let a = ident st in
      expect st "{";
      let rec cases acc =
        if accept st "}" then List.rev acc
        else begin
          let p = pattern st in
          expect st "->";
          let rec actions acc =
            let act =
              if accept_word st "none" then Nothing
              else
                let target = ident st in
                if accept st "<~" then Append (target, cel st ~chars:[ ';'; ','; '}' ] ()) else Enable target
            in
            if accept st "," then actions (act :: acc) else List.rev (act :: acc)
          in
          let acts = actions [] in
          ignore (accept st ";");
          cases ((p, acts) :: acc)
        end
      in
      let cs = cases [] in
      ignore (accept st ";");
      items (Route (a, cs) :: acc)
    end
    else if accept_word st "invariant" then begin
      if accept_word st "always" then (let e = cel st ~chars:[ ';' ] () in expect st ";"; items (Always_inv e :: acc))
      else if accept_word st "sometimes" then (let e = cel st ~chars:[ ';' ] () in expect st ";"; items (Sometimes_inv e :: acc))
      else begin
        let a = ident st in
        expect_word st "before";
        let b = ident st in
        expect st ";";
        items (Before (a, b) :: acc)
      end
    end
    else fail st "expected input, stream, asset, route, invariant or '}'"
  in
  Graph (name, items [])

(* ---- files ---------------------------------------------------------------------------------- *)

let hash st =
  skip st;
  expect st "sha256:";
  let start = st.pos in
  while match cur st with '0' .. '9' | 'a' .. 'f' -> true | _ -> false do st.pos <- st.pos + 1 done;
  if st.pos - start <> 64 then fail st "expected 64 hex digits after sha256:";
  "sha256:" ^ String.sub st.src start 64

let item st =
  if accept_word st "uses" then begin
    let a = ident st in
    expect st "=";
    let h = hash st in
    expect st ";";
    Uses (a, h)
  end
  else if accept_word st "type" then begin
    let n = ident st in
    expect st "=";
    (* a variant: Case | Case(T) | ...; anything else is a type *)
    let save = st.pos in
    let variant =
      skip st;
      if is_ident_start (cur st) && (match cur st with 'A' .. 'Z' -> true | _ -> false) then begin
        let rec cases acc =
          let c = ident st in
          let payload = if accept st "(" then (let t = ty st in expect st ")"; Some t) else None in
          let acc = (c, payload) :: acc in
          if accept st "|" then cases acc else List.rev acc
        in
        let cs = cases [] in
        if List.length cs > 1 || (looking st ";" && (match cs with [ (_, Some _) ] -> true | _ -> false)) then Some cs
        else None
      end
      else None
    in
    let def = match variant with Some cs -> Variant cs | None -> (st.pos <- save; Alias (ty st)) in
    (match def with
     | Variant cs ->
         let names = List.map fst cs in
         List.iter (fun c -> if List.length (List.filter (( = ) c) names) > 1 then fail st "duplicate case '%s'" c) names
     | Alias _ -> ());
    expect st ";";
    Type_def (n, def)
  end
  else if accept_word st "effect" then begin
    let k = dotted st in
    expect st "(";
    let req = ty st in
    expect st ")";
    expect st ":";
    let rcpt = ty st in
    expect st ";";
    Effect (k, req, rcpt)
  end
  else if accept_word st "cell" then Cell (cell st)
  else if accept_word st "graph" then graph st
  else fail st "expected uses, type, effect, cell or graph"

let file src =
  Cordis_cel.Extensions.load ();
  let st = { src; pos = 0 } in
  match
    let rec go acc = skip st; if eof st then List.rev acc else go (item st :: acc) in
    go []
  with
  | items -> Ok items
  | exception Error m -> Error m
