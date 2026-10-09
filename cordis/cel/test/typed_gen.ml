(* A type-directed generator of CEL expressions, driven by Hegel draws:
   choose a type, then build an expression intended to have that type, over
   variables declared with types and bound to values of those types. Used by
   the checker soundness property (props.ml) and the checker differential
   against cel-go (differential.ml). *)

open Cordis_cel

type ty =
  | Int
  | Uint
  | Double
  | Bool
  | String
  | Bytes
  | List of ty
  | Map of ty * ty  (** keys are Int or String *)

let rec ty_text = function
  | Int -> "int" | Uint -> "uint" | Double -> "double" | Bool -> "bool" | String -> "string" | Bytes -> "bytes"
  | List t -> "list(" ^ ty_text t ^ ")"
  | Map (k, v) -> "map(" ^ ty_text k ^ ", " ^ ty_text v ^ ")"

let to_checker t = Checker.parse_type (ty_text t)

type draw = { pick : int -> int }

let of_hegel tc =
  { pick = (fun n -> if n <= 1 then 0 else Hegel.draw_silent tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ())) }

let choose d l = List.nth l (d.pick (List.length l))

let rec gen_ty d depth =
  match d.pick (if depth <= 0 then 6 else 8) with
  | 0 -> Int | 1 -> Uint | 2 -> Double | 3 -> Bool | 4 -> String | 5 -> Bytes
  | 6 -> List (gen_ty d (depth - 1))
  | _ -> Map (choose d [ Int; String ], gen_ty d (depth - 1))

(* ---- literals and values ------------------------------------------------------------ *)

let int_lits = [ "0"; "1"; "-1"; "2"; "7"; "-13"; "42"; "9223372036854775807"; "-9223372036854775808"; "0x1f" ]
let uint_lits = [ "0u"; "1u"; "3u"; "100u"; "18446744073709551615u" ]
let double_lits = [ "0.0"; "1.5"; "-2.25"; "1e10"; "-0.0"; "3.0"; "0.1" ]
let string_lits =
  [ "''"; "'a'"; "'abc'"; "'héllo'"; "\"\\u00e9\""; "'a\\nb'"; "r'\\d+'"; "'abcdefghijklmnopqrstuvwxyz'";
    "'the quick brown fox jumps over the lazy dog'" ]
let bytes_lits = [ "b''"; "b'ab'"; "b'\\x00\\xff'"; "b'z'" ]

let rec value d ty : Value.t =
  match ty with
  | Int -> Value.Int (Int64.of_int (d.pick 2001 - 1000))
  | Uint -> Value.Uint (Int64.of_int (d.pick 2001))
  | Double -> Value.Double (float_of_int (d.pick 2001 - 1000) /. 4.0)
  | Bool -> Value.Bool (d.pick 2 = 0)
  | String -> Value.String (choose d [ ""; "a"; "abc"; "héllo"; "xyz"; "abcdefghijklmnopqrstuvwxyz"; String.make (d.pick 60) 'q' ])
  | Bytes -> Value.Bytes (choose d [ ""; "a"; "\000\255"; String.make (d.pick 40) 'b' ])
  | List t -> Value.List (Array.init (d.pick 4) (fun _ -> value d t))
  | Map (k, v) -> (
      let n = d.pick 4 in
      let keys = List.sort_uniq compare (List.init n (fun _ -> value d k)) in
      match Value.make_map (List.map (fun key -> (key, value d v)) keys) with Value.Map _ as m -> m | _ -> Value.Map [])

