(* The CEL evaluator (docs/cordis/rfc/0001-cel.md): a tree walk over the
   expanded AST with cel-spec's error and short-circuit rules, the standard
   library, and an actual-cost counter. *)

open Value

(* ---- activation ------------------------------------------------------------------ *)

type activation = {
  vars : (string * Value.t) list;  (** declared inputs *)
  locals : (string * Value.t) list;  (** comprehension variables, innermost first *)
  container : string;  (** the namespace names are resolved in, e.g. "com.example" *)
}

let activation ?(container = "") vars = { vars; locals = []; container }

(* The names an identifier may mean inside a container, most qualified first:
   in "com.example", y is com.example.y, then com.y, then y. A leading dot
   names the root only. *)
let candidates container name =
  if String.length name > 0 && name.[0] = '.' then [ String.sub name 1 (String.length name - 1) ]
  else if container = "" then [ name ]
  else
    let parts = String.split_on_char '.' container in
    let n = List.length parts in
    List.init n (fun k -> String.concat "." (List.filteri (fun i _ -> i < n - k) parts) ^ "." ^ name) @ [ name ]

type counter = { mutable cost : int; limit : int option }

exception Cost_exceeded

let charge c n =
  c.cost <- c.cost + n;
  match c.limit with Some l when c.cost > l -> raise Cost_exceeded | _ -> ()

let type_idents =
  [ ("int", T_int); ("uint", T_uint); ("double", T_double); ("bool", T_bool);
    ("string", T_string); ("bytes", T_bytes); ("list", T_list); ("map", T_map);
    ("null_type", T_null); ("type", T_type);
    ("google.protobuf.Timestamp", T_timestamp); ("google.protobuf.Duration", T_duration);
    ("optional_type", T_optional) ]

let resolve_absolute act name =
  match List.assoc_opt name act.vars with
  | Some v -> Some v
  | None -> (
      match List.assoc_opt name type_idents with
      | Some t -> Some (Type t)
      | None -> Option.map (fun t -> Type t) (Registry.find_type name))

(* Comprehension variables shadow everything; then the container's
   candidates, most qualified first. *)
let resolve act name =
  match List.assoc_opt name act.locals with
  | Some v -> Some v
  | None -> List.find_map (resolve_absolute act) (candidates act.container name)

(* ---- helpers ------------------------------------------------------------------------ *)

let err fmt = Printf.ksprintf (fun s -> Error s) fmt

let utf8_length s =
  let n = ref 0 in
  String.iter (fun c -> if Char.code c land 0xC0 <> 0x80 then incr n) s;
  !n

let starts_with s p = String.length p <= String.length s && String.sub s 0 (String.length p) = p
let ends_with s p =
  let n = String.length s and m = String.length p in
  m <= n && String.sub s (n - m) m = p

let contains s p =
  let n = String.length s and m = String.length p in
  let rec go i = i + m <= n && (String.sub s i m = p || go (i + 1)) in
  m = 0 || go 0

let regex_cache : (string, Re.re option) Hashtbl.t = Hashtbl.create 16

let compile_regex p =
  match Hashtbl.find_opt regex_cache p with
  | Some r -> r
  | None ->
      let r = try Some (Re.compile (Re.Perl.re p)) with _ -> None in
      Hashtbl.add regex_cache p r;
      r

(* ---- arithmetic -------------------------------------------------------------------- *)

let overflow = Error "return error for overflow"

let add_int a b =
  let r = Int64.add a b in
  if (Int64.compare a 0L >= 0) = (Int64.compare b 0L >= 0) && (Int64.compare r 0L >= 0) <> (Int64.compare a 0L >= 0)
  then overflow else Int r

let sub_int a b =
  let r = Int64.sub a b in
  if (Int64.compare a 0L >= 0) <> (Int64.compare b 0L >= 0) && (Int64.compare r 0L >= 0) <> (Int64.compare a 0L >= 0)
  then overflow else Int r

