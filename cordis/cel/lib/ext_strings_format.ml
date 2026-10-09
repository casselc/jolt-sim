(* string.format from cel-go's strings extension (version 4 and later, the
   cel-spec strings.md formatting; ext/formatting_v2.go).

   Clauses: %s %d %f %e %b %x %X %o, an optional precision ".N" (at most
   100) for %f and %e, and %% for a literal percent. The error texts are the
   ones cel-spec's conformance file (string_ext) states, which name the
   accepted types in words ("integers", "doubles") where cel-go's v2 texts
   name the CEL types. *)

open Value

let default_precision = 6
let max_precision = 100

exception Format_error of string

let fail fmt = Printf.ksprintf (fun s -> raise (Format_error s)) fmt

(* ---- numbers --------------------------------------------------------------------- *)

(* The shortest decimal digits that read back as [d] (|d| > 0 or zero), and
   the decimal exponent of the first digit: 1052.5 is ("10525", 3). *)
let shortest_digits d =
  let rec go p =
    let s = Printf.sprintf "%.*e" p (Float.abs d) in
    if p >= 16 || float_of_string s = Float.abs d then s else go (p + 1)
  in
  let s = go 0 in
  let e = String.index s 'e' in
  let mantissa = String.sub s 0 e in
  let digits = String.concat "" (String.split_on_char '.' mantissa) in
  (digits, int_of_string (String.sub s (e + 1) (String.length s - e - 1)))

(* Go's strconv.FormatFloat(d, 'f', -1, 64): the shortest round-tripping
   digits laid out without an exponent (1e21 is "1000000000000000000000"). *)
let shortest_fixed d =
  let digits, e = shortest_digits d in
  let n = String.length digits in
  let body =
    if e >= n - 1 then digits ^ String.make (e - n + 1) '0'
    else if e >= 0 then String.sub digits 0 (e + 1) ^ "." ^ String.sub digits (e + 1) (n - e - 1)
    else "0." ^ String.make (-e - 1) '0' ^ digits
  in
  if Float.sign_bit d then "-" ^ body else body

(* NaN and the infinities are spelled out by every numeric clause. *)
let special d =
  if Float.is_nan d then Some "NaN"
  else if d = Float.infinity then Some "Infinity"
  else if d = Float.neg_infinity then Some "-Infinity"
  else None

let format_double d = match special d with Some s -> s | None -> shortest_fixed d

(* Digits of an unsigned 64-bit value in base 2, 8 or 16. *)
let unsigned_radix ~upper base u =
  let digits = if upper then "0123456789ABCDEF" else "0123456789abcdef" in
  let b = Int64.of_int base in
  let rec go u acc =
    let acc = String.make 1 digits.[Int64.to_int (Int64.unsigned_rem u b)] ^ acc in
    let q = Int64.unsigned_div u b in
    if q = 0L then acc else go q acc
  in
  go u ""

