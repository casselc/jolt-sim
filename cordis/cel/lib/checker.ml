(* The CEL type checker (docs/cordis/rfc/0001-cel.md, step 3).

   Types are inferred bottom-up with type variables and a substitution.
   Two relations do the work:

   - assignable p a: may an argument of type a be passed where p is
     expected? It unifies type variables as it goes. dyn is compatible with
     everything; a wrapper type and its primitive are interchangeable; null
     fits any nullable type. A type parameter already bound takes the more
     general of its binding and a compatible argument (cel-go's flexible
     type parameter assignment); an incompatible argument does not fit.
   - join a b: the most general type of two, structural (type(int) and
     type(bool) join to type(dyn), not dyn); anything else that disagrees
     is dyn. List and map literals use cel-go's aggregate join instead: the
     more general of two compatible types, else dyn, so {1: {1: 0}, 1: {1:
     []}} is map(int, dyn).

   Function signatures are written in the same text form as conformance
   declarations ("list(A)", "map(K, V)", "type(T)"); single capital letters
   are type parameters, instantiated fresh at each call. *)

type t =
  | Dyn
  | Null
  | Bool
  | Int
  | Uint
  | Double
  | String
  | Bytes
  | Timestamp
  | Duration
  | List of t
  | Map of t * t
  | Type of t
  | Wrapper of t
  | Opt of t
  | Message of string
  | Opaque of string * t list  (** tuple(T, U, V), net.IP ... *)
  | Variant of (string * t option) list
      (** variant(Pass(T), Fail(U), Skipped): tagged cases, each with an
          optional payload. A value is a map {'tag': 'Pass', 'value': x}. *)
  | Record of (string * t) list * bool
      (** the graph language's records (RFC 0002): fields, and whether the
          record is open (at least these fields). A value is a map with a
          string key per field. *)
  | Param of string  (** a type parameter in a signature *)
  | Var of int  (** a type variable during inference *)

exception Check_error of string

let fail fmt = Printf.ksprintf (fun s -> raise (Check_error s)) fmt

(* ---- printing and parsing the text form ------------------------------------------- *)

let rec to_string = function
  | Dyn -> "dyn"
  | Null -> "null_type"
  | Bool -> "bool"
  | Int -> "int"
  | Uint -> "uint"
  | Double -> "double"
  | String -> "string"
  | Bytes -> "bytes"
  | Timestamp -> "google.protobuf.Timestamp"
  | Duration -> "google.protobuf.Duration"
  | List t -> "list(" ^ to_string t ^ ")"
  | Map (k, v) -> "map(" ^ to_string k ^ ", " ^ to_string v ^ ")"
  | Type t -> "type(" ^ to_string t ^ ")"
  | Wrapper t -> "wrapper(" ^ to_string t ^ ")"
  | Opt t -> "optional_type(" ^ to_string t ^ ")"
  | Message n -> n
  | Opaque (n, []) -> n
  | Opaque (n, ps) -> n ^ "(" ^ String.concat ", " (List.map to_string ps) ^ ")"
  | Record (fs, open_) ->
      "{" ^ String.concat ", " (List.map (fun (f, t) -> f ^ ": " ^ to_string t) fs @ if open_ then [ ".." ] else []) ^ "}"
  | Variant cs ->
      "variant("
      ^ String.concat ", " (List.map (fun (n, t) -> match t with None -> n | Some t -> n ^ "(" ^ to_string t ^ ")") cs)
      ^ ")"
  | Param p -> p
  | Var i -> Printf.sprintf "_var%d" i

let is_param name = String.length name >= 1 && name.[0] >= 'A' && name.[0] <= 'Z' && not (String.contains name '.')

(* Set below to [message_type], which maps well-known types to their CEL types. *)
let message_type_ref : (string -> t) ref = ref (fun n -> Message n)

(* "map(K, list(int))" -> Map (Param "K", List Int) *)
let parse_type (s : string) : t =
  let n = String.length s and i = ref 0 in
  let skip () = while !i < n && s.[!i] = ' ' do incr i done in
  let rec ty () =
    skip ();
    let st = !i in
    while !i < n && s.[!i] <> '(' && s.[!i] <> ')' && s.[!i] <> ',' && s.[!i] <> ' ' do incr i done;
    let name = String.sub s st (!i - st) in
    skip ();
    let args =
      if !i < n && s.[!i] = '(' then begin
        incr i;
        let rec go acc =
          let a = ty () in
          skip ();
          if !i < n && s.[!i] = ',' then (incr i; go (a :: acc))
          else (if !i < n && s.[!i] = ')' then incr i; List.rev (a :: acc))
        in
        go []
      end
      else []
    in
    match name, args with
    | "dyn", [] | "any", [] -> Dyn
    | "null_type", [] -> Null
    | "bool", [] -> Bool
    | "int", [] -> Int
    | "uint", [] -> Uint
    | "double", [] -> Double
    | "string", [] -> String
    | "bytes", [] -> Bytes
    | ("timestamp" | "google.protobuf.Timestamp"), [] -> Timestamp
    | ("duration" | "google.protobuf.Duration"), [] -> Duration
    | "list", [ t ] -> List t
    | "list", [] -> List Dyn
    | "map", [ k; v ] -> Map (k, v)
    | "map", [] -> Map (Dyn, Dyn)
    | "type", [ t ] -> Type t
    | "type", [] -> Type Dyn
    | "wrapper", [ t ] -> Wrapper t
    | "optional_type", [ t ] -> Opt t
    | "optional_type", [] -> Opt Dyn
    | n, [] when is_param n -> Param n
    | n, [] when Proto.find_message n <> None -> !message_type_ref n
    | "variant", cases ->
        Variant
          (List.map
             (function
               | Opaque (n, [ t ]) -> (n, Some t)
               | Opaque (n, []) | Param n | Message n -> (n, None)
               | t -> raise (Invalid_argument ("variant case " ^ to_string t)))
             cases)
    | n, ps -> Opaque (n, ps)
  in
  ty ()