(* The corpus encoding of a value, for cel-go. *)
let rec encode (v : Value.t) : Yojson.Safe.t =
  match v with
  | Value.Int i -> `Assoc [ ("int", `String (Int64.to_string i)) ]
  | Value.Uint u -> `Assoc [ ("uint", `String (Printf.sprintf "%Lu" u)) ]
  | Value.Double f -> `Assoc [ ("double", `Float f) ]
  | Value.Bool b -> `Assoc [ ("bool", `Bool b) ]
  | Value.String s -> `Assoc [ ("string", `String s) ]
  | Value.Bytes b -> `Assoc [ ("bytes", `String (Ext_encoders.encode b)) ]
  | Value.List xs -> `Assoc [ ("list", `List (Array.to_list (Array.map encode xs))) ]
  | Value.Map m -> `Assoc [ ("map", `List (List.map (fun (k, v) -> `List [ encode k; encode v ]) m)) ]
  | _ -> `Null

(* ---- expressions ---------------------------------------------------------------------- *)

type scope = (string * ty) list

(* A macro's variable shadows any outer one of the same name. *)
let bind n t (scope : scope) = (n, t) :: List.filter (fun (m, _) -> m <> n) scope

let rec expr d (scope : scope) ty depth : string =
  let sub t = expr d scope t (depth - 1) in
  let vars = List.filter (fun (_, t) -> t = ty) scope in
  let common () =
    match d.pick 3 with
    | 0 when vars <> [] -> fst (choose d vars)
    | 1 -> Printf.sprintf "(%s ? %s : %s)" (sub Bool) (sub ty) (sub ty)
    | _ -> Printf.sprintf "[%s][0]" (sub ty)
  in
  if depth <= 0 then leaf d scope ty
  else if d.pick 4 = 0 then common ()
  else
    match ty with
    | Int -> (
        match d.pick 6 with
        | 0 -> leaf d scope Int
        | 1 -> Printf.sprintf "(%s %s %s)" (sub Int) (choose d [ "+"; "-"; "*"; "/"; "%" ]) (sub Int)
        | 2 -> Printf.sprintf "-(%s)" (sub Int)
        | 3 -> Printf.sprintf "size(%s)" (sub (choose d [ String; Bytes; List Int; Map (String, Bool) ]))
        | 4 -> Printf.sprintf "int(%s)" (sub (choose d [ Uint; Double; Int ]))
        | _ -> Printf.sprintf "%s.map(x, x * 2)[0]" (list_of d scope Int (depth - 1)))
    | Uint -> (
        match d.pick 3 with
        | 0 -> leaf d scope Uint
        | 1 -> Printf.sprintf "(%s %s %s)" (sub Uint) (choose d [ "+"; "-"; "*"; "/"; "%" ]) (sub Uint)
        | _ -> Printf.sprintf "uint(%s)" (sub (choose d [ Int; Double ])))
    | Double -> (
        match d.pick 4 with
        | 0 -> leaf d scope Double
        | 1 -> Printf.sprintf "(%s %s %s)" (sub Double) (choose d [ "+"; "-"; "*"; "/" ]) (sub Double)
        | 2 -> Printf.sprintf "double(%s)" (sub (choose d [ Int; Uint ]))
        | _ -> Printf.sprintf "-(%s)" (sub Double))
    | Bool -> (
        match d.pick 12 with
        | 8 -> Printf.sprintf "%s.matches(%s)" (sub String) (choose d [ "'a+'"; "'^h.*o$'"; "'[a-z]+b?c*'"; "''" ])
        | 9 -> Printf.sprintf "(%s in %s)" (sub String) (sub (List String))
        | 10 ->
            let t = choose d [ List Int; List String; Map (String, Int); String; Bytes ] in
            Printf.sprintf "(%s %s %s)" (sub t) (choose d [ "=="; "!=" ]) (sub t)
        | 11 ->
            let m = choose d [ "all"; "exists"; "exists_one" ] in
            Printf.sprintf "%s.%s(w, %s)" (sub (List String)) m (expr d (bind "w" String scope) Bool (depth - 1))
        | 0 -> leaf d scope Bool
        | 1 ->
            let t = choose d [ Int; Uint; Double; String; Bytes; Bool ] in
            Printf.sprintf "(%s %s %s)" (sub t) (choose d [ "<"; "<="; ">"; ">="; "=="; "!=" ]) (sub t)
        | 2 ->
            let a, b = choose d [ (Int, Double); (Uint, Int); (Double, Uint) ] in
            (* cross-type ordering is declared; cross-type equality is not *)
            Printf.sprintf "(%s %s %s)" (sub a) (choose d [ "<"; ">="; ">" ]) (sub b)
        | 3 -> Printf.sprintf "(%s %s %s)" (sub Bool) (choose d [ "&&"; "||" ]) (sub Bool)
        | 4 -> Printf.sprintf "!(%s)" (sub Bool)
        | 5 -> Printf.sprintf "(%s in %s)" (sub Int) (list_of d scope Int (depth - 1))
        | 6 -> Printf.sprintf "%s.%s(%s)" (sub String) (choose d [ "contains"; "startsWith"; "endsWith" ]) (sub String)
        | _ ->
            let m = choose d [ "all"; "exists"; "exists_one" ] in
            Printf.sprintf "%s.%s(x, %s)" (list_of d scope Int (depth - 1)) m (expr d (bind "x" Int scope) Bool (depth - 1)))
    | String -> (
        match d.pick 3 with
        | 0 -> leaf d scope String
        | 1 -> Printf.sprintf "(%s + %s)" (sub String) (sub String)
        | _ -> Printf.sprintf "string(%s)" (sub (choose d [ Int; Uint; Double; Bool; String ])))
    | Bytes -> (
        match d.pick 3 with
        | 0 -> leaf d scope Bytes
        | 1 -> Printf.sprintf "(%s + %s)" (sub Bytes) (sub Bytes)
        | _ -> Printf.sprintf "bytes(%s)" (sub String))
    | List t -> (
        match d.pick 4 with
        | 0 -> list_of d scope t (depth - 1)
        | 1 -> Printf.sprintf "(%s + %s)" (sub ty) (sub ty)
        | 2 -> Printf.sprintf "%s.filter(y, %s)" (sub ty) (expr d (bind "y" t scope) Bool (depth - 1))
        | _ ->
            (* over a literal or any list-valued expression, inputs included *)
            let s = choose d [ Int; String ] in
            let range = if d.pick 2 = 0 then list_of d scope s (depth - 1) else sub (List s) in
            Printf.sprintf "%s.map(z, %s)" range (expr d (bind "z" s scope) t (depth - 1)))
    | Map (k, v) ->
        let n = d.pick 3 in
        "{" ^ String.concat ", " (List.init n (fun _ -> sub k ^ ": " ^ sub v)) ^ "}"

(* A non-empty list literal, so its element type is determined. *)
and list_of d scope t depth =
  "[" ^ String.concat ", " (List.init (1 + d.pick 3) (fun _ -> expr d scope t depth)) ^ "]"

and leaf d scope ty =
  let vars = List.filter (fun (_, t) -> t = ty) scope in
  if vars <> [] && d.pick 2 = 0 then fst (choose d vars)
  else
    match ty with
    | Int -> choose d int_lits
    | Uint -> choose d uint_lits
    | Double -> choose d double_lits
    | Bool -> choose d [ "true"; "false" ]
    | String -> choose d string_lits
    | Bytes -> choose d bytes_lits
    | List t -> "[" ^ leaf d scope t ^ "]"
    | Map (k, v) -> "{" ^ leaf d scope k ^ ": " ^ leaf d scope v ^ "}"

(* A test case: typed variables with values, an intended type, an expression. *)
type case = { vars : (string * ty * Value.t) list; ty : ty; src : string }

let case d =
  let vars = List.init (d.pick 3) (fun i -> let t = gen_ty d 1 in (Printf.sprintf "v%d" i, t, value d t)) in
  let ty = gen_ty d 2 in
  let src = expr d (List.map (fun (n, t, _) -> (n, t)) vars) ty 4 in
  { vars; ty; src }

let checker_env c =
  List.fold_left (fun env (n, t, _) -> Checker.declare_ident env n (to_checker t)) (Checker.empty ()) c.vars

let bindings c = List.map (fun (n, _, v) -> (n, v)) c.vars
