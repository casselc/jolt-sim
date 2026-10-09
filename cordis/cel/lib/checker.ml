(* The CEL type checker (docs/cordis/rfc/0001-cel.md, step 3).

   Types are inferred bottom-up with type variables and a substitution.
   Two relations do the work:

   - assignable p a: may an argument of type a be passed where p is
     expected? It unifies type variables as it goes. dyn is compatible with
     everything; a wrapper type and its primitive are interchangeable; null
     fits any nullable type. A type parameter already bound to an
     incompatible type is widened to the join of the two (cel-spec's
     "flexible type parameter assignment").
   - join a b: the most general type of two, for list elements, map values
     and the branches of ?:. It is structural (type(int) and type(bool)
     join to type(dyn), not dyn), and anything else that disagrees is dyn.

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
    | n, ps -> Opaque (n, ps)
  in
  ty ()

(* ---- substitution -------------------------------------------------------------------- *)

type env = {
  idents : (string * t) list;
  functions : (string * (bool * t list * t) list) list;  (** name -> (receiver, params, result) *)
  container : string;
  subst : (int, t) Hashtbl.t;
  mutable next : int;
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
  | t -> t

let rec occurs env i t =
  match resolve env t with
  | Var j -> i = j
  | List t | Type t | Wrapper t | Opt t -> occurs env i t
  | Map (k, v) -> occurs env i k || occurs env i v
  | Opaque (_, ps) -> List.exists (occurs env i) ps
  | _ -> false

let bind env i t = if not (occurs env i t) then Hashtbl.replace env.subst i t

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
  | List x, List y | Type x, Type y | Opt x, Opt y -> assignable env x y
  | Map (k1, v1), Map (k2, v2) -> assignable env k1 k2 && assignable env v1 v2
  | Opaque (n, ps), Opaque (m, qs) -> n = m && List.length ps = List.length qs && List.for_all2 (assignable env) ps qs
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
    | t -> t
  in
  (List.map go params, go result)

(* Flexible assignment of an argument to a parameter: when a variable the
   parameter mentions is already bound to something the argument does not
   fit, widen the binding to their join. *)
let rec flexible env p a =
  match resolve env p with
  | _ when assignable env p a -> true
  | _ -> (
      match p with
      | Var i -> (
          match Hashtbl.find_opt env.subst i with
          | Some bound -> Hashtbl.replace env.subst i (join env bound a); true
          | None -> false)
      | List x -> (match resolve env a with List y -> flexible env x y | _ -> false)
      | Type x -> (match resolve env a with Type y -> flexible env x y | _ -> false)
      | Opt x -> (match resolve env a with Opt y -> flexible env x y | _ -> false)
      | Map (k, v) -> (match resolve env a with Map (k', v') -> flexible env k k' && flexible env v v' | _ -> false)
      | Opaque (n, ps) -> (
          match resolve env a with
          | Opaque (m, qs) when n = m && List.length ps = List.length qs -> List.for_all2 (flexible env) ps qs
          | _ -> false)
      | _ -> false)

(* ---- the standard library's signatures ---------------------------------------------------- *)

let sig_ ?(receiver = false) params result = (receiver, List.map parse_type params, parse_type result)

let standard : (string * (bool * t list * t) list) list Lazy.t =
  lazy
    (let num = [ "int"; "uint"; "double" ] in
     let cmp_pairs =
       List.map (fun t -> [ t; t ]) [ "int"; "uint"; "double"; "string"; "bytes"; "bool"; "timestamp"; "duration" ]
       @ [ [ "int"; "uint" ]; [ "int"; "double" ]; [ "uint"; "int" ]; [ "uint"; "double" ]; [ "double"; "int" ]; [ "double"; "uint" ] ]
     in
     let cmp = List.map (fun ps -> sig_ ps "bool") cmp_pairs in
     let arith ts = List.map (fun t -> sig_ [ t; t ] t) ts in
     let time_parts = [ "getFullYear"; "getMonth"; "getDate"; "getDayOfMonth"; "getDayOfYear"; "getDayOfWeek"; "getHours"; "getMinutes"; "getSeconds"; "getMilliseconds" ] in
     [ ("!_", [ sig_ [ "bool" ] "bool" ]);
       ("-_", [ sig_ [ "int" ] "int"; sig_ [ "double" ] "double" ]);
       ("_&&_", [ sig_ [ "bool"; "bool" ] "bool" ]);
       ("_||_", [ sig_ [ "bool"; "bool" ] "bool" ]);
       ("@not_strictly_false", [ sig_ [ "bool" ] "bool" ]);
       ("_==_", [ sig_ [ "A"; "A" ] "bool" ]);
       ("_!=_", [ sig_ [ "A"; "A" ] "bool" ]);
       ("_<_", cmp); ("_<=_", cmp); ("_>_", cmp); ("_>=_", cmp);
       ( "_+_",
         arith (num @ [ "string"; "bytes" ])
         @ [ sig_ [ "list(A)"; "list(A)" ] "list(A)"; sig_ [ "timestamp"; "duration" ] "timestamp";
             sig_ [ "duration"; "timestamp" ] "timestamp"; sig_ [ "duration"; "duration" ] "duration" ] );
       ( "_-_",
         arith num
         @ [ sig_ [ "timestamp"; "timestamp" ] "duration"; sig_ [ "timestamp"; "duration" ] "timestamp";
             sig_ [ "duration"; "duration" ] "duration" ] );
       ("_*_", arith num); ("_/_", arith num); ("_%_", arith [ "int"; "uint" ]);
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
       ("int", List.map (fun t -> sig_ [ t ] "int") [ "int"; "uint"; "double"; "string"; "timestamp" ]);
       ("uint", List.map (fun t -> sig_ [ t ] "uint") [ "int"; "uint"; "double"; "string" ]);
       ("double", List.map (fun t -> sig_ [ t ] "double") [ "int"; "uint"; "double"; "string" ]);
       ("string", List.map (fun t -> sig_ [ t ] "string") [ "int"; "uint"; "double"; "bool"; "bytes"; "string"; "timestamp"; "duration" ]);
       ("bytes", List.map (fun t -> sig_ [ t ] "bytes") [ "string"; "bytes" ]);
       ("bool", List.map (fun t -> sig_ [ t ] "bool") [ "bool"; "string" ]);
       ("dyn", [ sig_ [ "A" ] "dyn" ]);
       ("type", [ sig_ [ "A" ] "type(A)" ]);
       ("timestamp", List.map (fun t -> sig_ [ t ] "timestamp") [ "string"; "timestamp"; "int" ]);
       ("duration", List.map (fun t -> sig_ [ t ] "duration") [ "string"; "duration" ]);
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
  { idents = []; functions = []; container; subst = Hashtbl.create 16; next = 0 }

let declare_ident env name ty = { env with idents = (name, ty) :: env.idents }

let declare_function env name overloads = { env with functions = (name, overloads) :: env.functions }

(* ---- types of protobuf fields ---------------------------------------------------------------- *)

let rec field_type (f : Proto.field) =
  match f.map with
  | Some (k, v) -> Map (element_type k, element_type v)
  | None -> if f.repeated then List (element_type f) else element_type f

(* Enum values are ints, or typed values under strong enums. *)
and enum_type name = if !Proto.strong_enums then Opaque (name, []) else Int

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
                  match Proto.find_message n with
                  | Some _ -> Some (Type (message_type n))
                  | None -> (
                      (* an enum constant's type is its enum's (int unless strong) *)
                      match Proto.enum_constant n with
                      | Some _ -> Some (enum_type (String.sub n 0 (String.rindex n '.')))
                      | None -> None))))
        (candidates env name)

let overloads env name =
  (match List.assoc_opt name env.functions with Some o -> o | None -> [])
  @ (match List.assoc_opt name (Lazy.force standard) with Some o -> o | None -> [])
  @ List.map (fun (r, ps, res) -> sig_ ~receiver:r ps res) (Registry.find_signatures name)

(* Resolve a call: the overloads whose parameters accept the arguments. With
   one match its bindings stand; with several (typically from dyn
   arguments) the result is the join of their results. *)
let resolve_call env name ~receiver args =
  let candidates =
    List.filter (fun (r, ps, _) -> r = receiver && List.length ps = List.length args) (overloads env name)
  in
  let attempt (_, ps, res) =
    let saved = Hashtbl.copy env.subst in
    let ps, res = instantiate env (ps, res) in
    if List.for_all2 (flexible env) ps args then Some (substitute env res, Hashtbl.copy env.subst)
    else (Hashtbl.reset env.subst; Hashtbl.iter (Hashtbl.replace env.subst) saved; None)
  in
  let saved = Hashtbl.copy env.subst in
  let restore s = Hashtbl.reset env.subst; Hashtbl.iter (Hashtbl.replace env.subst) s in
  let matches =
    List.filter_map
      (fun o ->
        restore saved;
        attempt o)
      candidates
  in
  match matches with
  | [] ->
      restore saved;
      fail "found no matching overload for '%s' applied to (%s)" name
        (String.concat ", " (List.map (fun a -> to_string (finalize env a)) args))
  | [ (res, s) ] -> restore s; res
  | (res, s) :: rest ->
      restore s;
      List.fold_left (fun acc (r, _) -> if acc = r then acc else Dyn) res rest

(* The enum type a call names (GlobalEnum(...), TestAllTypes.NestedEnum(...)),
   resolved in the container. *)
let enum_called env target fn =
  let name =
    match target with
    | None -> Some fn
    | Some t -> Option.map (fun p -> String.concat "." (p @ [ fn ])) (ident_path t)
  in
  Option.bind name (fun n -> List.find_opt Proto.enum_exists (candidates env n))

let rec infer env locals (e : Ast.expr) : t =
  match e.kind with
  | Ast.Const v -> (
      match v with
      | Value.Null -> Null | Value.Bool _ -> Bool | Value.Int _ -> Int | Value.Uint _ -> Uint
      | Value.Double _ -> Double | Value.String _ -> String | Value.Bytes _ -> Bytes
      | _ -> Dyn)
  | Ast.Ident n -> (
      match lookup_ident env locals n with Some t -> t | None -> fail "undeclared reference to '%s'" n)
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
      let elem = List.fold_left (fun acc e -> join env acc (element env locals e)) (fresh env) es in
      List elem
  | Ast.Map_lit kvs ->
      let k, v =
        List.fold_left
          (fun (k, v) ((ke : Ast.expr), ve) ->
            let optional, ke = match ke.kind with Ast.Call { fn; args = [ x ]; _ } when fn = Ast.op_opt_elem -> (true, x) | _ -> (false, ke) in
            let vt = infer env locals ve in
            (* {?k: o} contributes the optional's content *)
            let vt = if optional then (match resolve env vt with Opt t -> t | t -> t) else vt in
            (join env k (infer env locals ke), join env v vt))
          (fresh env, fresh env) kvs
      in
      Map (k, v)
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
      join env (infer env locals t) (infer env locals f)
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
  | t -> fail "type '%s' does not support field selection" (to_string (finalize env t))

(* The checked type of an expression, or the first error. *)
let check env (e : Ast.expr) =
  match infer env [] e with
  | t -> Ok (finalize env t)
  | exception Check_error m -> Error m