let mul_int a b =
  if a = 0L || b = 0L then Int 0L
  else
    let r = Int64.mul a b in
    if (a = -1L && b = Int64.min_int) || (b = -1L && a = Int64.min_int) || Int64.div r b <> a then overflow
    else Int r

let add_uint a b =
  let r = Int64.add a b in
  if Int64.unsigned_compare r a < 0 then overflow else Uint r

let sub_uint a b = if Int64.unsigned_compare a b < 0 then overflow else Uint (Int64.sub a b)

let mul_uint a b =
  if a = 0L || b = 0L then Uint 0L
  else
    let r = Int64.mul a b in
    if Int64.unsigned_div r b <> a then overflow else Uint r

let time_result k = function Ok v -> k v | Result.Error e -> Error e

let arith op a b =
  match op, a, b with
  | "_+_", Int x, Int y -> add_int x y
  | "_-_", Int x, Int y -> sub_int x y
  | "_*_", Int x, Int y -> mul_int x y
  | "_/_", Int _, Int 0L -> Error "division by zero"
  | "_/_", Int x, Int y -> if x = Int64.min_int && y = -1L then overflow else Int (Int64.div x y)
  | "_%_", Int _, Int 0L -> Error "modulus by zero"
  | "_%_", Int x, Int y -> if x = Int64.min_int && y = -1L then overflow else Int (Int64.rem x y)
  | "_+_", Uint x, Uint y -> add_uint x y
  | "_-_", Uint x, Uint y -> sub_uint x y
  | "_*_", Uint x, Uint y -> mul_uint x y
  | "_/_", Uint _, Uint 0L -> Error "division by zero"
  | "_/_", Uint x, Uint y -> Uint (Int64.unsigned_div x y)
  | "_%_", Uint _, Uint 0L -> Error "modulus by zero"
  | "_%_", Uint x, Uint y -> Uint (Int64.unsigned_rem x y)
  | "_+_", Double x, Double y -> Double (x +. y)
  | "_-_", Double x, Double y -> Double (x -. y)
  | "_*_", Double x, Double y -> Double (x *. y)
  | "_/_", Double x, Double y -> Double (x /. y)
  | "_+_", String x, String y -> String (x ^ y)
  | "_+_", Bytes x, Bytes y -> Bytes (x ^ y)
  | "_+_", List x, List y -> List (Array.append x y)
  | "_+_", Timestamp (s, n), Duration (ds, dn) | "_+_", Duration (ds, dn), Timestamp (s, n) ->
      time_result (fun (s, n) -> Timestamp (s, n)) (Ctime.ts_add (s, n) (ds, dn))
  | "_-_", Timestamp (s, n), Duration (ds, dn) ->
      time_result (fun (s, n) -> Timestamp (s, n)) (Ctime.ts_add (s, n) (Ctime.dur_neg (ds, dn)))
  | "_-_", Timestamp (s1, n1), Timestamp (s2, n2) ->
      time_result (fun (s, n) -> Duration (s, n)) (Ctime.ts_sub (s1, n1) (s2, n2))
  | "_+_", Duration (s1, n1), Duration (s2, n2) ->
      time_result (fun (s, n) -> Duration (s, n)) (Ctime.dur_add (s1, n1) (s2, n2))
  | "_-_", Duration (s1, n1), Duration (s2, n2) ->
      time_result (fun (s, n) -> Duration (s, n)) (Ctime.dur_sub (s1, n1) (s2, n2))
  | _ -> no_overload op

