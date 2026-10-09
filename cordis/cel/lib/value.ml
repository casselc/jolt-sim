(* CEL values (docs/cordis/rfc/0001-cel.md, "Values").

   Int and uint are 64-bit; uint keeps the bit pattern in an int64 and uses
   unsigned operations. Maps are ordered by CEL key order so that iteration,
   and therefore every cell's output, is deterministic. Errors and unknowns
   are values, so the evaluator can absorb them in && and || as cel-spec
   requires. *)

type ty =
  | T_null
  | T_bool
  | T_int
  | T_uint
  | T_double
  | T_string
  | T_bytes
  | T_list
  | T_map
  | T_type
  | T_timestamp
  | T_duration
  | T_optional
  | T_named of string

let type_name = function
  | T_null -> "null_type"
  | T_bool -> "bool"
  | T_int -> "int"
  | T_uint -> "uint"
  | T_double -> "double"
  | T_string -> "string"
  | T_bytes -> "bytes"
  | T_list -> "list"
  | T_map -> "map"
  | T_type -> "type"
  | T_timestamp -> "google.protobuf.Timestamp"
  | T_duration -> "google.protobuf.Duration"
  | T_optional -> "optional_type"
  | T_named n -> n

type t =
  | Null
  | Bool of bool
  | Int of int64
  | Uint of int64
  | Double of float
  | String of string
  | Bytes of string
  | List of t array
  | Map of (t * t) list  (** sorted by [compare_keys], no duplicates *)
  | Type of ty
  | Timestamp of int64 * int  (** seconds since the epoch, nanoseconds *)
  | Duration of int64 * int  (** seconds, nanoseconds; same sign *)
  | Optional of t option
  | Error of string
  | Unknown of int list
  | Message of { tname : string; fields : (string * t) list }
      (** a protobuf message, by full type name; only present fields are
          stored (proto3 scalars equal to their zero value and empty
          repeated fields are absent), sorted by field name *)
  | Enum of { tname : string; num : int }
      (** a strongly typed enum value (cel-spec's strong enum mode; by
          default enum values are ints) *)
  | Opaque of { tname : string; data : string }
      (** a value of an extension type (e.g. [net.IP]), held in a canonical
          string form; equal when type and form are equal *)

let type_of = function
  | Null -> T_null
  | Bool _ -> T_bool
  | Int _ -> T_int
  | Uint _ -> T_uint
  | Double _ -> T_double
  | String _ -> T_string
  | Bytes _ -> T_bytes
  | List _ -> T_list
  | Map _ -> T_map
  | Type _ -> T_type
  | Timestamp _ -> T_timestamp
  | Duration _ -> T_duration
  | Optional _ -> T_optional
  | Error _ | Unknown _ -> T_named "error"
  | Opaque { tname; _ } | Message { tname; _ } | Enum { tname; _ } -> T_named tname

let is_error = function Error _ -> true | _ -> false
let is_unknown = function Unknown _ -> true | _ -> false

let no_overload name = Error (Printf.sprintf "no such overload: %s" name)

(* ---- numbers ------------------------------------------------------------------- *)

let uint_to_float u =
  if Int64.compare u 0L >= 0 then Int64.to_float u
  else
    (* top bit set: halve, convert, double, keeping the low bit *)
    let half = Int64.shift_right_logical u 1 in
    let low = Int64.logand u 1L in
    (Int64.to_float half *. 2.0) +. Int64.to_float low

(* Compare an int64 with an unsigned int64 numerically. *)
let compare_int_uint i u =
  if Int64.compare i 0L < 0 then -1
  else if Int64.compare u 0L < 0 then -1 (* u >= 2^63 > i *)
  else Int64.compare i u

(* Compare a double with an int or uint as cel-spec and cel-go do: outside
   the integer type's range the double is simply smaller or larger;
   otherwise the integer is converted to a double (possibly rounding) and the
   two doubles are compared. *)
let compare_double_int d i =
  if Float.is_nan d then None
  else if d < -9.223372036854775808e18 then Some (-1)
  else if d > 9.223372036854775807e18 then Some 1
  else Some (compare d (Int64.to_float i))

let compare_double_uint d u =
  if Float.is_nan d then None
  else if d < 0.0 then Some (-1)
  else if d > 1.8446744073709551615e19 then Some 1
  else Some (compare d (uint_to_float u))

(* Numeric comparison across int, uint and double; None when unordered or not
   both numbers. *)
let compare_numeric a b =
  match a, b with
  | Int x, Int y -> Some (Int64.compare x y)
  | Uint x, Uint y -> Some (Int64.unsigned_compare x y)
  | Double x, Double y -> if Float.is_nan x || Float.is_nan y then None else Some (compare x y)
  | Int x, Uint y -> Some (compare_int_uint x y)
  | Uint x, Int y -> Some (- compare_int_uint y x)
  | Double x, Int y -> compare_double_int x y
  | Int x, Double y -> Option.map (fun c -> - c) (compare_double_int y x)
  | Double x, Uint y -> compare_double_uint x y
  | Uint x, Double y -> Option.map (fun c -> - c) (compare_double_uint y x)
  | _ -> None

let is_numeric = function Int _ | Uint _ | Double _ -> true | _ -> false

(* ---- map keys ------------------------------------------------------------------ *)

(* CEL key order: bool < numbers (by value) < string. Numeric keys of
   different types with the same value are the same key. *)
