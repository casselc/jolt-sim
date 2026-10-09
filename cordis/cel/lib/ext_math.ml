(* The math extension library, as cel-go's ext.Math at its latest version:
   math.greatest and math.least (macros over the internal math.@max and
   math.@min), rounding, sign and floating-point tests, square root, and the
   bitwise functions. Messages follow cel-go. *)

open Value

let fail_parse fmt = Printf.ksprintf (fun s -> raise (Parser.Parse_error s)) fmt

(* cel-go's message for arguments no overload accepts: name(int, string). *)
let no_overload name args =
  Error
    (Printf.sprintf "no such overload: %s(%s)" name
       (String.concat ", " (List.map (fun v -> type_name (type_of v)) args)))

(* ---- greatest and least ----------------------------------------------------------- *)

(* The parse-time argument rules of cel-go's macros: a literal must be a
   number, and a list or map literal is never a number; any other
   expression may produce one at run time. *)
let numeric_arg (e : Ast.expr) =
  match e.kind with
  | Ast.Const (Int _ | Uint _ | Double _) -> true
  | Ast.Const _ | Ast.List_lit _ | Ast.Map_lit _ -> false
  | _ -> true

let numeric_list_literal (e : Ast.expr) =
  match e.kind with Ast.List_lit (_ :: _ as es) -> List.for_all numeric_arg es | _ -> false

(* math.greatest(a) -> @max(a); (a, b) -> @max(a, b); (a, b, c...) -> @max([a, b, c...]) *)
let extremum_macro ~macro ~fn : Registry.expander =
 fun ~mk ~target:_ args ->
  let call args = Some (mk (Ast.Call { fn; target = None; args })) in
  let check_all () =
    if not (List.for_all numeric_arg args) then
      fail_parse "%s simple literal arguments must be numeric" macro
  in
  match args with
  | [] -> fail_parse "%s requires at least one argument" macro
  | [ a ] ->
      if numeric_list_literal a || numeric_arg a then call [ a ]
      else fail_parse "%s invalid single argument value" macro
  | [ _; _ ] -> check_all (); call args
  | _ -> check_all (); call [ mk (Ast.List_lit args) ]

(* The pairwise step: [second] replaces [first] only when strictly better,
   so among equal values the first wins (greatest(1, 1u) is 1). *)
let pick ~fn ~better a b =
  if is_numeric a && is_numeric b then
    match compare_numeric a b with
    | None -> Error ("NaN values cannot be ordered: " ^ fn)
    | Some c -> if better c then b else a
  else no_overload fn [ a; b ]

(* Over a list: as in cel-go, a non-numeric first element finds no
   overload, and any failure along the way is reported as no overload. *)
let pick_list ~fn ~better xs =
  if Array.length xs = 0 then Error (fn ^ "(list) argument must not be empty")
  else if not (is_numeric xs.(0)) then Error ("no such overload: " ^ fn ^ "(list)")
  else
    let step acc x = match acc with Error _ -> acc | _ -> pick ~fn ~better acc x in
    match Array.fold_left step xs.(0) xs with Error _ -> Error ("no such overload: " ^ fn) | v -> v

let extremum_function ~fn ~better = function
  | [ v ] when is_numeric v -> Some v
  | [ List xs ] -> Some (pick_list ~fn ~better xs)
  | [ v ] -> Some (no_overload fn [ v ])
  | [ a; b ] -> Some (pick ~fn ~better a b)
  | _ -> None

(* ---- unary and binary functions -------------------------------------------------- *)

(* Register a namespaced function of fixed arity; [f] answers [None] for
   arguments of other types, reported as cel-go reports them. *)
let fixed name arity f =
  Registry.register name (fun args ->
      if List.length args <> arity then None
      else Some (match f args with Some v -> v | None -> no_overload name args))

let on_double name g = fixed name 1 (function [ Double d ] -> Some (g d) | _ -> None)

let abs = function
  | [ Int i ] -> Some (if i = Int64.min_int then Error "integer overflow" else Int (Int64.abs i))
  | [ Uint u ] -> Some (Uint u)
  | [ Double d ] -> Some (Double (Float.abs d))
  | _ -> None

(* NaN stays NaN; zero of either sign gives +0. *)
let sign = function
  | [ Int i ] -> Some (Int (Int64.of_int (Int64.compare i 0L)))
  | [ Uint u ] -> Some (Uint (if u = 0L then 0L else 1L))
  | [ Double d ] ->
      Some (Double (if Float.is_nan d then d else if d > 0.0 then 1.0 else if d < 0.0 then -1.0 else 0.0))
  | _ -> None

let sqrt = function
  | [ Double d ] -> Some (Double (Float.sqrt d))
  | [ Int i ] -> Some (Double (Float.sqrt (Int64.to_float i)))
  | [ Uint u ] -> Some (Double (Float.sqrt (uint_to_float u)))
  | _ -> None

(* Bitwise operations take two ints or two uints. *)
let bitwise op = function
  | [ Int a; Int b ] -> Some (Int (op a b))
  | [ Uint a; Uint b ] -> Some (Uint (op a b))
  | _ -> None

let bit_not = function
  | [ Int a ] -> Some (Int (Int64.lognot a))
  | [ Uint a ] -> Some (Uint (Int64.lognot a))
  | _ -> None

(* Shifts by 64 or more give 0, as in Go; a right shift is logical for ints
   too (cel-go shifts the uint bit pattern). *)
let shift name op = function
  | [ (Int _ | Uint _) as v; Int k ] ->
      if Int64.compare k 0L < 0 then Some (Error (Printf.sprintf "%s() negative offset: %Ld" name k))
      else
        let r x = if Int64.compare k 64L >= 0 then 0L else op x (Int64.to_int k) in
        Some (match v with Int x -> Int (r x) | Uint x -> Uint (r x) | _ -> assert false)
  | _ -> None

(* Signatures, as cel-go's ext.Math declares them; math.greatest and
   math.least expand to math.@max and math.@min with one argument, two, or
   a list of them all. *)
let declare () =
  let f params result = (false, params, result) in
  let d = Registry.declare in
  let nums = [ "int"; "uint"; "double" ] in
  let extremum =
    List.map (fun t -> f [ t ] t) nums
    @ List.concat_map (fun a -> List.map (fun b -> f [ a; b ] (if a = b then a else "dyn")) nums) nums
    @ List.map (fun t -> f [ "list(" ^ t ^ ")" ] t) nums
  in
  d "math.@max" extremum;
  d "math.@min" extremum;
  List.iter (fun n -> d n [ f [ "double" ] "double" ]) [ "math.ceil"; "math.floor"; "math.round"; "math.trunc" ];
  List.iter (fun n -> d n [ f [ "double" ] "bool" ]) [ "math.isInf"; "math.isNaN"; "math.isFinite" ];
  d "math.abs" (List.map (fun t -> f [ t ] t) nums);
  d "math.sign" (List.map (fun t -> f [ t ] t) nums);
  d "math.sqrt" (List.map (fun t -> f [ t ] "double") nums);
  List.iter (fun n -> d n [ f [ "int"; "int" ] "int"; f [ "uint"; "uint" ] "uint" ]) [ "math.bitAnd"; "math.bitOr"; "math.bitXor" ];
  d "math.bitNot" [ f [ "int" ] "int"; f [ "uint" ] "uint" ];
  List.iter (fun n -> d n [ f [ "int"; "int" ] "int"; f [ "uint"; "int" ] "uint" ]) [ "math.bitShiftLeft"; "math.bitShiftRight" ]

let register () =
  declare ();
  Registry.register_macro "math.greatest" (extremum_macro ~macro:"math.greatest()" ~fn:"math.@max");
  Registry.register_macro "math.least" (extremum_macro ~macro:"math.least()" ~fn:"math.@min");
  Registry.register "math.@max" (extremum_function ~fn:"math.@max" ~better:(fun c -> c < 0));
  Registry.register "math.@min" (extremum_function ~fn:"math.@min" ~better:(fun c -> c > 0));
  on_double "math.ceil" (fun d -> Double (Float.ceil d));
  on_double "math.floor" (fun d -> Double (Float.floor d));
  (* Float.round, like Go's math.Round, rounds half away from zero *)
  on_double "math.round" (fun d -> Double (Float.round d));
  on_double "math.trunc" (fun d -> Double (Float.trunc d));
  on_double "math.isInf" (fun d -> Bool (Float.abs d = Float.infinity));
  on_double "math.isNaN" (fun d -> Bool (Float.is_nan d));
  on_double "math.isFinite" (fun d -> Bool (Float.is_finite d));
  fixed "math.abs" 1 abs;
  fixed "math.sign" 1 sign;
  fixed "math.sqrt" 1 sqrt;
  fixed "math.bitAnd" 2 (bitwise Int64.logand);
  fixed "math.bitOr" 2 (bitwise Int64.logor);
  fixed "math.bitXor" 2 (bitwise Int64.logxor);
  fixed "math.bitNot" 1 bit_not;
  fixed "math.bitShiftLeft" 2 (shift "math.bitShiftLeft" Int64.shift_left);
  fixed "math.bitShiftRight" 2 (shift "math.bitShiftRight" Int64.shift_right_logical)