let compare_values op a b =
  let c =
    match a, b with
    | (Int _ | Uint _ | Double _), (Int _ | Uint _ | Double _) -> (
        match compare_numeric a b with Some c -> `Cmp c | None -> `Unordered)
    | String x, String y -> `Cmp (String.compare x y)
    | Bytes x, Bytes y -> `Cmp (String.compare x y)
    | Bool x, Bool y -> `Cmp (compare x y)
    | Timestamp (s, n), Timestamp (s', n') | Duration (s, n), Duration (s', n') ->
        `Cmp (compare (s, n) (s', n'))
    | _ -> `No
  in
  match c with
  | `No -> no_overload op
  | `Unordered -> Bool false
  | `Cmp c ->
      Bool
        (match op with
         | "_<_" -> c < 0 | "_<=_" -> c <= 0 | "_>_" -> c > 0 | _ -> c >= 0)

(* ---- conversions ---------------------------------------------------------------- *)

let re_int = Re.compile (Re.Perl.re "^[+-]?[0-9]+$")
let re_uint = Re.compile (Re.Perl.re "^[0-9]+$")
let re_double =
  Re.compile (Re.Perl.re "^[+-]?(([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?|[iI][nN][fF]([iI][nN][iI][tT][yY])?|[nN][aA][nN])$")

let two63 = 9.223372036854775808e18
let two64 = 1.8446744073709551616e19

let float_to_uint d =
  if d < two63 then Int64.of_float d else Int64.add (Int64.of_float (d -. two63)) Int64.min_int

let convert fn v =
  match fn, v with
  | "int", Int _ -> v
  | "int", Uint u -> if Int64.compare u 0L < 0 then Error "range error converting uint to int" else Int u
  | "int", Double d ->
      if Float.is_nan d || d <= -.two63 || d >= two63 then Error "range error converting double to int"
      else Int (Int64.of_float d)
  | "int", String s ->
      if Re.execp re_int s then (match Int64.of_string_opt s with Some i -> Int i | None -> Error "range error")
      else err "cannot convert %S to int" s
  | "int", Timestamp (s, _) -> Int s
  | "uint", Uint _ -> v
  | "uint", Int i -> if Int64.compare i 0L < 0 then Error "range error converting int to uint" else Uint i
  | "uint", Double d ->
      if Float.is_nan d || d < 0.0 || d >= two64 then Error "range error converting double to uint"
      else Uint (float_to_uint (Float.trunc d))
  | "uint", String s ->
      if Re.execp re_uint s then (match Int64.of_string_opt ("0u" ^ s) with Some u -> Uint u | None -> Error "range error")
      else err "cannot convert %S to uint" s
  | "double", Double _ -> v
  | "double", Int i -> Double (Int64.to_float i)
  | "double", Uint u -> Double (uint_to_float u)
  | "double", String s ->
      if Re.execp re_double s then
        let l = String.lowercase_ascii s in
        let l = if String.length l > 0 && l.[0] = '+' then String.sub l 1 (String.length l - 1) else l in
        Double
          (match l with
           | "inf" | "infinity" -> Float.infinity
           | "-inf" | "-infinity" -> Float.neg_infinity
           | "nan" | "-nan" -> Float.nan
           | _ -> float_of_string l)
      else err "cannot convert %S to double" s
  | "string", String _ -> v
  | "string", Int i -> String (Int64.to_string i)
  | "string", Uint u -> String (Printf.sprintf "%Lu" u)
  | "string", Double d -> String (format_double d)
  | "string", Bool b -> String (string_of_bool b)
  | "string", Bytes b -> if String.is_valid_utf_8 b then String b else Error "invalid UTF-8 in bytes"
  | "string", Timestamp (s, n) -> String (Ctime.format_timestamp (s, n))
  | "string", Duration (s, n) -> String (Ctime.format_duration (s, n))
  | "bytes", Bytes _ -> v
  | "bytes", String s -> Bytes s
  | "bool", Bool _ -> v
  | "bool", String s -> (
      match s with
      | "1" | "t" | "true" | "TRUE" | "True" -> Bool true
      | "0" | "f" | "false" | "FALSE" | "False" -> Bool false
      | _ -> err "cannot convert %S to bool" s)
  | "dyn", _ -> v
  | "type", _ -> Type (type_of v)
  | _ -> no_overload fn