(* ---- substitution -------------------------------------------------------------------- *)

type env = {
  idents : (string * t) list;
  functions : (string * (bool * t list * t) list) list;  (** name -> (receiver, params, result) *)
  container : string;
  subst : (int, t) Hashtbl.t;
  trail : (int * t option) list ref;
      (** every change to [subst], newest first, with the binding it replaced: an overload that does not
          fit is undone back to a mark, at the cost of what it changed, not of the whole table *)
  mutable next : int;
  types : (int, t) Hashtbl.t;  (** expression id -> inferred type, for the cost estimator *)
  named : (string * t) list;  (** the graph language's declared records and variants, by name *)
}

let fresh env =
  env.next <- env.next + 1;
  Var env.next

let rec resolve env t =
  match t with
  | Var i -> ( match Hashtbl.find_opt env.subst i with Some u -> resolve env u | None -> t)
  | _ -> t

(* The type with every bound variable substituted. *)
let rec substitute env t =
  match resolve env t with
  | List t -> List (substitute env t)
  | Map (k, v) -> Map (substitute env k, substitute env v)
  | Type t -> Type (substitute env t)
  | Wrapper t -> Wrapper (substitute env t)
  | Opt t -> Opt (substitute env t)
  | Opaque (n, ps) -> Opaque (n, List.map (substitute env) ps)
  | Variant cs -> Variant (List.map (fun (n, t) -> (n, Option.map (substitute env) t)) cs)
  | Record (fs, o) -> Record (List.map (fun (f, t) -> (f, substitute env t)) fs, o)
  | t -> t

(* Unbound variables become dyn when a type is reported. *)
let rec finalize env t =
  match substitute env t with
  | Var _ | Param _ -> Dyn
  | List t -> List (finalize env t)
  | Map (k, v) -> Map (finalize env k, finalize env v)
  | Type t -> Type (finalize env t)
  | Wrapper t -> Wrapper (finalize env t)
  | Opt t -> Opt (finalize env t)
  | Opaque (n, ps) -> Opaque (n, List.map (finalize env) ps)
  | Variant cs -> Variant (List.map (fun (n, t) -> (n, Option.map (finalize env) t)) cs)
  | Record (fs, o) -> Record (List.map (fun (f, t) -> (f, finalize env t)) fs, o)
  | t -> t

let rec occurs env i t =
  match resolve env t with
  | Var j -> i = j
  | List t | Type t | Wrapper t | Opt t -> occurs env i t
  | Map (k, v) -> occurs env i k || occurs env i v
  | Opaque (_, ps) -> List.exists (occurs env i) ps
  | Variant cs -> List.exists (fun (_, t) -> match t with Some t -> occurs env i t | None -> false) cs
  | Record (fs, _) -> List.exists (fun (_, t) -> occurs env i t) fs
  | _ -> false

let set env i t =
  env.trail := (i, Hashtbl.find_opt env.subst i) :: !(env.trail);
  Hashtbl.replace env.subst i t

(* Undo every change made since [mark] (a former value of the trail). *)
let undo_to env mark =
  let rec go l =
    if l != mark then
      match l with
      | (i, old) :: rest ->
          (match old with Some u -> Hashtbl.replace env.subst i u | None -> Hashtbl.remove env.subst i);
          go rest
      | [] -> ()
  in
  go !(env.trail);
  env.trail := mark

let reset_subst env =
  Hashtbl.reset env.subst;
  env.trail := []

let bind env i t = if not (occurs env i t) then set env i t

let is_primitive = function Bool | Int | Uint | Double | String | Bytes -> true | _ -> false
let nullable = function Wrapper _ | Message _ | Timestamp | Duration | Opt _ | Null | Dyn -> true | _ -> false

(* ---- join and assignability ------------------------------------------------------------ *)

let rec join env a b =
  match resolve env a, resolve env b with
  | Var i, Var j when i = j -> Var i
  | Var i, t | t, Var i -> bind env i t; t
  | Dyn, _ | _, Dyn -> Dyn
  | Null, t when nullable t -> t
  | t, Null when nullable t -> t
  | (Wrapper p as w), q when resolve env p = q -> w
  | q, (Wrapper p as w) when resolve env p = q -> w
  | List x, List y -> List (join env x y)
  | Map (k1, v1), Map (k2, v2) -> Map (join env k1 k2, join env v1 v2)
  | Type x, Type y -> Type (join env x y)
  | Opt x, Opt y -> Opt (join env x y)
  | Opaque (n, ps), Opaque (m, qs) when n = m && List.length ps = List.length qs ->
      Opaque (n, List.map2 (join env) ps qs)
  | x, y -> if x = y then x else Dyn

(* Is a value of type [a] acceptable where [p] is expected? Binds variables. *)
let rec assignable env p a =
  match resolve env p, resolve env a with
  (* variables bind first, even to dyn, so dyn(x) makes a parameter dyn *)
  | Var i, Var j when i = j -> true
  | Var i, t -> bind env i t; true
  | t, Var i -> bind env i t; true
  | Dyn, _ | _, Dyn -> true
  | Wrapper x, (Null | Wrapper _ as w) -> (match w with Null -> true | Wrapper y -> assignable env x y | _ -> false)
  | Wrapper x, y when is_primitive y -> assignable env x y
  | x, Wrapper y when is_primitive x -> assignable env x y
  | (Message _ | Timestamp | Duration | Opt _), Null -> true
  | List x, List y | Opt x, Opt y -> assignable env x y
  (* any type value fits type(T), as in cel-go: type(1) == type(1u) is bool *)
  | Type x, Type y -> ignore (assignable env x y); true
  | Map (k1, v1), Map (k2, v2) -> assignable env k1 k2 && assignable env v1 v2
  | Opaque (n, ps), Opaque (m, qs) -> n = m && List.length ps = List.length qs && List.for_all2 (assignable env) ps qs
  (* a variant built in CEL is a map {'tag': ..., 'value': ...}: its type
     says string keys and dyn (or, for unit cases only, string) values, and
     its tag is checked when matched *)
  | Variant _, Map (k, v) -> assignable env String k && (match resolve env v with Dyn | String | Var _ -> true | _ -> false)
  (* width subtyping at boundaries: an open record accepts any record with
     at least its fields; a closed one exactly its fields *)
  | Record (ps, open_), Record (fs, _) ->
      (open_ || List.length ps = List.length fs)
      && List.for_all (fun (f, t) -> match List.assoc_opt f fs with Some u -> assignable env t u | None -> false) ps
  | Variant cs, Variant ds ->
      (* the same cases, by name, with assignable payloads *)
      List.length cs = List.length ds
      && List.for_all
           (fun (n, t) ->
             match t, List.assoc_opt n ds with
             | None, Some None -> true
             | Some t, Some (Some u) -> assignable env t u
             | _ -> false)
           cs
  | x, y -> x = y

(* Instantiate a signature's type parameters with fresh variables. *)
let instantiate env (params, result) =
  let tbl = Hashtbl.create 4 in
  let rec go = function
    | Param p -> (match Hashtbl.find_opt tbl p with Some v -> v | None -> let v = fresh env in Hashtbl.add tbl p v; v)
    | List t -> List (go t)
    | Map (k, v) -> Map (go k, go v)
    | Type t -> Type (go t)
    | Wrapper t -> Wrapper (go t)
    | Opt t -> Opt (go t)
    | Opaque (n, ps) -> Opaque (n, List.map go ps)
    | Variant cs -> Variant (List.map (fun (n, t) -> (n, Option.map go t)) cs)
    | Record (fs, o) -> Record (List.map (fun (f, t) -> (f, go t)) fs, o)
    | t -> t
  in
  (List.map go params, go result)

(* The more general of two compatible types: dyn wins, structurally. *)
let rec most_general env a b =
  match resolve env a, resolve env b with
  | Dyn, _ | _, Dyn -> Dyn
  (* a wrapper is more general than its primitive, and a nullable type
     than null: [msg.single_int64, msg.single_int64_wrapper] is
     list(wrapper(int)) *)
  | x, (Wrapper _ as w) when is_primitive x -> w
  | Null, t when nullable t -> t
  | List x, List y -> List (most_general env x y)
  (* differing types of types widen to type(dyn), as cel-spec's
     type_free_param_list_concat expects *)
  | Type x, Type y -> Type (join env x y)
  | Opt x, Opt y -> Opt (most_general env x y)
  | Map (k1, v1), Map (k2, v2) -> Map (most_general env k1 k2, most_general env v1 v2)
  | Opaque (n, ps), Opaque (m, qs) when n = m && List.length ps = List.length qs ->
      Opaque (n, List.map2 (most_general env) ps qs)
  | x, _ -> x

(* Flexible assignment of an argument to a parameter (cel-go's
   isValidTypeSubstitution): when a variable the parameter mentions is
   already bound and the argument fits the binding, the binding becomes the
   more general of the two, so [1] + [dyn(2)] is list(dyn). An argument that
   does not fit is a mismatch, not a widening: [] == 0 has no overload. *)
let rec flexible env p a =
  match p with
  | Var i when Hashtbl.mem env.subst i ->
      let bound = Hashtbl.find env.subst i in
      (match bound with
       | Var _ -> assignable env p a
       | _ ->
           assignable env bound a
           && (set env i (most_general env bound a); true))
  | List x -> (match resolve env a with List y -> flexible env x y | _ -> assignable env p a)
  | Type x -> (match resolve env a with Type y -> flexible env x y | _ -> assignable env p a)
  | Opt x -> (match resolve env a with Opt y -> flexible env x y | _ -> assignable env p a)
  | Map (k, v) -> (match resolve env a with Map (k', v') -> flexible env k k' && flexible env v v' | _ -> assignable env p a)
  | Opaque (n, ps) -> (
      match resolve env a with
      | Opaque (m, qs) when n = m && List.length ps = List.length qs -> List.for_all2 (flexible env) ps qs
      | _ -> assignable env p a)
  | _ -> assignable env p a

(* ---- the standard library's signatures ---------------------------------------------------- *)

let sig_ ?(receiver = false) params result = (receiver, List.map parse_type params, parse_type result)

let standard : (string * (bool * t list * t) list) list Lazy.t =
  lazy
    (let cmp_pairs =
       List.map (fun t -> [ t; t ]) [ "int"; "uint"; "double"; "string"; "bytes"; "bool"; "timestamp"; "duration" ]
       @ [ [ "int"; "uint" ]; [ "int"; "double" ]; [ "uint"; "int" ]; [ "uint"; "double" ]; [ "double"; "int" ]; [ "double"; "uint" ] ]
     in
     let cmp = List.map (fun ps -> sig_ ps "bool") cmp_pairs in
     let arith ts = List.map (fun t -> sig_ [ t; t ] t) ts in
     (* Overloads are listed in cel-go's declaration order: when an argument's
        type is still a variable, the first overload that fits decides the
        result (-([][0]) is double), as in cel-go's resolveOverload. *)
     let time_parts = [ "getFullYear"; "getMonth"; "getDate"; "getDayOfMonth"; "getDayOfYear"; "getDayOfWeek"; "getHours"; "getMinutes"; "getSeconds"; "getMilliseconds" ] in
     [ ("!_", [ sig_ [ "bool" ] "bool" ]);
       ("-_", [ sig_ [ "double" ] "double"; sig_ [ "int" ] "int" ]);
       ("_&&_", [ sig_ [ "bool"; "bool" ] "bool" ]);
       ("_||_", [ sig_ [ "bool"; "bool" ] "bool" ]);
       ("@not_strictly_false", [ sig_ [ "bool" ] "bool" ]);
       ("_==_", [ sig_ [ "A"; "A" ] "bool" ]);
       ("_!=_", [ sig_ [ "A"; "A" ] "bool" ]);
       ("_<_", cmp); ("_<=_", cmp); ("_>_", cmp); ("_>=_", cmp);
       ( "_+_",
         arith [ "bytes"; "double"; "duration" ]
         @ [ sig_ [ "duration"; "timestamp" ] "timestamp"; sig_ [ "timestamp"; "duration" ] "timestamp" ]
         @ arith [ "int" ] @ [ sig_ [ "list(A)"; "list(A)" ] "list(A)" ] @ arith [ "string"; "uint" ] );
       ( "_-_",
         arith [ "double"; "duration"; "int" ]
         @ [ sig_ [ "timestamp"; "duration" ] "timestamp"; sig_ [ "timestamp"; "timestamp" ] "duration" ]
         @ arith [ "uint" ] );
       ("_*_", arith [ "double"; "int"; "uint" ]); ("_/_", arith [ "double"; "int"; "uint" ]);
       ("_%_", arith [ "int"; "uint" ]);
       ( "_[_]",
         [ sig_ [ "list(A)"; "int" ] "A"; sig_ [ "map(K, V)"; "K" ] "V";
           (* indexing an optional chains *)
           sig_ [ "optional_type(list(A))"; "int" ] "optional_type(A)";
           sig_ [ "optional_type(map(K, V))"; "K" ] "optional_type(V)" ] );
       ( "_[?_]",
         [ sig_ [ "list(A)"; "int" ] "optional_type(A)"; sig_ [ "map(K, V)"; "K" ] "optional_type(V)";
           sig_ [ "optional_type(list(A))"; "int" ] "optional_type(A)";
           sig_ [ "optional_type(map(K, V))"; "K" ] "optional_type(V)" ] );
       (* internal functions the two-variable macros expand to *)
       ("@map_insert", [ sig_ [ "map(K, V)"; "K"; "V" ] "map(K, V)" ]);
       ("@map_merge", [ sig_ [ "map(K, V)"; "map(K, V)" ] "map(K, V)" ]);
       ("@in", [ sig_ [ "A"; "list(A)" ] "bool"; sig_ [ "K"; "map(K, V)" ] "bool" ]);
       ( "size",
         List.concat_map
           (fun t -> [ sig_ [ t ] "int"; sig_ ~receiver:true [ t ] "int" ])
           [ "string"; "bytes"; "list(A)"; "map(K, V)" ] );
       ("contains", [ sig_ ~receiver:true [ "string"; "string" ] "bool" ]);
       ("startsWith", [ sig_ ~receiver:true [ "string"; "string" ] "bool" ]);
       ("endsWith", [ sig_ ~receiver:true [ "string"; "string" ] "bool" ]);
       ("matches", [ sig_ [ "string"; "string" ] "bool"; sig_ ~receiver:true [ "string"; "string" ] "bool" ]);
       ("int", List.map (fun t -> sig_ [ t ] "int") [ "int"; "double"; "duration"; "string"; "timestamp"; "uint" ]);
       ("uint", List.map (fun t -> sig_ [ t ] "uint") [ "uint"; "double"; "int"; "string" ]);
       ("double", List.map (fun t -> sig_ [ t ] "double") [ "double"; "int"; "string"; "uint" ]);
       ("string", List.map (fun t -> sig_ [ t ] "string") [ "string"; "bool"; "bytes"; "double"; "duration"; "int"; "timestamp"; "uint" ]);
       ("bytes", List.map (fun t -> sig_ [ t ] "bytes") [ "bytes"; "string" ]);
       ("bool", List.map (fun t -> sig_ [ t ] "bool") [ "bool"; "string" ]);
       ("dyn", [ sig_ [ "A" ] "dyn" ]);
       ("type", [ sig_ [ "A" ] "type(A)" ]);
       ("timestamp", List.map (fun t -> sig_ [ t ] "timestamp") [ "timestamp"; "int"; "string" ]);
       ("duration", List.map (fun t -> sig_ [ t ] "duration") [ "duration"; "string" ]);
       ("optional.of", [ sig_ [ "A" ] "optional_type(A)" ]);
       ("optional.ofNonZeroValue", [ sig_ [ "A" ] "optional_type(A)" ]);
       ("optional.none", [ sig_ [] "optional_type(A)" ]);
       ("hasValue", [ sig_ ~receiver:true [ "optional_type(A)" ] "bool" ]);
       ("value", [ sig_ ~receiver:true [ "optional_type(A)" ] "A" ]);
       ("or", [ sig_ ~receiver:true [ "optional_type(A)"; "optional_type(A)" ] "optional_type(A)" ]);
       ("orValue", [ sig_ ~receiver:true [ "optional_type(A)"; "A" ] "A" ]) ]
     @ List.map
         (fun p ->
           ( p,
             [ sig_ ~receiver:true [ "timestamp" ] "int"; sig_ ~receiver:true [ "timestamp"; "string" ] "int" ]
             @ (if List.mem p [ "getHours"; "getMinutes"; "getSeconds"; "getMilliseconds" ] then [ sig_ ~receiver:true [ "duration" ] "int" ] else []) ))
         time_parts)

(* ---- declarations ------------------------------------------------------------------------ *)

let empty ?(container = "") () =
  { idents = []; functions = []; container; subst = Hashtbl.create 16; trail = ref []; next = 0; types = Hashtbl.create 64; named = [] }

let declare_ident env name ty = { env with idents = (name, ty) :: env.idents }

let declare_function env name overloads = { env with functions = (name, overloads) :: env.functions }

(* A named record or variant type: Name{f: e} builds a record, and
   Name.Case(x) or Name.Case a variant. *)
let declare_type env name ty = { env with named = (name, ty) :: env.named }

(* ---- types of protobuf fields ---------------------------------------------------------------- *)

let rec field_type (f : Proto.field) =
  match f.map with
  | Some (k, v) -> Map (element_type k, element_type v)
  | None -> if f.repeated then List (element_type f) else element_type f

(* Enum values are ints, or typed values under strong enums. *)
and enum_type name = if Proto.strong_enums () then Opaque (name, []) else Int

and element_type (f : Proto.field) =
  match f.ty, f.type_name with
  | "enum", Some t -> enum_type t
  | ("int32" | "int64" | "sint32" | "sint64" | "sfixed32" | "sfixed64" | "enum"), _ -> Int
  | ("uint32" | "uint64" | "fixed32" | "fixed64"), _ -> Uint
  | ("float" | "double"), _ -> Double
  | "bool", _ -> Bool
  | "string", _ -> String
  | "bytes", _ -> Bytes
  | "message", Some t -> message_type t
  | _ -> Dyn

and message_type t =
  match t with
  | "google.protobuf.Timestamp" -> Timestamp
  | "google.protobuf.Duration" -> Duration
  | "google.protobuf.Value" | "google.protobuf.Any" -> Dyn
  | "google.protobuf.Struct" -> Map (String, Dyn)
  | "google.protobuf.ListValue" -> List Dyn
  | _ -> (
      match Proto.wrapper_scalar t with
      | Some ("int32" | "int64") -> Wrapper Int
      | Some ("uint32" | "uint64") -> Wrapper Uint
      | Some ("float" | "double") -> Wrapper Double
      | Some "bool" -> Wrapper Bool
      | Some "string" -> Wrapper String
      | Some "bytes" -> Wrapper Bytes
      | _ -> Message t)

let () = message_type_ref := message_type

(* ---- inference ---------------------------------------------------------------------------------- *)

let candidates env name = Eval.candidates env.container name

let rec ident_path (e : Ast.expr) =
  match e.kind with
  | Ast.Ident n -> Some [ n ]
  | Ast.Select { operand; field; test_only = false } -> Option.map (fun p -> p @ [ field ]) (ident_path operand)
  | _ -> None

let lookup_ident env locals name =
  match List.assoc_opt name locals with
  | Some t -> Some t
  | None ->
      List.find_map
        (fun n ->
          match List.assoc_opt n env.idents with
          | Some t -> Some t
          | None -> (
              match List.assoc_opt n Eval.type_idents with
              | Some ty -> Some (Type (parse_type (Value.type_name ty)))
              | None -> (
                  (* a type an extension library registered (net.IP), as the evaluator resolves it *)
                  match Registry.find_type n with
                  | Some ty -> Some (Type (parse_type (Value.type_name ty)))
                  | None -> (
                  match Proto.find_message n with
                  | Some _ -> Some (Type (message_type n))
                  | None -> (
                      (* an enum constant's type is its enum's (int unless strong) *)
                      match Proto.enum_constant n with
                      | Some _ -> Some (enum_type (String.sub n 0 (String.rindex n '.')))
                      | None -> None)))))
        (candidates env name)

let overloads env name =
  (match List.assoc_opt name env.functions with Some o -> o | None -> [])
  @ (match List.assoc_opt name (Lazy.force standard) with Some o -> o | None -> [])
  @ List.map (fun (r, ps, res) -> sig_ ~receiver:r ps res) (Registry.find_signatures name)

(* Resolve a call as cel-go's resolveOverload does: try the overloads in
   order, keeping the bindings of each that fits. The first fitting overload
   decides the result; a later fit with a different result makes it dyn
   (several overloads fit dyn arguments). *)
let resolve_call env name ~receiver args =
  let candidates =
    List.filter (fun (r, ps, _) -> r = receiver && List.length ps = List.length args) (overloads env name)
  in
  let result =
    List.fold_left
      (fun result o ->
        let saved = !(env.trail) in
        let ps, res = instantiate env (let _, ps, res = o in (ps, res)) in
        if List.for_all2 (flexible env) ps args then
          let r = substitute env res in
          match result with
          | None -> Some r
          | Some Dyn -> result
          | Some r0 -> if r0 = r then result else Some Dyn
        else (undo_to env saved; result))
      None candidates
  in
  match result with
  | Some r -> r
  | None ->
      fail "found no matching overload for '%s' applied to (%s)" name
        (String.concat ", " (List.map (fun a -> to_string (finalize env a)) args))

(* The enum type a call names (GlobalEnum(...), TestAllTypes.NestedEnum(...)),
   resolved in the container. *)
let enum_called env target fn =
  let name =
    match target with
    | None -> Some fn
    | Some t -> Option.map (fun p -> String.concat "." (p @ [ fn ])) (ident_path t)
  in
  Option.bind name (fun n -> List.find_opt Proto.enum_exists (candidates env n))

(* cel-go's joinTypes for the elements of a list or map literal. *)
let aggregate_join env prev cur = if assignable env prev cur then most_general env prev cur else Dyn

(* Every inferred type is recorded against its expression id. A node a
   macro expansion shares between two contexts records dyn if its types
   differ. *)
let rec infer env locals (e : Ast.expr) : t =
  let t = infer_kind env locals e in
  (match Hashtbl.find_opt env.types e.id with
   | Some t' when t' <> t -> Hashtbl.replace env.types e.id Dyn
   | _ -> Hashtbl.replace env.types e.id t);
  t

and infer_kind env locals (e : Ast.expr) : t =
  match e.kind with
  | Ast.Const v -> (
      match v with
      | Value.Null -> Null | Value.Bool _ -> Bool | Value.Int _ -> Int | Value.Uint _ -> Uint
      | Value.Double _ -> Double | Value.String _ -> String | Value.Bytes _ -> Bytes
      | _ -> Dyn)
  | Ast.Ident n -> (
      match lookup_ident env locals n with Some t -> t | None -> fail "undeclared reference to '%s'" n)
  | Ast.Select { operand = { kind = Ast.Ident n; _ }; field; test_only = false }
    when (not (List.mem_assoc n locals)) && (match List.assoc_opt n env.named with Some (Variant _) -> true | _ -> false) -> (
      match List.assoc_opt n env.named with
      | Some (Variant cs as vt) -> (
          match List.assoc_opt field cs with
          | Some None -> vt
          | Some (Some _) -> fail "case %s.%s carries a value: %s.%s(x)" n field n field
          | None -> fail "no case '%s' in %s" field n)
      | _ -> assert false)
  | Ast.Call { fn; target = Some { kind = Ast.Ident n; _ }; args }
    when (not (List.mem_assoc n locals)) && (match List.assoc_opt n env.named with Some (Variant _) -> true | _ -> false) -> (
      match List.assoc_opt n env.named, args with
      | Some (Variant cs as vt), [ x ] -> (
          match List.assoc_opt fn cs with
          | Some (Some pt) ->
              let at = infer env locals x in
              if not (assignable env pt at) then
                fail "case %s.%s takes '%s', not '%s'" n fn (to_string pt) (to_string (finalize env at));
              vt
          | Some None -> fail "case %s.%s carries no value" n fn
          | None -> fail "no case '%s' in %s" fn n)
      | _ -> fail "a variant case takes one argument")
  | Ast.Select { operand; field; test_only } -> (
      (* a qualified name (a.b.c) resolves as a whole first *)
      let qualified =
        match ident_path e with
        | Some (root :: _ as p) when (not test_only) && not (List.mem_assoc root locals) ->
            lookup_ident env locals (String.concat "." p)
        | _ -> None
      in
      match qualified with
      | Some t -> t
      | None ->
          let ot = infer env locals operand in
          let ft = select_type env ot field in
          if test_only then Bool else ft)
  | Ast.List_lit es ->
      let elem = List.fold_left (fun acc e -> aggregate_join env acc (element env locals e)) (fresh env) es in
      List elem
  | Ast.Map_lit kvs ->
      let k, v =
        List.fold_left
          (fun (k, v) ((ke : Ast.expr), ve) ->
            let optional, ke = match ke.kind with Ast.Call { fn; args = [ x ]; _ } when fn = Ast.op_opt_elem -> (true, x) | _ -> (false, ke) in
            let vt = infer env locals ve in
            (* {?k: o} contributes the optional's content *)
            let vt = if optional then (match resolve env vt with Opt t -> t | t -> t) else vt in
            (aggregate_join env k (infer env locals ke), aggregate_join env v vt))
          (fresh env, fresh env) kvs
      in
      Map (k, v)
  | Ast.Struct_lit { tname; fields } when (match List.assoc_opt tname env.named with Some (Record _) -> true | _ -> false) -> (
      match List.assoc_opt tname env.named with
      | Some (Record (fs, _) as rt) ->
          List.iter
            (fun (f, fe, optional) ->
              if optional then fail "a record field cannot be optional ('?%s')" f;
              match List.assoc_opt f fs with
              | None -> fail "record %s has no field '%s'" tname f
              | Some ft ->
                  let at = infer env locals fe in
                  if not (assignable env ft at) then
                    fail "expected type of field '%s' is '%s' but provided type is '%s'" f (to_string ft) (to_string (finalize env at)))
            fields;
          (match List.find_opt (fun (f, _) -> not (List.exists (fun (g, _, _) -> g = f) fields)) fs with
           | Some (f, _) -> fail "record %s needs field '%s'" tname f
           | None -> ());
          let given = List.map (fun (g, _, _) -> g) fields in
          List.iter (fun g -> if List.length (List.filter (( = ) g) given) > 1 then fail "field '%s' given twice" g) given;
          rt
      | _ -> assert false)
  | Ast.Struct_lit { tname; fields } -> (
      match List.find_opt (fun n -> Proto.find_message n <> None) (candidates env tname) with
      | None -> fail "undeclared reference to '%s'" tname
      | Some full ->
          let m = Option.get (Proto.find_message full) in
          List.iter
            (fun (f, fe, optional) ->
              match Proto.find_field m f with
              | None -> fail "undefined field '%s'" f
              | Some fd ->
                  let ft = field_type fd and at = infer env locals fe in
                  let at = if optional then (match resolve env at with Opt x -> x | x -> x) else at in
                  if not (assignable env ft at) then
                    fail "expected type of field '%s' is '%s' but provided type is '%s'" f (to_string ft) (to_string (finalize env at)))
            fields;
          message_type full)
  | Ast.Call { fn; target = None; args = [ c; t; f ] } when fn = Ast.op_cond ->
      let ct = infer env locals c in
      if not (assignable env Bool ct) then fail "conditional must be bool";
      (* the overload (bool, A, A) -> A, as cel-go declares it: the branches
         must be compatible, so true ? [] : 0 is rejected *)
      let tt = infer env locals t and ft = infer env locals f and a = fresh env in
      if flexible env a tt && flexible env a ft then a
      else
        fail "found no matching overload for '_?_:_' applied to (bool, %s, %s)" (to_string (finalize env tt))
          (to_string (finalize env ft))
  (* an inlined library call (library.ml): its arguments, then its body *)
  | Ast.Call { fn = "@strict"; target = None; args } when args <> [] ->
      List.fold_left (fun _ a -> infer env locals a) Dyn args
  (* a match's expansion (parser.ml): the scrutinee's type names its cases *)
  | Ast.Call { fn = ("@match_is" | "@match_value") as fn; target = None; args = [ m; { kind = Ast.Const (Value.String tag); _ } ] } -> (
      let mt = infer env locals m in
      match match_cases env mt with
      | None -> if fn = "@match_is" then Bool else Dyn
      | Some cases -> (
          match List.assoc_opt tag cases with
          | None ->
              fail "no case '%s' in %s" tag (to_string (finalize env mt))
          | Some payload ->
              if fn = "@match_is" then Bool
              else match payload with Some t -> t | None -> fail "case '%s' carries no value" tag))
  | Ast.Call { fn = "@match_fail"; target = None; args = [ m; { kind = Ast.List_lit tags; _ } ] } -> (
      let mt = infer env locals m in
      let covered = List.filter_map (fun (t : Ast.expr) -> match t.kind with Ast.Const (Value.String s) -> Some s | _ -> None) tags in
      match match_cases env mt with
      | Some cases -> (
          match List.filter (fun (n, _) -> not (List.mem n covered)) cases with
          | [] -> fresh env
          | missing ->
              fail "match is not exhaustive: no case for %s" (String.concat ", " (List.map fst missing)))
      | None -> fresh env)
  | Ast.Call { fn; target = None; args = [ x; field ] } when fn = Ast.op_opt_select ->
      let xt = infer env locals x in
      let name = match field.kind with Ast.Const (Value.String s) -> s | _ -> "" in
      let base = match resolve env xt with Opt t -> t | t -> t in
      Opt (select_type env base name)
  | Ast.Call { fn; target; args = [ arg ] } when enum_called env target fn <> None -> (
      (* an enum constructor: Enum(int) or Enum(string) *)
      let name = Option.get (enum_called env target fn) in
      match resolve env (infer env locals arg) with
      | Int | String | Dyn | Var _ -> enum_type name
      | t -> fail "enum constructor applied to %s" (to_string (finalize env t)))
  | Ast.Call { fn; target; args } -> (
      (* a namespaced function (math.greatest) or enum constructor *)
      let qualified = match target with Some t -> Option.map (fun p -> String.concat "." (p @ [ fn ])) (ident_path t) | None -> None in
      match qualified with
      | Some q when overloads env q <> [] && (match target with Some t -> (match ident_path t with Some (r :: _) -> lookup_ident env locals r = None | _ -> false) | None -> false) ->
          resolve_call env q ~receiver:false (List.map (infer env locals) args)
      | _ -> (
          match target with
          | Some t -> resolve_call env fn ~receiver:true (infer env locals t :: List.map (infer env locals) args)
          | None -> (
              match fn, List.map (infer env locals) args with
              | "int", [ t ] when (match resolve env t with Opaque (n, []) -> Proto.enum_exists n | _ -> false) ->
                  Int (* int() of a strong enum value *)
              | _, ats -> resolve_call env fn ~receiver:false ats)))
  | Ast.Comprehension { iter_var; iter_var2; range; accu_var; accu_init; loop_cond; loop_step; result } ->
      let rt = infer env locals range in
      let v1, v2 =
        match resolve env rt, iter_var2 with
        | List e, None -> (e, None)
        | List e, Some _ -> (Int, Some e)
        | Map (k, _), None -> (k, None)
        | Map (k, v), Some _ -> (k, Some v)
        | (Dyn | Var _), None -> (Dyn, None)
        | (Dyn | Var _), Some _ -> (Dyn, Some Dyn)
        | t, _ -> fail "expression of type '%s' cannot be the range of a comprehension" (to_string (finalize env t))
      in
      let at = infer env locals accu_init in
      let locals' =
        (iter_var, v1) :: (match iter_var2, v2 with Some n, Some t -> [ (n, t) ] | _ -> []) @ ((accu_var, at) :: locals)
      in
      let ct = infer env locals' loop_cond in
      if not (assignable env Bool ct) then fail "loop condition must be bool";
      let st = infer env locals' loop_step in
      if not (flexible env at st) then fail "loop step does not match the accumulator";
      infer env ((accu_var, at) :: locals) result

(* The cases a match can name: a variant's, or an optional's Some and None.
   None for a scrutinee whose type does not say (dyn, a map). *)
and match_cases env t =
  match resolve env t with
  | Variant cs -> Some cs
  | Opt t -> Some [ ("Some", Some t); ("None", None) ]
  | _ -> None

(* A list element; [?e] contributes the optional's content type. *)
and element env locals (e : Ast.expr) =
  match e.kind with
  | Ast.Call { fn; args = [ x ]; _ } when fn = Ast.op_opt_elem -> (
      match resolve env (infer env locals x) with Opt t -> t | t -> t)
  | _ -> infer env locals e

and select_type env ot field =
  match resolve env ot with
  | Dyn | Var _ -> Dyn
  | Map (k, v) -> if assignable env k String then v else fail "map key type does not support field selection"
  | Message m -> (
      match Option.bind (Proto.find_message m) (fun md -> Proto.find_field md field) with
      | Some f -> field_type f
      | None -> fail "undefined field '%s'" field)
  | Opt t -> Opt (select_type env t field)
  | Variant _ -> (match field with "tag" -> String | "value" -> Dyn | f -> fail "undefined field '%s'" f)
  | Record (fs, _) -> (
      match List.assoc_opt field fs with Some t -> t | None -> fail "undefined field '%s' in %s" field (to_string (resolve env ot)))
  | t -> fail "type '%s' does not support field selection" (to_string (finalize env t))

(* The checked type of an expression, or the first error. Each check is a
   fresh inference: environments derived by declare_ident share the
   substitution table, so it starts empty, and no binding from an earlier
   check can leak into this one. *)
let check env (e : Ast.expr) =
  reset_subst env;
  match infer env [] e with
  | t -> Ok (finalize env t)
  | exception Check_error m -> Error m

(* The checked type and the type of every subexpression, by id. *)
let check_types env (e : Ast.expr) =
  reset_subst env;
  Hashtbl.reset env.types;
  match infer env [] e with
  | t ->
      let types = Hashtbl.create (Hashtbl.length env.types) in
      Hashtbl.iter (fun id u -> Hashtbl.replace types id (finalize env u)) env.types;
      Ok (finalize env t, fun id -> Hashtbl.find_opt types id)
  | exception Check_error m -> Error m