let key_rank = function Bool _ -> 0 | Int _ | Uint _ -> 1 | String _ -> 2 | _ -> 3

let compare_keys a b =
  match a, b with
  | Bool x, Bool y -> compare x y
  | (Int _ | Uint _), (Int _ | Uint _) -> Option.get (compare_numeric a b)
  | String x, String y -> String.compare x y
  | _ -> compare (key_rank a) (key_rank b)

let valid_key = function Bool _ | Int _ | Uint _ | String _ -> true | _ -> false

let map_find m k = List.find_map (fun (k', v) -> if compare_keys k k' = 0 then Some v else None) m

(* Map lookup with CEL's numeric key equivalence: a double key that is an
   integral value finds the int or uint key with that value. *)
let lookup_key m k =
  match k with
  | Double d when Float.is_integer d ->
      let as_int =
        if d >= -9.223372036854775808e18 && d < 9.223372036854775808e18 then
          map_find m (Int (Int64.of_float d))
        else None
      in
      (match as_int with
       | Some _ -> as_int
       | None ->
           if d >= 0.0 && d < 1.8446744073709551616e19 then
             (* convert via halves to stay exact above 2^63 *)
             let u =
               if d < 9.223372036854775808e18 then Int64.of_float d
               else Int64.add (Int64.of_float (d -. 9.223372036854775808e18)) Int64.min_int
             in
             map_find m (Uint u)
           else None)
  | Double _ -> None
  | _ when valid_key k -> map_find m k
  | _ -> None

let make_map pairs =
  let insert acc (k, v) =
    if not (valid_key k) then
      Result.Error (Printf.sprintf "unsupported key type: %s" (type_name (type_of k)))
    else if List.exists (fun (k', _) -> compare_keys k k' = 0) acc then
      Result.Error "Failed with repeated key"
    else Result.Ok ((k, v) :: acc)
  in
  let rec go acc = function
    | [] -> Map (List.sort (fun (a, _) (b, _) -> compare_keys a b) acc)
    | p :: rest -> (match insert acc p with Result.Ok acc -> go acc rest | Result.Error e -> Error e)
  in
  go [] pairs

(* ---- equality ------------------------------------------------------------------- *)

(* CEL's heterogeneous equality: numbers compare by value across int, uint
   and double; values of other different types are unequal (not an error). *)
let rec equal a b =
  match a, b with
  | (Int _ | Uint _ | Double _), (Int _ | Uint _ | Double _) -> compare_numeric a b = Some 0
  | Null, Null -> true
  | Bool x, Bool y -> x = y
  | String x, String y -> String.equal x y
  | Bytes x, Bytes y -> String.equal x y
  | List xs, List ys -> Array.length xs = Array.length ys && Array.for_all2 equal xs ys
  | Map xs, Map ys ->
      List.length xs = List.length ys
      && List.for_all (fun (k, v) -> match map_find ys k with Some v' -> equal v v' | None -> false) xs
  | Type x, Type y -> type_name x = type_name y
  | Timestamp (s, n), Timestamp (s', n') | Duration (s, n), Duration (s', n') -> s = s' && n = n'
  | Opaque a, Opaque b -> a.tname = b.tname && a.data = b.data
  | Message a, Message b ->
      a.tname = b.tname
      && List.length a.fields = List.length b.fields
      && List.for_all2 (fun (n, v) (n', v') -> n = n' && equal v v') a.fields b.fields
  | Enum a, Enum b -> a.tname = b.tname && a.num = b.num
  | Optional x, Optional y -> (
      match x, y with None, None -> true | Some x, Some y -> equal x y | _ -> false)
  | _ -> false

(* ---- printing (for errors and tests) ---------------------------------------- *)

let format_double d =
  if Float.is_nan d then "NaN"
  else if d = Float.infinity then "+Inf"
  else if d = Float.neg_infinity then "-Inf"
  else
    let rec go p =
      let s = Printf.sprintf "%.*g" p d in
      if p >= 17 || float_of_string s = d then s else go (p + 1)
    in
    go 1

let rec to_string = function
  | Null -> "null"
  | Bool b -> string_of_bool b
  | Int i -> Int64.to_string i
  | Uint u -> Printf.sprintf "%Luu" u
  | Double d -> format_double d
  | String s -> Printf.sprintf "%S" s
  | Bytes b -> Printf.sprintf "b%S" b
  | List xs -> "[" ^ String.concat ", " (Array.to_list (Array.map to_string xs)) ^ "]"
  | Map m -> "{" ^ String.concat ", " (List.map (fun (k, v) -> to_string k ^ ": " ^ to_string v) m) ^ "}"
  | Type t -> type_name t
  | Timestamp (s, n) -> Printf.sprintf "timestamp(%Ld.%09d)" s n
  | Duration (s, n) -> Printf.sprintf "duration(%Ld.%09d)" s n
  | Optional None -> "optional.none()"
  | Optional (Some v) -> "optional.of(" ^ to_string v ^ ")"
  | Error e -> "error(" ^ e ^ ")"
  | Unknown ids -> "unknown(" ^ String.concat "," (List.map string_of_int ids) ^ ")"
  | Opaque { tname; data } -> Printf.sprintf "%s(%S)" tname data
  | Message { tname; fields } ->
      tname ^ "{" ^ String.concat ", " (List.map (fun (n, v) -> n ^ ": " ^ to_string v) fields) ^ "}"
  | Enum { tname; num } -> Printf.sprintf "%s(%d)" tname num