(* ---- the standard library --------------------------------------------------------- *)

let size_of = function
  | String s -> Int (Int64.of_int (utf8_length s))
  | Bytes b -> Int (Int64.of_int (String.length b))
  | List xs -> Int (Int64.of_int (Array.length xs))
  | Map m -> Int (Int64.of_int (List.length m))
  | _ -> no_overload "size"

let index a i =
  match a, i with
  | List xs, _ -> (
      let n = Array.length xs in
      let pos =
        match i with
        | Int k -> Some k
        | Uint k -> if Int64.compare k 0L < 0 then Some Int64.max_int else Some k
        | Double d when Float.is_integer d && Float.abs d < two63 -> Some (Int64.of_float d)
        | _ -> None
      in
      match pos with
      | None -> no_overload "_[_]"
      | Some k ->
          if Int64.compare k 0L < 0 || Int64.compare k (Int64.of_int n) >= 0 then
            err "index out of bounds: %Ld" k
          else xs.(Int64.to_int k))
  | Map m, k -> (
      match lookup_key m k with
      | Some v -> v
      | None ->
          if valid_key k || (match k with Double _ -> true | _ -> false) then
            err "no such key: %s" (to_string k)
          else no_overload "_[_]")
  | _ -> no_overload "_[_]"

(* x[?k] and x.?f: the element or entry as an optional, none when absent;
   on an optional, chained. *)
let rec opt_index a i =
  match a, i with
  | Optional None, _ -> Optional None
  | Optional (Some v), _ -> opt_index v i
  | List xs, (Int k | Uint k) ->
      if Int64.compare k 0L >= 0 && Int64.compare k (Int64.of_int (Array.length xs)) < 0 then
        Optional (Some xs.(Int64.to_int k))
      else Optional None
  | List _, _ -> no_overload "_[?_]"
  | Map m, k -> Optional (lookup_key m k)
  | _ -> no_overload "_[?_]"

let call_function fn args =
  match fn, args with
  | ("_+_" | "_-_" | "_*_" | "_/_" | "_%_"), [ a; b ] -> arith fn a b
  | "-_", [ Int i ] -> if i = Int64.min_int then overflow else Int (Int64.neg i)
  | "-_", [ Double d ] -> Double (-.d)
  | "!_", [ Bool b ] -> Bool (not b)
  | "_==_", [ a; b ] -> Bool (equal a b)
  | "_!=_", [ a; b ] -> Bool (not (equal a b))
  | ("_<_" | "_<=_" | "_>_" | "_>=_"), [ a; b ] -> compare_values fn a b
  | "@in", [ x; List xs ] -> Bool (Array.exists (equal x) xs)
  | "@in", [ k; Map m ] -> Bool (lookup_key m k <> None)
  | "_[_]", [ Optional None; _ ] -> Optional None
  | "_[_]", [ Optional (Some v); i ] -> (
      (* indexing an optional chains: a missing entry is none *)
      match v with Map _ | List _ -> opt_index v i | _ -> index v i)
  | "_[_]", [ a; i ] -> index a i
  | "_[?_]", [ a; i ] -> opt_index a i
  | "_?._", [ a; String f ] -> (
      match a with
      | Map m | Optional (Some (Map m)) -> Optional (map_find m (String f))
      | Optional None -> Optional None
      | _ -> err "type %s does not support optional field selection" (type_name (type_of a)))
  | "size", [ x ] -> size_of x
  | "contains", [ String s; String p ] -> Bool (contains s p)
  | "startsWith", [ String s; String p ] -> Bool (starts_with s p)
  | "endsWith", [ String s; String p ] -> Bool (ends_with s p)
  | "matches", [ String s; String p ] -> (
      match compile_regex p with
      | Some r -> Bool (Re.execp r s)
      | None -> err "invalid regular expression: %s" p)
  | ("int" | "uint" | "double" | "string" | "bytes" | "bool" | "dyn" | "type"), [ v ] -> convert fn v
  | "@map_insert", [ Map m; k; v ] -> (
      match lookup_key m k with
      | Some _ -> err "insert failed: key %s already exists" (to_string k)
      | None -> make_map ((k, v) :: m))
  | "@map_merge", [ Map m; Map entries ] ->
      if List.exists (fun (k, _) -> lookup_key m k <> None) entries then
        Error "insert failed: key already exists"
      else make_map (entries @ m)
  | "timestamp", [ (Timestamp _ as t) ] -> t
  | "timestamp", [ String s ] -> time_result (fun (s, n) -> Timestamp (s, n)) (Ctime.parse_timestamp s)
  | "timestamp", [ Int i ] -> time_result (fun (s, n) -> Timestamp (s, n)) (Ctime.check_ts (i, 0))
  | "duration", [ (Duration _ as d) ] -> d
  | "duration", [ String s ] -> time_result (fun (s, n) -> Duration (s, n)) (Ctime.parse_duration s)
  | ( ("getFullYear" | "getMonth" | "getDate" | "getDayOfMonth" | "getDayOfYear" | "getDayOfWeek" | "getHours"
      | "getMinutes" | "getSeconds" | "getMilliseconds") as part ),
    Timestamp (s, n) :: tz -> (
      match tz with
      | [] -> time_result (fun i -> Int i) (Ctime.timestamp_part part (s, n))
      | [ String z ] -> time_result (fun i -> Int i) (Ctime.timestamp_part part ~tz:z (s, n))
      | _ -> no_overload part)
  | ("getHours" | "getMinutes" | "getSeconds" | "getMilliseconds"), [ Duration (s, n) ] ->
      Int (Ctime.duration_part fn (s, n))
  | _ -> ( match Registry.call fn args with Some v -> v | None -> no_overload fn)

