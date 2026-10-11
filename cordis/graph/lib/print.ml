(* The canonical text of graph-language definitions, and their hashes
   (docs/cordis/rfc/0002-graph-language.md, "Identity and edits").

   The printer is the parser's inverse up to formatting: printing then
   parsing gives the same tree, and two texts that differ only in spacing,
   comments or string-literal spelling print the same. A definition's hash
   is the SHA-256 of its canonical text together with the hashes of the
   definitions it names, so a change to a type changes the hash of every
   cell and graph that uses it. *)

open Syntax

let bound = function None -> "" | Some n -> Printf.sprintf "<max %d>" n

let rec ty = function
  | Scalar (n, b) -> n ^ bound b
  | List (t, b) -> "list(" ^ ty t ^ ")" ^ bound b
  | Map (k, v, b) -> "map(" ^ ty k ^ ", " ^ ty v ^ ")" ^ bound b
  | Option t -> "option(" ^ ty t ^ ")"
  | Record (fs, open_) ->
      let fields = List.map (fun (f, t) -> f ^ " : " ^ ty t ^ ";") fs @ if open_ then [ ".." ] else [] in
      if fields = [] then "{}" else "{ " ^ String.concat " " fields ^ " }"
  | Named n -> n
  | Var v -> "'" ^ v

let expr = function Cel { text; _ } -> text | Hole h -> "?" ^ h

let tdef = function
  | Alias t -> ty t
  | Variant cs ->
      let body = String.concat " | " (List.map (fun (c, p) -> match p with None -> c | Some t -> c ^ "(" ^ ty t ^ ")") cs) in
      (* a lone unit case, or a first case in lower case, would read back as
         a type name: a leading '|' keeps it a variant *)
      let needs_bar =
        match cs with
        | [ (_, None) ] -> true
        | (c, _) :: _ -> not (c.[0] >= 'A' && c.[0] <= 'Z')
        | [] -> false
      in
      if needs_bar then "| " ^ body else body

let cell (c : cell) =
  let b = Buffer.create 256 in
  let add = Buffer.add_string b in
  add (Printf.sprintf "cell %s(%s) : %s" c.name
         (String.concat ", " (List.map (fun (p, t) -> p ^ " : " ^ ty t) c.params))
         (ty c.result));
  if c.effects <> [] then add (" ! { " ^ String.concat ", " c.effects ^ " }");
  if c.state <> [] then
    add (" state { " ^ String.concat " " (List.map (fun (f, t, e) -> Printf.sprintf "%s : %s = %s;" f (ty t) (expr e)) c.state) ^ " }");
  add " = {\n";
  List.iter
    (function
      | Let (x, e) -> add (Printf.sprintf "  let %s = %s;\n" x (expr e))
      | Use (x, k, e) -> add (Printf.sprintf "  use %s = %s(%s);\n" x k (expr e)))
    c.body;
  add ("  " ^ expr c.final);
  if c.updates <> [] then
    add (" with " ^ String.concat ", " (List.map (fun (f, e) -> Printf.sprintf "state.%s := %s" f (expr e)) c.updates));
  add ";\n}";
  Buffer.contents b

let fresh = function On_change -> "on_change" | Always -> "always" | Every d -> "every " ^ d

let pattern = function
  | Wild -> "_"
  | Case (t, None) -> t
  | Case (t, Some b) -> t ^ "(" ^ b ^ ")"

let action = function Enable a -> a | Append (s, e) -> s ^ " <~ " ^ expr e | Nothing -> "none"

let gitem = function
  | Input (n, t) -> Printf.sprintf "input %s : %s;" n (ty t)
  | Stream (n, t, cap) -> Printf.sprintf "stream %s : %s up to %d;" n (ty t) cap
  | Asset { name; cell; args; fresh = f } ->
      Printf.sprintf "asset %s = %s(%s)%s;" name cell (String.concat ", " args)
        (match f with None -> "" | Some f -> " ~fresh:" ^ fresh f)
  | Route (a, cs) ->
      Printf.sprintf "route %s { %s };" a
        (String.concat "; " (List.map (fun (p, acts) -> pattern p ^ " -> " ^ String.concat ", " (List.map action acts)) cs))
  | Before (a, b) -> Printf.sprintf "invariant %s before %s;" a b
  | Always_inv e -> "invariant always " ^ expr e ^ ";"
  | Sometimes_inv e -> "invariant sometimes " ^ expr e ^ ";"

let item = function
  | Uses (a, h) -> Printf.sprintf "uses %s = %s;" a h
  | Uses_descriptors h -> Printf.sprintf "uses descriptors %s;" h
  | Type_def (n, d, nodes) ->
      Printf.sprintf "type %s = %s%s;" n (tdef d) (match nodes with Some k -> Printf.sprintf " <nodes %d>" k | None -> "")
  | Effect (k, req, r) -> Printf.sprintf "effect %s(%s) : %s;" k (ty req) (ty r)
  | Cell c -> cell c
  | Graph (n, items) -> "graph " ^ n ^ " {\n" ^ String.concat "" (List.map (fun i -> "  " ^ gitem i ^ "\n") items) ^ "}"

let file items = String.concat "\n\n" (List.map item items) ^ "\n"

(* ---- hashes ----------------------------------------------------------------------------------- *)