(* A signed int in base 2, 8 or 16 as Go prints it: a minus sign, then the
   magnitude (min_int's magnitude is its own unsigned bit pattern). *)
let signed_radix ~upper base i =
  if Int64.compare i 0L >= 0 then unsigned_radix ~upper base i
  else "-" ^ unsigned_radix ~upper base (Int64.neg i)

let hex_bytes ~upper s =
  String.concat "" (List.init (String.length s) (fun k ->
    Printf.sprintf (if upper then "%02X" else "%02x") (Char.code s.[k])))

(* ---- timestamps and durations (as %s prints them) ------------------------------- *)

(* The civil date of a day count from 1970-01-01 (Howard Hinnant's
   days_from_civil, inverted). *)
let civil_of_days z =
  let z = z + 719468 in
  let era = (if z >= 0 then z else z - 146096) / 146097 in
  let doe = z - (era * 146097) in
  let yoe = (doe - (doe / 1460) + (doe / 36524) - (doe / 146096)) / 365 in
  let doy = doe - ((365 * yoe) + (yoe / 4) - (yoe / 100)) in
  let mp = ((5 * doy) + 2) / 153 in
  let d = doy - (((153 * mp) + 2) / 5) + 1 in
  let m = if mp < 10 then mp + 3 else mp - 9 in
  ((yoe + (era * 400)) + (if m <= 2 then 1 else 0), m, d)

(* RFC 3339 in UTC with the fraction's trailing zeros dropped, as Go's
   time.RFC3339Nano layout prints it. *)
let format_timestamp secs nanos =
  let secs = Int64.to_int secs in
  let days = (if secs >= 0 then secs else secs - 86399) / 86400 in
  let rem = secs - (days * 86400) in
  let y, mo, d = civil_of_days days in
  let frac =
    if nanos = 0 then ""
    else
      let f = Printf.sprintf "%09d" nanos in
      let n = ref 9 in
      while f.[!n - 1] = '0' do decr n done;
      "." ^ String.sub f 0 !n
  in
  Printf.sprintf "%04d-%02d-%02dT%02d:%02d:%02d%sZ" y mo d (rem / 3600) (rem mod 3600 / 60) (rem mod 60) frac

(* Go's Duration.Seconds() printed shortest, then "s". *)
let format_duration secs nanos =
  shortest_fixed (Int64.to_float secs +. (float_of_int nanos /. 1e9)) ^ "s"

(* ---- the clauses ------------------------------------------------------------------ *)

let string_clause_error v =
  fail
    "string clause can only be used on strings, bools, bytes, ints, doubles, maps, lists, types, durations, and timestamps, was given %s"
    (type_name (type_of v))

(* %s: any value cel-go can print; lists and maps recursively, map entries
   sorted by their printed keys. *)
let rec as_string v =
  match v with
  | Null -> "null"
  | Bool b -> string_of_bool b
  | Int i -> Int64.to_string i
  | Uint u -> Printf.sprintf "%Lu" u
  | Double d -> format_double d
  | String s | Bytes s -> s
  | Type t -> type_name t
  | Timestamp (s, n) -> format_timestamp s n
  | Duration (s, n) -> format_duration s n
  | List xs -> "[" ^ String.concat ", " (Array.to_list (Array.map as_string xs)) ^ "]"
  | Map m ->
      let entries = List.map (fun (k, v) -> (as_string k, as_string v)) m in
      let entries = List.stable_sort (fun (a, _) (b, _) -> String.compare a b) entries in
      "{" ^ String.concat ", " (List.map (fun (k, v) -> k ^ ": " ^ v) entries) ^ "}"
  | _ -> string_clause_error v

let decimal = function
  | Int i -> Int64.to_string i
  | Uint u -> Printf.sprintf "%Lu" u
  | Double d -> format_double d
  | v -> fail "decimal clause can only be used on integers, was given %s" (type_name (type_of v))

(* %f and %e take ints and uints too, converted to double. *)
let as_float = function
  | Int i -> Some (Int64.to_float i)
  | Uint u -> Some (uint_to_float u)
  | Double d -> Some d
  | _ -> None

let fixed precision v =
  match as_float v with
  | Some d -> (match special d with Some s -> s | None -> Printf.sprintf "%.*f" precision d)
  | None -> fail "fixed-point clause can only be used on doubles, was given %s" (type_name (type_of v))

let scientific precision v =
  match as_float v with
  | Some d -> (match special d with Some s -> s | None -> Printf.sprintf "%.*e" precision d)
  | None -> fail "scientific clause can only be used on doubles, was given %s" (type_name (type_of v))

let binary = function
  | Bool b -> if b then "1" else "0"
  | Int i -> signed_radix ~upper:false 2 i
  | Uint u -> unsigned_radix ~upper:false 2 u
  | v -> fail "only integers and bools can be formatted as binary, was given %s" (type_name (type_of v))

let hex ~upper = function
  | Int i -> signed_radix ~upper 16 i
  | Uint u -> unsigned_radix ~upper 16 u
  | String s | Bytes s -> hex_bytes ~upper s
  | v ->
      fail "only integers, byte buffers, and strings can be formatted as hex, was given %s"
        (type_name (type_of v))

let octal = function
  | Int i -> signed_radix ~upper:false 8 i
  | Uint u -> unsigned_radix ~upper:false 8 u
  | v -> fail "octal clause can only be used on integers, was given %s" (type_name (type_of v))

(* ---- parsing the format string ----------------------------------------------------- *)

let is_digit c = c >= '0' && c <= '9'

(* The precision at [s.[i]] ("." and digits, or nothing), and the index
   after it. *)
let parse_precision s i =
  if s.[i] <> '.' then (default_precision, i)
  else
    let j = ref (i + 1) in
    while !j < String.length s && is_digit s.[!j] do incr j done;
    if !j >= String.length s then fail "error while parsing precision: could not find end of precision specifier";
    let digits = String.sub s (i + 1) (!j - i - 1) in
    match int_of_string_opt digits with
    | None ->
        fail "error while parsing precision: error while converting precision to integer: strconv.Atoi: parsing %S: %s"
          digits (if digits = "" then "invalid syntax" else "value out of range")
    | Some p when p > max_precision ->
        fail "error while parsing precision: precision %d exceeds maximum allowed precision %d" p max_precision
    | Some p -> (p, !j)

(* The clause after a '%' at [i]: its formatter and the index after it. *)
let parse_clause s i =
  let precision, j = parse_precision s i in
  let f =
    match s.[j] with
    | 's' -> as_string
    | 'd' -> decimal
    | 'f' -> fixed precision
    | 'e' -> scientific precision
    | 'b' -> binary
    | 'x' -> hex ~upper:false
    | 'X' -> hex ~upper:true
    | 'o' -> octal
    | c ->
        (* cel-go reads the clause byte as a code point *)
        let b = Buffer.create 4 in
        Buffer.add_utf_8_uchar b (Uchar.of_int (Char.code c));
        fail "unrecognized formatting clause \"%s\"" (Buffer.contents b)
  in
  (f, j + 1)

(* One substitution: the argument is fetched before the clause is parsed,
   so a missing argument is reported first, as in cel-go. *)
let substitute s i args k =
  if k >= Array.length args then fail "index %d out of range" k;
  if i + 1 >= String.length s then fail "unexpected end of string";
  let f, next =
    try parse_clause s (i + 1)
    with Format_error e -> fail "could not parse formatting clause: %s" e
  in
  let text = try f args.(k) with Format_error e -> fail "error during formatting: %s" e in
  (text, next)

let format s args =
  let buf = Buffer.create (String.length s) in
  let rec go i k =
    if i >= String.length s then ()
    else if s.[i] <> '%' then (Buffer.add_char buf s.[i]; go (i + 1) k)
    else if i + 1 < String.length s && s.[i + 1] = '%' then (Buffer.add_char buf '%'; go (i + 2) k)
    else
      let text, next = substitute s i args k in
      Buffer.add_string buf text;
      go next (k + 1)
  in
  match go 0 0 with
  | () -> String (Buffer.contents buf)
  | exception Format_error e -> Error e