(* ---- evaluation ------------------------------------------------------------------ *)

let merge_unknowns vs =
  List.concat_map (function Unknown ids -> ids | _ -> []) vs |> List.sort_uniq compare

(* Strict calls: unknowns win over errors, and the first error wins. *)
let strict args k =
  if List.exists is_unknown args then Unknown (merge_unknowns args)
  else match List.find_opt is_error args with Some e -> e | None -> k args

let logic ~is_and l r =
  let short = not is_and in
  match l, r with
  | Bool b, _ when b = short -> Bool short
  | _, Bool b when b = short -> Bool short
  | Bool _, Bool _ -> Bool (not short)
  | _ ->
      if is_unknown l || is_unknown r then Unknown (merge_unknowns [ l; r ])
      else if is_error l then l
      else if is_error r then r
      else no_overload (if is_and then "_&&_" else "_||_")

(* A chain of identifiers a.b.c, for qualified-name resolution. *)
let rec ident_path (e : Ast.expr) =
  match e.kind with
  | Ast.Ident n -> Some [ n ]
  | Ast.Select { operand; field; test_only = false } ->
      Option.map (fun p -> p @ [ field ]) (ident_path operand)
  | _ -> None

let rec select v field ~test_only =
  match v with
  | Optional None -> if test_only then Bool false else Optional None
  | Optional (Some x) -> (
      (* selecting from an optional chains: a missing key is none *)
      match x, test_only with
      | Map m, false -> Optional (map_find m (String field))
      | _ -> select x field ~test_only)
  | Map m -> (
      match map_find m (String field), test_only with
      | Some _, true -> Bool true
      | None, true -> Bool false
      | Some x, false -> x
      | None, false -> err "no such key: %s" field)
  | Error _ | Unknown _ -> v
  | _ -> err "type %s does not support field selection" (type_name (type_of v))

(* Selection and indexing on an identifier or on another selection or index
   form one cel-go attribute; on anything else they start a new one. *)