(* The names a definition refers to: types, effects, cells, library aliases. *)
let rec ty_refs = function
  | Scalar _ | Var _ -> []
  | List (t, _) | Option t -> ty_refs t
  | Map (k, v, _) -> ty_refs k @ ty_refs v
  | Record (fs, _) -> List.concat_map (fun (_, t) -> ty_refs t) fs
  | Named n -> [ n ]

(* identifiers a CEL tree mentions as call targets or type names: library
   aliases (alias.f(x)), variant constructors (T.Case(x)), record literals,
   and dotted paths that may name a protobuf type or enum constant
   (pkg.Enum.VALUE). A path rooted at a variable in scope ([bound]) is a
   field selection, not a name. *)
let rec cel_refs ?(bound = []) (e : Cordis_cel.Ast.expr) =
  let open Cordis_cel.Ast in
  let rec path (e : expr) =
    match e.kind with
    | Ident n -> Some n
    | Select { operand; field; test_only = false } -> Option.map (fun p -> p ^ "." ^ field) (path operand)
    | _ -> None
  in
  let root p = match String.index_opt p '.' with Some i -> String.sub p 0 i | None -> p in
  let named p = if List.mem (root p) bound then [] else [ p ] in
  let here =
    match e.kind with
    | Call { target = Some { kind = Ident n; _ }; _ } -> named n
    | Select { operand = { kind = Ident n; _ }; _ } -> named n @ Option.fold ~none:[] ~some:named (path e)
    | Select _ -> Option.fold ~none:[] ~some:named (path e)
    | Struct_lit { tname; _ } -> [ tname ]
    | _ -> []
  in
  let r = cel_refs ~bound in
  let below =
    match e.kind with
    | Const _ | Ident _ -> []
    | Select s -> r s.operand
    | Call c -> (match c.target with Some t -> r t | None -> []) @ List.concat_map r c.args
    | List_lit es -> List.concat_map r es
    | Map_lit kvs -> List.concat_map (fun (k, v) -> r k @ r v) kvs
    | Struct_lit s -> List.concat_map (fun (_, x, _) -> r x) s.fields
    | Comprehension c ->
        let inner = cel_refs ~bound:(c.iter_var :: c.accu_var :: Option.to_list c.iter_var2 @ bound) in
        r c.range @ r c.accu_init @ List.concat_map inner [ c.loop_cond; c.loop_step; c.result ]
  in
  here @ below

let expr_refs ?bound = function Cel { ast; _ } -> cel_refs ?bound ast | Hole _ -> []

let refs = function
  | Uses _ | Uses_descriptors _ -> []
  | Type_def (_, Alias t, _) -> ty_refs t
  | Type_def (_, Variant cs, _) -> List.concat_map (fun (_, p) -> match p with Some t -> ty_refs t | None -> []) cs
  | Effect (_, a, b) -> ty_refs a @ ty_refs b
  | Cell c ->
      (* the cell's variables: its parameters, state, lets and uses *)
      let bound =
        "state" :: List.map fst c.params @ List.map (function Let (x, _) | Use (x, _, _) -> x) c.body
      in
      let expr_refs = expr_refs ~bound in
      List.concat_map (fun (_, t) -> ty_refs t) c.params
      @ ty_refs c.result @ c.effects
      @ List.concat_map (fun (_, t, e) -> ty_refs t @ expr_refs e) c.state
      @ List.concat_map (function Let (_, e) -> expr_refs e | Use (_, k, e) -> k :: expr_refs e) c.body
      @ expr_refs c.final
      @ List.concat_map (fun (_, e) -> expr_refs e) c.updates
  | Graph (_, items) ->
      List.concat_map
        (function
          | Input (_, t) | Stream (_, t, _) -> ty_refs t
          | Asset { cell; _ } -> [ cell ]
          | Route (_, cs) -> List.concat_map (fun (_, acts) -> List.concat_map (function Append (_, e) -> expr_refs e | _ -> []) acts) cs
          | Before (a, b) -> [ a; b ]
          | Always_inv e | Sometimes_inv e -> expr_refs e)
        items

(* Each definition's hash: SHA-256 over its canonical text and the sorted
   (name, hash) pairs of the definitions it refers to, transitively. A
   reference back into a cycle contributes its name only. *)
let hashes (items : file) =
  let defs = List.map (fun i -> (item_name i, i)) items in
  (* a protobuf full name (it has a dot) is defined by the attached
     descriptor sets: a definition naming one depends on all of them *)
  let sets = List.filter_map (function Uses_descriptors h -> Some h | _ -> None) items in
  let refs i = List.concat_map (fun n -> if String.contains n '.' then sets else [ n ]) (refs i) in
  let memo = Hashtbl.create 16 in
  let rec hash visiting name =
    match Hashtbl.find_opt memo name with
    | Some h -> Some h
    | None -> (
        match List.assoc_opt name defs with
        | None -> None
        | Some _ when List.mem name visiting -> None
        | Some i ->
            let deps =
              List.sort_uniq compare (refs i)
              |> List.filter (fun n -> n <> name)
              |> List.filter_map (fun n -> Option.map (fun h -> n ^ "=" ^ h) (hash (name :: visiting) n))
            in
            let h = "sha256:" ^ Cordis_cel.Sha256.digest (String.concat "\n" (item i :: deps)) in
            if not (List.exists (fun n -> List.mem n visiting) (refs i)) then Hashtbl.replace memo name h;
            Some h)
  in
  List.map (fun (n, _) -> (n, Option.get (hash [] n))) defs
