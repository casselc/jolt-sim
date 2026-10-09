(* The strings extension library: cel-go's ext.Strings at its latest
   version (ext/strings.go), checked against cel-spec's string_ext
   conformance file.

   Positions and lengths are in code points. A string is split into its
   code points, each held as its UTF-8 bytes; an invalid byte stands for
   U+FFFD, as Go's []rune conversion has it. *)

open Value

let err fmt = Printf.ksprintf (fun s -> Error s) fmt

(* ---- code points -------------------------------------------------------------------- *)

let replacement = "\xEF\xBF\xBD"

(* The byte length of the code point at [i]; an invalid byte counts alone,
   as Go's decoder has it. *)
let width s i =
  let d = String.get_utf_8_uchar s i in
  if Uchar.utf_decode_is_valid d then Uchar.utf_decode_length d else 1

(* The byte offset of each code point. *)
let offsets s =
  let rec go i acc = if i >= String.length s then Array.of_list (List.rev acc) else go (i + width s i) (i :: acc) in
  go 0 []

let code_points s =
  Array.map
    (fun i ->
      let n = width s i in
      if Uchar.utf_decode_is_valid (String.get_utf_8_uchar s i) then String.sub s i n else replacement)
    (offsets s)

let concat cps from upto = String.concat "" (Array.to_list (Array.sub cps from (upto - from)))
let length cps = Int64.of_int (Array.length cps)

(* Whether [sub] occurs in [cps] at [i]. *)
let matches_at cps sub i =
  let m = Array.length sub in
  let rec go j = j >= m || (String.equal cps.(i + j) sub.(j) && go (j + 1)) in
  i + m <= Array.length cps && go 0

let out_of_range i = err "index out of range: %Ld" i
let in_range cps i = Int64.compare i 0L >= 0 && Int64.compare i (length cps) <= 0

(* ---- charAt, indexOf, lastIndexOf, substring ------------------------------------------- *)

(* The position just past the end is valid and gives "". *)
let char_at s i =
  let cps = code_points s in
  if not (in_range cps i) then out_of_range i
  else if i = length cps then String ""
  else String cps.(Int64.to_int i)

(* cel-go returns -1 for an offset past the end; cel-spec's conformance
   (indexof_out_of_range) makes it an error, as for a negative offset. *)
let index_of s sub off =
  let cps = code_points s and sub = code_points sub in
  if not (in_range cps off) then out_of_range off
  else if Array.length sub = 0 then Int off
  else
    let n = Array.length cps in
    let rec go i = if i >= n then -1 else if matches_at cps sub i then i else go (i + 1) in
    Int (Int64.of_int (go (Int64.to_int off)))

(* [off] is the last position a match may start at. As in cel-go, an
   offset at the very end finds nothing for a non-empty needle; one past the
   end is an error (lastindexof_out_of_range). *)
let last_index_of_from s sub off =
  let cps = code_points s and sub = code_points sub in
  if not (in_range cps off) then out_of_range off
  else if Array.length sub = 0 then Int off
  else if off = length cps then Int (-1L)
  else
    let start = min (Int64.to_int off) (Array.length cps - Array.length sub) in
    let rec go i = if i < 0 then -1 else if matches_at cps sub i then i else go (i - 1) in
    Int (Int64.of_int (go start))

(* Without an offset the search starts at the last character, and the empty
   needle is found at the end. *)
let last_index_of s sub =
  let n = length (code_points s) in
  if sub = "" then Int n
  else if String.length s < String.length sub then Int (-1L)
  else last_index_of_from s sub (Int64.pred n)

let substring_from s start =
  let cps = code_points s in
  if not (in_range cps start) then out_of_range start
  else String (concat cps (Int64.to_int start) (Array.length cps))

let substring s start stop =
  let cps = code_points s in
  if Int64.compare start stop > 0 then err "invalid substring range. start: %Ld, end: %Ld" start stop
  else if not (in_range cps start) then out_of_range start
  else if not (in_range cps stop) then out_of_range stop
  else String (concat cps (Int64.to_int start) (Int64.to_int stop))

(* ---- replace and split ---------------------------------------------------------------- *)

(* The byte offsets of the first [limit] non-overlapping occurrences of
   [old] (all of them when [limit] is negative). The empty needle occurs at
   every code point boundary, as in Go's strings.Replace. *)
let occurrences s old limit =
  let n = String.length s and m = String.length old in
  let boundaries () = Array.to_list (offsets s) @ [ n ] in
  let rec scan i acc =
    if i + m > n then List.rev acc
    else if String.sub s i m = old then scan (i + m) (i :: acc)
    else scan (i + 1) acc
  in
  let all = if m = 0 then boundaries () else scan 0 [] in
  if Int64.compare limit 0L < 0 then all else List.filteri (fun k _ -> Int64.of_int k < limit) all

let replace s old rep limit =
  let m = String.length old in
  let buf = Buffer.create (String.length s) in
  let last =
    List.fold_left
      (fun from at ->
        Buffer.add_string buf (String.sub s from (at - from));
        Buffer.add_string buf rep;
        at + m)
      0 (occurrences s old limit)
  in
  Buffer.add_string buf (String.sub s last (String.length s - last));
  String (Buffer.contents buf)

(* The pieces of [s] between byte offsets [cuts], skipping [m] bytes (the
   separator) after each cut. *)
let pieces s cuts m =
  let starts = 0 :: List.map (fun at -> at + m) cuts and stops = cuts @ [ String.length s ] in
  List.map2 (fun a b -> String.sub s a (b - a)) starts stops

(* Go's strings.SplitN: a limit of 0 gives no pieces, a positive limit at
   most that many (the last holding the rest), a negative limit all. An
   empty separator splits into code points (none for ""). *)
let split s sep limit =
  let most = if Int64.compare limit 0L > 0 then Int64.pred limit else limit in
  let parts =
    if limit = 0L || s = "" && sep = "" then []
    else if sep = "" then
      let cuts = List.tl (Array.to_list (offsets s)) in
      pieces s (if Int64.compare most 0L < 0 then cuts else List.filteri (fun k _ -> Int64.of_int k < most) cuts) 0
    else pieces s (occurrences s sep most) (String.length sep)
  in
  List (Array.of_list (List.map (fun p -> String p) parts))

(* ---- the rest ------------------------------------------------------------------------- *)

let map_ascii f s = String.map (fun c -> if Char.code c < 128 then f c else c) s

(* Unicode White_Space, as Go's unicode.IsSpace has it (no zero-width
   spaces). *)
let is_space cp =
  match cp with
  | "\t" | "\n" | "\x0B" | "\x0C" | "\r" | " " -> true
  | _ -> (
      match Uchar.to_int (Uchar.utf_decode_uchar (String.get_utf_8_uchar cp 0)) with
      | 0x85 | 0xA0 | 0x1680 | 0x2028 | 0x2029 | 0x202F | 0x205F | 0x3000 -> true
      | c -> c >= 0x2000 && c <= 0x200A)

(* The kept text is copied byte for byte (invalid bytes are not space). *)
let trim s =
  let cps = code_points s and offs = offsets s in
  let n = Array.length cps in
  let rec first i = if i < n && is_space cps.(i) then first (i + 1) else i in
  let rec last j = if j > 0 && is_space cps.(j - 1) then last (j - 1) else j in
  let a = first 0 and b = last n in
  let at k = if k = n then String.length s else offs.(k) in
  if a >= b then String "" else String (String.sub s (at a) (at b - at a))

let reverse s =
  let cps = code_points s in
  let n = Array.length cps in
  String (String.concat "" (List.init n (fun k -> cps.(n - 1 - k))))

let join xs sep =
  let strs = Array.to_list xs in
  match List.find_opt (function String _ -> false | _ -> true) strs with
  | Some bad -> err "join: invalid input: %s" (to_string bad)
  | None -> String (String.concat sep (List.map (function String s -> s | _ -> "") strs))

(* strings.quote: a double-quoted string with CEL's escapes; invalid UTF-8
   becomes U+FFFD. *)
let quote s =
  let escape = function
    | "\x07" -> "\\a" | "\b" -> "\\b" | "\x0C" -> "\\f" | "\n" -> "\\n" | "\r" -> "\\r"
    | "\t" -> "\\t" | "\x0B" -> "\\v" | "\\" -> "\\\\" | "\"" -> "\\\"" | cp -> cp
  in
  String ("\"" ^ String.concat "" (Array.to_list (Array.map escape (code_points s))) ^ "\"")

(* ---- registration --------------------------------------------------------------------- *)

(* Signatures, as cel-go's ext.Strings declares them. *)
let declare () =
  let m params result = (true, params, result) in
  let d = Registry.declare in
  d "charAt" [ m [ "string"; "int" ] "string" ];
  d "indexOf" [ m [ "string"; "string" ] "int"; m [ "string"; "string"; "int" ] "int" ];
  d "lastIndexOf" [ m [ "string"; "string" ] "int"; m [ "string"; "string"; "int" ] "int" ];
  d "lowerAscii" [ m [ "string" ] "string" ];
  d "upperAscii" [ m [ "string" ] "string" ];
  d "replace" [ m [ "string"; "string"; "string" ] "string"; m [ "string"; "string"; "string"; "int" ] "string" ];
  d "split" [ m [ "string"; "string" ] "list(string)"; m [ "string"; "string"; "int" ] "list(string)" ];
  d "substring" [ m [ "string"; "int" ] "string"; m [ "string"; "int"; "int" ] "string" ];
  d "trim" [ m [ "string" ] "string" ];
  d "reverse" [ m [ "string" ] "string" ];
  d "join" [ m [ "list(string)" ] "string"; m [ "list(string)"; "string" ] "string" ];
  d "strings.quote" [ (false, [ "string" ], "string") ];
  d "format" [ m [ "string"; "list(dyn)" ] "string" ]

let register () =
  declare ();
  let reg = Registry.register in
  reg "charAt" (function [ String s; Int i ] -> Some (char_at s i) | _ -> None);
  reg "indexOf" (function
    | [ String s; String sub ] -> Some (index_of s sub 0L)
    | [ String s; String sub; Int off ] -> Some (index_of s sub off)
    | _ -> None);
  reg "lastIndexOf" (function
    | [ String s; String sub ] -> Some (last_index_of s sub)
    | [ String s; String sub; Int off ] -> Some (last_index_of_from s sub off)
    | _ -> None);
  reg "lowerAscii" (function [ String s ] -> Some (String (map_ascii Char.lowercase_ascii s)) | _ -> None);
  reg "upperAscii" (function [ String s ] -> Some (String (map_ascii Char.uppercase_ascii s)) | _ -> None);
  reg "replace" (function
    | [ String s; String old; String rep ] -> Some (replace s old rep (-1L))
    | [ String s; String old; String rep; Int n ] -> Some (replace s old rep n)
    | _ -> None);
  reg "split" (function
    | [ String s; String sep ] -> Some (split s sep (-1L))
    | [ String s; String sep; Int n ] -> Some (split s sep n)
    | _ -> None);
  reg "substring" (function
    | [ String s; Int start ] -> Some (substring_from s start)
    | [ String s; Int start; Int stop ] -> Some (substring s start stop)
    | _ -> None);
  reg "trim" (function [ String s ] -> Some (trim s) | _ -> None);
  reg "reverse" (function [ String s ] -> Some (reverse s) | _ -> None);
  reg "join" (function
    | [ List xs ] -> Some (join xs "")
    | [ List xs; String sep ] -> Some (join xs sep)
    | _ -> None);
  reg "strings.quote" (function [ String s ] -> Some (quote s) | _ -> None);
  reg "format" (function
    | [ String s; List args ] -> Some (Ext_strings_format.format s args)
    | _ -> None)