let rec is_attribute_root (e : Ast.expr) =
  match e.kind with
  | Ast.Ident _ -> true
  | Ast.Select { operand; test_only = false; _ } -> is_attribute_root operand
  | Ast.Call { fn; target = None; args = [ operand; _ ] } when fn = Ast.op_index -> is_attribute_root operand
  | _ -> false

let rec eval c act (e : Ast.expr) : Value.t =
  match e.kind with
  | Ast.Const v -> v
  | Ast.Ident n -> (
      charge c 1;
      match resolve act n with Some v -> v | None -> err "undeclared reference to '%s'" n)
  | Ast.Select { operand; field; test_only } -> (
      (* cel-go's attribute cost: 1 for the attribute, 1 per qualifier; an
         attribute rooted at an identifier counts the identifier as the 1 *)
      charge c (if is_attribute_root operand then 1 else 2);
      (* qualified names: try the longest declared prefix of a.b.c first *)
      match ident_path e with
      | Some (root :: _ as path) when (not test_only) && not (List.mem_assoc root act.locals) ->
          let n = List.length path in
          (* the identifier and the inner selections, resolved together *)
          charge c (n - 1);
          let rec try_prefix k =
            if k = 0 then err "undeclared reference to '%s'" (String.concat "." path)
            else
              let name = String.concat "." (List.filteri (fun i _ -> i < k) path) in
              match resolve act name with
              | Some v ->
                  List.fold_left
                    (fun v f -> select v f ~test_only:false)
                    v
                    (List.filteri (fun i _ -> i >= k) path)
              | None -> try_prefix (k - 1)
          in
          try_prefix n
      | _ -> select (eval c act operand) field ~test_only)
  | Ast.List_lit es ->
      charge c 10;
      let vs = List.map (eval_element c act) es in
      strict (List.filter_map Fun.id vs) (fun vs -> List (Array.of_list vs))
  | Ast.Map_lit kvs ->
      charge c 30;
      let pairs =
        List.filter_map
          (fun ((k : Ast.expr), v) ->
            match k.kind with
            | Ast.Call { fn; args = [ k ]; _ } when fn = Ast.op_opt_elem -> (
                (* {?k: v}: the entry exists only when v has a value *)
                match eval c act v with
                | Optional None -> None
                | Optional (Some v) -> Some (eval c act k, v)
                | (Error _ | Unknown _) as e -> Some (eval c act k, e)
                | _ -> Some (eval c act k, Error "optional map entry needs an optional value"))
            | _ -> Some (eval c act k, eval c act v))
          kvs
      in
      let all = List.concat_map (fun (k, v) -> [ k; v ]) pairs in
      strict all (fun _ -> make_map pairs)
  | Ast.Call { fn; target = Some t; args } -> (
      (* a namespaced function: math.greatest(x) when math is not a value *)
      match ident_path t with
      | Some (root :: _ as p)
        when (not (List.mem_assoc root act.locals))
             && resolve act root = None
             && Registry.mem (String.concat "." (p @ [ fn ])) ->
          eval_call c act (String.concat "." (p @ [ fn ])) None args
      | _ -> eval_call c act fn (Some t) args)
  | Ast.Call { fn; target; args } -> eval_call c act fn target args
  | Ast.Comprehension { iter_var; iter_var2; range; accu_var; accu_init; loop_cond; loop_step; result } -> (
      let r = eval c act range in
      let items =
        match r, iter_var2 with
        | List xs, None -> Ok (Array.to_list (Array.map (fun x -> [ (iter_var, x) ]) xs))
        | List xs, Some v2 ->
            Ok (List.mapi (fun i x -> [ (iter_var, Int (Int64.of_int i)); (v2, x) ]) (Array.to_list xs))
        | Map m, None -> Ok (List.map (fun (k, _) -> [ (iter_var, k) ]) m)
        | Map m, Some v2 -> Ok (List.map (fun (k, v) -> [ (iter_var, k); (v2, v) ]) m)
        | (Error _ | Unknown _), _ -> Error r
        | _ -> Error (err "expression of type %s cannot be iterated" (type_name (type_of r)))
      in
      match items with
      | Error v -> v
      | Ok items ->
          let accu = ref (eval c act accu_init) in
          let rec loop = function
            | [] -> ()
            | binds :: rest ->
                let act' = { act with locals = binds @ ((accu_var, !accu) :: act.locals) } in
                (match eval c act' loop_cond with
                 | Bool false -> ()
                 | _ ->
                     accu := eval c act' loop_step;
                     loop rest)
          in
          loop items;
          eval c { act with locals = (accu_var, !accu) :: act.locals } result)

