(* CEL's regular expressions: RE2 syntax (the CEL spec's `matches`),
   compiled to ocaml-re over UTF-8. A string is matched by code point, as
   RE2 does: `.`, a class and a repeated literal each take one whole
   character, however many bytes it has, so a class or a range can name
   characters beyond ASCII. ocaml-re matches bytes, so every set of code
   points becomes an alternation of UTF-8 byte sequences.

   Supported: literals; escapes \a \f \t \n \r \v, \xHH, \x{H...}, octal
   \0NN and \1NN..\7NN, escaped punctuation, \Q...\E; `.`; classes with
   ranges, negation, \d \w \s (ASCII, as RE2's) and their negations, and
   [:name:] POSIX classes; groups (...), (?:...), (?P<name>...),
   (?<name>...); flags i, m, s (as (?flags) or (?flags:...), and cleared
   with -); repetition * + ? {n} {n,} {n,m}, greedy or lazy; anchors ^ $
   \A \z; \b \B.

   Refused, as RE2 refuses them: backreferences, lookaround, and \C;
   refused here though RE2 has them: Unicode classes (\pN, \p{Greek}),
   the U flag, and case folding of characters beyond ASCII (no tables).
   \b and \B are ocaml-re's word boundaries, whose word characters
   include Latin-1 letters: the same as RE2's on ASCII text. *)

exception Error of string

let error fmt = Printf.ksprintf (fun m -> raise (Error m)) fmt
let max_cp = 0x10FFFF

(* ---- sets of code points: sorted, disjoint, non-adjacent ranges ---------------------------------- *)

let normalize rs =
  let rs = List.sort compare (List.filter (fun (a, b) -> a <= b) rs) in
  let rec go = function
    | (a, b) :: (c, d) :: rest when c <= b + 1 -> go ((a, max b d) :: rest)
    | r :: rest -> r :: go rest
    | [] -> []
  in
  go rs

let complement rs =
  let rec go lo = function
    | (a, b) :: rest -> if a > lo then (lo, a - 1) :: go (b + 1) rest else go (b + 1) rest
    | [] -> if lo <= max_cp then [ (lo, max_cp) ] else []
  in
  go 0 (normalize rs)

(* no valid UTF-8 text holds a surrogate *)
let without_surrogates rs =
  normalize (List.concat_map (fun (a, b) -> if b < 0xD800 || a > 0xDFFF then [ (a, b) ] else List.filter (fun (x, y) -> x <= y) [ (a, 0xD7FF); (0xE000, b) ]) rs)

(* ASCII case folding: each ASCII letter with its other case *)
let fold rs =
  let rs = normalize rs in
  let extra =
    List.concat_map
      (fun (a, b) ->
        let part lo hi d = let x = max a lo and y = min b hi in if x <= y then [ (x + d, y + d) ] else [] in
        part 0x41 0x5A 0x20 @ part 0x61 0x7A (-0x20))
      rs
  in
  (* a set that names some letters beyond ASCII but not all of them would need Unicode's folding *)
  let beyond = List.filter_map (fun (a, b) -> if b >= 0x80 then Some (max a 0x80, b) else None) rs in
  if beyond <> [] && beyond <> [ (0x80, max_cp) ] && without_surrogates beyond <> without_surrogates [ (0x80, max_cp) ] then
    error "case folding beyond ASCII is not supported";
  normalize (rs @ extra)

(* ---- UTF-8 byte sequences --------------------------------------------------------------------------- *)

let encode cp =
  if cp < 0x80 then [ cp ]
  else if cp < 0x800 then [ 0xC0 lor (cp lsr 6); 0x80 lor (cp land 0x3F) ]
  else if cp < 0x10000 then [ 0xE0 lor (cp lsr 12); 0x80 lor ((cp lsr 6) land 0x3F); 0x80 lor (cp land 0x3F) ]
  else [ 0xF0 lor (cp lsr 18); 0x80 lor ((cp lsr 12) land 0x3F); 0x80 lor ((cp lsr 6) land 0x3F); 0x80 lor (cp land 0x3F) ]

(* the byte sequences of the code points lo..hi, each a list of byte ranges (the standard split: by
   encoded length, then until every continuation byte below the first that differs spans 80..BF) *)
let rec sequences lo hi =
  if lo > hi then []
  else
    match List.find_opt (fun b -> lo <= b && hi > b) [ 0x7F; 0x7FF; 0xFFFF ] with
    | Some b -> sequences lo b @ sequences (b + 1) hi
    | None ->
        if hi < 0x80 then [ [ (lo, hi) ] ]
        else
          let n = List.length (encode lo) in
          let rec split i =
            if i >= n then None
            else
              let m = (1 lsl (6 * i)) - 1 in
              if lo land lnot m <> hi land lnot m then
                if lo land m <> 0 then Some (sequences lo (lo lor m) @ sequences ((lo lor m) + 1) hi)
                else if hi land m <> m then Some (sequences lo ((hi land lnot m) - 1) @ sequences (hi land lnot m) hi)
                else split (i + 1)
              else split (i + 1)
          in
          match split 1 with Some s -> s | None -> [ List.combine (encode lo) (encode hi) ]

let byte_range (a, b) = Re.rg (Char.chr a) (Char.chr b)

let of_set rs =
  match without_surrogates rs with
  | [] -> Re.empty
  | rs -> Re.alt (List.concat_map (fun (a, b) -> List.map (fun s -> Re.seq (List.map byte_range s)) (sequences a b)) rs)

(* ---- the parser ------------------------------------------------------------------------------------- *)

type flags = { i : bool; m : bool; s : bool }

let digit = [ (0x30, 0x39) ]
let word = [ (0x30, 0x39); (0x41, 0x5A); (0x5F, 0x5F); (0x61, 0x7A) ]
let space = [ (0x09, 0x0A); (0x0C, 0x0D); (0x20, 0x20) ]

let posix = function
  | "alnum" -> [ (0x30, 0x39); (0x41, 0x5A); (0x61, 0x7A) ]
  | "alpha" -> [ (0x41, 0x5A); (0x61, 0x7A) ]
  | "ascii" -> [ (0, 0x7F) ]
  | "blank" -> [ (0x09, 0x09); (0x20, 0x20) ]
  | "cntrl" -> [ (0, 0x1F); (0x7F, 0x7F) ]
  | "digit" -> digit
  | "graph" -> [ (0x21, 0x7E) ]
  | "lower" -> [ (0x61, 0x7A) ]
  | "print" -> [ (0x20, 0x7E) ]
  | "punct" -> [ (0x21, 0x2F); (0x3A, 0x40); (0x5B, 0x60); (0x7B, 0x7E) ]
  | "space" -> [ (0x09, 0x0D); (0x20, 0x20) ]
  | "upper" -> [ (0x41, 0x5A) ]
  | "word" -> word
  | "xdigit" -> [ (0x30, 0x39); (0x41, 0x46); (0x61, 0x66) ]
  | n -> error "unknown class [:%s:]" n

(* the UTF-8 character of [s] at [i], and its length *)
let decode s i =
  let n = String.length s in
  let c = Char.code s.[i] in
  let len, init = if c < 0x80 then (1, c) else if c < 0xE0 then (2, c land 0x1F) else if c < 0xF0 then (3, c land 0x0F) else (4, c land 0x07) in
  if (c >= 0x80 && c < 0xC2) || c > 0xF4 || i + len > n then error "invalid UTF-8 in the pattern";
  let cp = ref init in
  for k = 1 to len - 1 do
    let b = Char.code s.[i + k] in
    if b land 0xC0 <> 0x80 then error "invalid UTF-8 in the pattern";
    cp := (!cp lsl 6) lor (b land 0x3F)
  done;
  (!cp, len)

let compile (p : string) : Re.t =
  let n = String.length p and pos = ref 0 in
  let peek () = if !pos < n then Some p.[!pos] else None in
  let at s = !pos + String.length s <= n && String.sub p !pos (String.length s) = s in
  let adv k = pos := !pos + k in
  (* one UTF-8 character of the pattern *)
  let char_ () = let cp, len = decode p !pos in adv len; cp in
  let hex s = match int_of_string_opt ("0x" ^ s) with Some v when s <> "" && v <= max_cp -> v | _ -> error "invalid escape \\x%s" s in
  (* after a backslash: a single character, or a class *)
  let escape ~in_class : [ `Cp of int | `Set of (int * int) list ] =
    match peek () with
    | None -> error "trailing backslash"
    | Some c -> (
        adv 1;
        match c with
        | 'a' -> `Cp 7 | 'f' -> `Cp 12 | 't' -> `Cp 9 | 'n' -> `Cp 10 | 'r' -> `Cp 13 | 'v' -> `Cp 11
        | 'x' ->
            if peek () = Some '{' then (
              match String.index_from_opt p !pos '}' with
              | Some j -> let h = String.sub p (!pos + 1) (j - !pos - 1) in pos := j + 1; `Cp (hex h)
              | None -> error "unterminated \\x{")
            else if !pos + 2 <= n then (let h = String.sub p !pos 2 in adv 2; `Cp (hex h))
            else error "invalid escape \\x"
        | '0' .. '7' ->
            (* octal: \0, or a digit 1-7 followed by more (a single \1..\7 would be a backreference) *)
            let start = !pos - 1 in
            while !pos < n && !pos - start < 3 && p.[!pos] >= '0' && p.[!pos] <= '7' do adv 1 done;
            if c <> '0' && !pos - start = 1 then error "backreferences are not supported";
            `Cp (int_of_string ("0o" ^ String.sub p start (!pos - start)))
        | '8' | '9' -> error "backreferences are not supported"
        | 'd' -> `Set digit | 'D' -> `Set (complement digit)
        | 'w' -> `Set word | 'W' -> `Set (complement word)
        | 's' -> `Set space | 'S' -> `Set (complement space)
        | 'p' | 'P' -> error "Unicode classes are not supported"
        | 'C' -> error "\\C is not supported"
        | ('b' | 'B' | 'A' | 'z' | 'Q' | 'E') when in_class -> error "\\%c in a class" c
        | c when Char.code c < 0x80 && not ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) -> `Cp (Char.code c)
        | c -> error "invalid escape \\%c" c)
  in
  let set fl rs = of_set (if fl.i then fold rs else rs) in
  (* [...]: the opening bracket is consumed *)
  let cls fl =
    let neg = peek () = Some '^' in
    if neg then adv 1;
    let items = ref [] and first = ref true in
    let one () =
      if at "[:" then (
        match String.index_from_opt p (!pos + 2) ':' with
        | Some j when j + 1 < n && p.[j + 1] = ']' ->
            let name = String.sub p (!pos + 2) (j - !pos - 2) in
            pos := j + 2;
            let neg, name = if String.length name > 0 && name.[0] = '^' then (true, String.sub name 1 (String.length name - 1)) else (false, name) in
            `Set (if neg then complement (posix name) else posix name)
        | _ -> adv 1; `Cp (Char.code '['))
      else if peek () = Some '\\' then (adv 1; escape ~in_class:true)
      else `Cp (char_ ())
    in
    let rec loop () =
      match peek () with
      | None -> error "missing ]"
      | Some ']' when not !first -> adv 1
      | Some _ ->
          first := false;
          (match one () with
           | `Set s -> items := s @ !items
           | `Cp a ->
               if peek () = Some '-' && !pos + 1 < n && p.[!pos + 1] <> ']' then (
                 adv 1;
                 match one () with
                 | `Cp b -> if b < a then error "invalid range" else items := (a, b) :: !items
                 | `Set _ -> error "invalid range")
               else items := (a, a) :: !items);
          loop ()
    in
    loop ();
    let rs = if fl.i then fold !items else normalize !items in
    of_set (if neg then complement rs else rs)
  in
  let flags_of fl s =
    let on = ref true and fl = ref fl in
    String.iter
      (function
        | '-' -> on := false
        | 'i' -> fl := { !fl with i = !on } | 'm' -> fl := { !fl with m = !on } | 's' -> fl := { !fl with s = !on }
        | 'U' -> error "the U flag is not supported"
        | c -> error "unknown flag %c" c)
      s;
    !fl
  in
  (* {n}, {n,}, {n,m}: None if it is not one (a literal brace) *)
  let repeat () =
    match String.index_from_opt p !pos '}' with
    | None -> None
    | Some j -> (
        let body = String.sub p (!pos + 1) (j - !pos - 1) in
        let num s = if s <> "" && String.for_all (fun c -> c >= '0' && c <= '9') s then int_of_string_opt s else None in
        let r =
          match String.index_opt body ',' with
          | None -> Option.map (fun k -> (k, Some k)) (num body)
          | Some c -> (
              let lo = String.sub body 0 c and hi = String.sub body (c + 1) (String.length body - c - 1) in
              match num lo, hi with Some a, "" -> Some (a, None) | Some a, h -> Option.map (fun b -> (a, Some b)) (num h) | _ -> None)
        in
        match r with
        | Some (a, b) ->
            if a > 1000 || Option.value b ~default:0 > 1000 then error "repetition count over 1000";
            (match b with Some b when b < a -> error "invalid repetition" | _ -> ());
            pos := j + 1;
            Some (a, b)
        | None -> None)
  in
  (* a group's alternatives; a bare (?flags) changes the flags for the rest of the group *)
  let rec alt f =
    let branches = ref [] and fl = ref f in
    let rec go () =
      branches := seq fl :: !branches;
      if peek () = Some '|' then (adv 1; go ())
    in
    go ();
    match !branches with [ b ] -> b | bs -> Re.alt (List.rev bs)
  and seq fl =
    let items = ref [] in
    let push r = items := r :: !items in
    let quantify () =
      let q =
        match peek () with
        | Some '*' -> adv 1; Some (0, None)
        | Some '+' -> adv 1; Some (1, None)
        | Some '?' -> adv 1; Some (0, Some 1)
        | Some '{' -> repeat ()
        | _ -> None
      in
      match q with
      | None -> ()
      | Some (a, b) -> (
          let lazy_ = peek () = Some '?' in
          if lazy_ then adv 1;
          (match peek () with Some ('*' | '+' | '?') -> error "a repeated repetition" | _ -> ());
          match !items with
          | r :: rest ->
              let r = Re.repn r a b in
              items := (if lazy_ then Re.non_greedy r else r) :: rest
          | [] -> error "missing argument to repetition")
    in
    let rec go () =
      match peek () with
      | None | Some '|' | Some ')' -> ()
      | Some c ->
          (match c with
           | '(' ->
               adv 1;
               if at "?=" || at "?!" || at "?<=" || at "?<!" then error "lookaround is not supported";
               if at "?P<" || (at "?<" && not (at "?<=")) then (
                 match String.index_from_opt p !pos '>' with
                 | Some j -> pos := j + 1; let r = alt !fl in close (); push (Re.group r)
                 | None -> error "missing >")
               else if at "?" then (
                 adv 1;
                 let j = ref !pos in
                 while !j < n && p.[!j] <> ')' && p.[!j] <> ':' do incr j done;
                 if !j >= n then error "missing )";
                 let f = flags_of !fl (String.sub p !pos (!j - !pos)) in
                 pos := !j + 1;
                 if p.[!j] = ')' then fl := f
                 else (let r = alt f in close (); push (Re.no_group r)))
               else (let r = alt !fl in close (); push (Re.group r))
           | '[' -> adv 1; push (cls !fl)
           | '.' -> adv 1; push (of_set (if !fl.s then [ (0, max_cp) ] else complement [ (10, 10) ]))
           | '^' -> adv 1; push (if !fl.m then Re.bol else Re.bos)
           | '$' -> adv 1; push (if !fl.m then Re.eol else Re.eos)
           | '\\' when at "\\A" -> adv 2; push Re.bos
           | '\\' when at "\\z" -> adv 2; push Re.eos
           | '\\' when at "\\b" -> adv 2; push (Re.alt [ Re.bow; Re.eow ])
           | '\\' when at "\\B" -> adv 2; push Re.not_boundary
           | '\\' when at "\\Q" ->
               adv 2;
               let stop = let rec find i = if i + 1 >= n then n else if p.[i] = '\\' && p.[i + 1] = 'E' then i else find (i + 1) in find !pos in
               (* each character its own item, so a repetition after \E repeats the last *)
               while !pos < stop do let c = char_ () in push (set !fl [ (c, c) ]) done;
               pos := min n (stop + 2)
           | '\\' -> (
               adv 1;
               match escape ~in_class:false with `Cp c -> push (set !fl [ (c, c) ]) | `Set s -> push (set !fl s))
           | '*' | '+' | '?' -> error "missing argument to repetition"
           | '{' -> (
               match repeat () with
               | Some _ -> error "missing argument to repetition"
               | None -> adv 1; push (set !fl [ (Char.code '{', Char.code '{') ]))
           | _ -> let c = char_ () in push (set !fl [ (c, c) ]));
          quantify ();
          go ()
    in
    go ();
    Re.seq (List.rev !items)
  and close () = if peek () = Some ')' then adv 1 else error "missing )" in
  let r = alt { i = false; m = false; s = false } in
  if !pos < n then error "unexpected )";
  r