(* A list element; [?e] is present only when the optional e has a value. *)
and eval_element c act (e : Ast.expr) =
  match e.kind with
  | Ast.Call { fn; args = [ x ]; _ } when fn = Ast.op_opt_elem -> (
      match eval c act x with
      | Optional None -> None
      | Optional (Some v) -> Some v
      | (Error _ | Unknown _) as v -> Some v
      | _ -> Some (Error "optional list element needs an optional value"))
  | _ -> Some (eval c act e)

and eval_call c act fn target args =
  let open Ast in
  (* cel-go: logical operators and the conditional cost nothing themselves;
     every other call costs once its arguments are values (a call whose
     argument is already an error does not run, and is not charged) *)
  let own = if fn = op_index && not (is_attribute_root (List.hd args)) then 2 else 1 in
  if fn = op_not_strictly_false then charge c 1;
  match fn, target, args with
  | f, None, [ l; r ] when f = op_and || f = op_or ->
      let is_and = f = op_and in
      let lv = eval c act l in
      if lv = Bool (not is_and) then lv else logic ~is_and lv (eval c act r)
  | f, None, [ cond; t; e ] when f = op_cond -> (
      (* cel-go charges nothing for an identifier chosen by a conditional *)
      let branch (b : Ast.expr) =
        match b.kind with
        | Ident n -> (match resolve act n with Some v -> v | None -> err "undeclared reference to '%s'" n)
        | _ -> eval c act b
      in
      match eval c act cond with
      | Bool true -> branch t
      | Bool false -> branch e
      | (Error _ | Unknown _) as v -> v
      | _ -> no_overload op_cond)
  | f, None, [ x ] when f = op_not_strictly_false -> (
      match eval c act x with Bool b -> Bool b | _ -> Bool true)
  | _ ->
      (* strict calls evaluate their arguments left to right and stop at the
         first error, as cel-go does: later arguments are not evaluated (nor
         charged), and neither is the call *)
      let rec eval_args acc = function
        | [] -> Ok (List.rev acc)
        | a :: rest -> (
            match eval c act a with
            | Error _ as e ->
                (* every argument was evaluated when the error is the last *)
                if rest = [] then charge c own;
                Result.Error e
            | v -> eval_args (v :: acc) rest)
      in
      match eval_args [] (match target with Some t -> t :: args | None -> args) with
      | Result.Error e -> e
      | Ok vs ->
      strict vs (fun vs ->
          charge c own;
          (* equality costs ceil(min(size l, size r) / 10) in place of 1, as
             in cel-go; scalars have size 1, strings count code points *)
          (match fn, vs with
           | ("_==_" | "_!=_"), [ l; r ] ->
               let size = function
                 | String s -> utf8_length s | Bytes b -> String.length b
                 | List xs -> Array.length xs | Map m -> List.length m | _ -> 1
               in
               charge c (((min (size l) (size r) + 9) / 10) - 1)
           | _ -> ());
          call_function fn vs)

type result = { value : Value.t; cost : int }

let run ?limit ?(vars = []) ?container (e : Ast.expr) =
  let c = { cost = 0; limit } in
  match eval c (activation ?container vars) e with
  | v -> { value = v; cost = c.cost }
  | exception Cost_exceeded -> { value = Error "cost limit exceeded"; cost = c.cost }
