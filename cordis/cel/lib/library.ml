(* Function libraries (docs/cordis/rfc/0001-cel.md, "Extensions"): typed
   CEL functions in a content-addressed file, imported by hash and called as
   alias.f(x).

   A library file is a sequence of definitions, each ended by ';':

     // comments run to the end of the line
     greet(name: string): string = 'hello, ' + name;
     shout(name: string): string = greet(name).upperAscii();

   Types are in the checker's text form ("list(string)", "map(string,
   int)"). A body names only its parameters and the library's other
   functions (called unqualified); the checker enforces that, so a library
   cannot reach anything its caller does not pass it. The call graph must be
   acyclic: recursion is the line CEL does not cross.

   A library is identified by the SHA-256 of its text. Calls are checked
   against the declared signatures (so a caller depends on a function's
   type, not its body), then inlined for evaluation and cost estimation:
   each argument is bound once, eagerly, to a fresh reserved name, as a
   function call would evaluate it, and the inlined expression's size is
   bounded. A pure cell called as a function is a library function. *)

open Ast

type fn = { name : string; params : (string * Checker.t) list; result : Checker.t; body : expr }
type t = { hash : string; fns : fn list }

exception Library_error of string

let fail fmt = Printf.ksprintf (fun s -> raise (Library_error s)) fmt

(* ---- splitting the text --------------------------------------------------------------------- *)

(* The text with comments blanked, split at each ';' outside brackets and
   string literals. *)
let definitions text =
  let n = String.length text in
  let out = Buffer.create n and defs = ref [] in
  let depth = ref 0 and i = ref 0 in
  let flush () =
    let d = String.trim (Buffer.contents out) in
    if d <> "" then defs := d :: !defs;
    Buffer.clear out
  in
  let add c = Buffer.add_char out c in
  while !i < n do
    let c = text.[!i] in
    if c = '/' && !i + 1 < n && text.[!i + 1] = '/' then begin
      while !i < n && text.[!i] <> '\n' do incr i done
    end
    else if c = '"' || c = '\'' then begin
      (* a raw prefix (r, R) turns escapes off *)
      let raw =
        let j = ref (!i - 1) and r = ref false in
        while !j >= 0 && (match text.[!j] with 'r' | 'R' | 'b' | 'B' -> true | _ -> false) do
          if text.[!j] = 'r' || text.[!j] = 'R' then r := true;
          decr j
        done;
        !r
      in
      let triple = !i + 2 < n && text.[!i + 1] = c && text.[!i + 2] = c in
      let close = if triple then String.make 3 c else String.make 1 c in
      Buffer.add_string out close;
      i := !i + String.length close;
      let fin = ref false in
      while not !fin do
        if !i >= n then fail "unterminated string literal";
        if (not raw) && text.[!i] = '\\' && !i + 1 < n then (add text.[!i]; add text.[!i + 1]; i := !i + 2)
        else if String.length close <= n - !i && String.sub text !i (String.length close) = close then begin
          Buffer.add_string out close;
          i := !i + String.length close;
          fin := true
        end
        else (add text.[!i]; incr i)
      done
    end
    else begin
      (match c with
       | '(' | '[' | '{' -> incr depth
       | ')' | ']' | '}' -> decr depth
       | _ -> ());
      if c = ';' && !depth = 0 then flush () else add c;
      incr i
    end
  done;
  if String.trim (Buffer.contents out) <> "" then fail "the last definition is not ended by ';'";
  List.rev !defs

(* Split at the first top-level occurrence of [sep] satisfying [ok]. *)
let split_top s ~sep ~ok =
  let depth = ref 0 and found = ref None in
  String.iteri
    (fun i c ->
      if !found = None then
        match c with
        | '(' | '[' | '{' -> incr depth
        | ')' | ']' | '}' -> decr depth
        | _ -> if c = sep && !depth = 0 && ok i then found := Some i)
    s;
  Option.map (fun i -> (String.sub s 0 i, String.sub s (i + 1) (String.length s - i - 1))) !found

let commas s =
  let rec go s acc =
    match split_top s ~sep:',' ~ok:(fun _ -> true) with
    | Some (a, rest) -> go rest (String.trim a :: acc)
    | None -> List.rev (String.trim s :: acc)
  in
  if String.trim s = "" then [] else go s []

let is_ident s =
  s <> ""
  && (match s.[0] with 'a' .. 'z' | 'A' .. 'Z' | '_' -> true | _ -> false)
  && String.for_all (function 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' | '_' -> true | _ -> false) s

let parse_type what s =
  match Checker.parse_type (String.trim s) with
  | t -> t
  | exception _ -> fail "%s: bad type '%s'" what (String.trim s)

(* name(p: t, ...): t = body *)
let parse_definition d =
  let header, body =
    match
      split_top d ~sep:'=' ~ok:(fun i ->
          let prev = if i > 0 then d.[i - 1] else ' ' and next = if i + 1 < String.length d then d.[i + 1] else ' ' in
          next <> '=' && not (List.mem prev [ '='; '!'; '<'; '>' ]))
    with
    | Some (h, b) -> (String.trim h, String.trim b)
    | None -> fail "a definition needs '= body': %s" d
  in
  let lp = match String.index_opt header '(' with Some i -> i | None -> fail "a definition needs parameters: %s" header in
  let name = String.trim (String.sub header 0 lp) in
  if not (is_ident name) then fail "bad function name '%s'" name;
  let rest = String.sub header lp (String.length header - lp) in
  let params_text, result_text =
    match split_top rest ~sep:':' ~ok:(fun _ -> true) with
    | Some (p, r) -> (String.trim p, r)
    | None -> fail "%s: a definition needs a result type" name
  in
  if String.length params_text < 2 || params_text.[String.length params_text - 1] <> ')' then
    fail "%s: bad parameter list" name;
  let params =
    List.map
      (fun p ->
        match String.index_opt p ':' with
        | Some i ->
            let pn = String.trim (String.sub p 0 i) in
            if not (is_ident pn) then fail "%s: bad parameter name '%s'" name pn;
            (pn, parse_type name (String.sub p (i + 1) (String.length p - i - 1)))
        | None -> fail "%s: parameter '%s' needs a type" name p)
      (commas (String.sub params_text 1 (String.length params_text - 2)))
  in
  let seen = Hashtbl.create 4 in
  List.iter (fun (p, _) -> if Hashtbl.mem seen p then fail "%s: duplicate parameter '%s'" name p; Hashtbl.add seen p ()) params;
  let body = match Parser.parse body with Ok e -> e | Error m -> fail "%s: %s" name m in
  { name; params; result = parse_type name result_text; body }

(* ---- the tree ------------------------------------------------------------------------------- *)

let map_children f (e : expr) =
  let kind =
    match e.kind with
    | Const _ | Ident _ -> e.kind
    | Select s -> Select { s with operand = f s.operand }
    | Call c -> Call { c with target = Option.map f c.target; args = List.map f c.args }
    | List_lit es -> List_lit (List.map f es)
    | Map_lit kvs -> Map_lit (List.map (fun (k, v) -> (f k, f v)) kvs)
    | Struct_lit s -> Struct_lit { s with fields = List.map (fun (n, x, o) -> (n, f x, o)) s.fields }
    | Comprehension c ->
        Comprehension
          { c with range = f c.range; accu_init = f c.accu_init; loop_cond = f c.loop_cond; loop_step = f c.loop_step;
            result = f c.result }
  in
  { e with kind }

(* The functions an expression calls unqualified, outside [locals]. *)
let rec unqualified_calls (e : expr) =
  let here = match e.kind with Call { fn; target = None; _ } -> [ fn ] | _ -> [] in
  let below = ref [] in
  ignore (map_children (fun c -> below := unqualified_calls c @ !below; c) e);
  here @ !below

(* Rename free occurrences of [names], respecting comprehension scopes. *)
let rec rename (sub : (string * string) list) (e : expr) =
  if sub = [] then e
  else
    match e.kind with
    | Ident n -> (match List.assoc_opt n sub with Some n' -> { e with kind = Ident n' } | None -> e)
    | Comprehension c ->
        let without vs = List.filter (fun (n, _) -> not (List.mem n vs)) sub in
        let iters = c.iter_var :: Option.to_list c.iter_var2 in
        let in_loop = without (c.accu_var :: iters) and in_result = without [ c.accu_var ] in
        { e with
          kind =
            Comprehension
              { c with range = rename sub c.range; accu_init = rename sub c.accu_init;
                loop_cond = rename in_loop c.loop_cond; loop_step = rename in_loop c.loop_step;
                result = rename in_result c.result } }
    | _ -> map_children (rename sub) e

let rec size (e : expr) =
  let n = ref 1 in
  ignore (map_children (fun c -> n := !n + size c; c) e);
  !n

let rec max_id (e : expr) =
  let m = ref e.id in
  ignore (map_children (fun c -> m := max !m (max_id c); c) e);
  !m

(* ---- loading -------------------------------------------------------------------------------- *)

let find lib name = List.find_opt (fun f -> f.name = name) lib.fns

(* The library's functions as checker declarations, under [prefix]. *)
let declare ?(prefix = "") env lib =
  List.fold_left
    (fun env f -> Checker.declare_function env (prefix ^ f.name) [ (false, List.map snd f.params, f.result) ])
    env lib.fns

let check_acyclic lib =
  let state = Hashtbl.create 8 in
  let rec visit path f =
    match Hashtbl.find_opt state f.name with
    | Some `Done -> ()
    | Some `Active -> fail "recursion: %s" (String.concat " -> " (List.rev (f.name :: path)))
    | None ->
        Hashtbl.replace state f.name `Active;
        List.iter
          (fun g -> match find lib g with Some g -> visit (f.name :: path) g | None -> ())
          (unqualified_calls f.body);
        Hashtbl.replace state f.name `Done
  in
  List.iter (visit []) lib.fns

let check_bodies lib =
  let siblings = declare (Checker.empty ()) lib in
  List.iter
    (fun f ->
      let env = List.fold_left (fun env (p, t) -> Checker.declare_ident env p t) siblings f.params in
      match Checker.check env f.body with
      | Error m -> fail "%s: %s" f.name m
      | Ok t ->
          if not (Checker.assignable (Checker.empty ()) f.result t) then
            fail "%s: the body has type %s, not the declared %s" f.name (Checker.to_string t) (Checker.to_string f.result))
    lib.fns

(* Parse and check a library's text. *)
let parse text =
  Extensions.load ();
  match
    let fns = List.map parse_definition (definitions text) in
    let names = Hashtbl.create 8 in
    List.iter (fun f -> if Hashtbl.mem names f.name then fail "duplicate function '%s'" f.name; Hashtbl.add names f.name ()) fns;
    let lib = { hash = "sha256:" ^ Sha256.digest text; fns } in
    check_acyclic lib;
    check_bodies lib;
    lib
  with
  | lib -> Ok lib
  | exception Library_error m -> Error m

(* Load a library by its expected hash ("sha256:<hex>"): the text must be
   the one that hash names. *)
let load ~expect text =
  let got = "sha256:" ^ Sha256.digest text in
  if got <> expect then Error (Printf.sprintf "library hash mismatch: expected %s, got %s" expect got) else parse text

(* ---- inlining ------------------------------------------------------------------------------- *)

(* Inline every call alias.f(args) of an imported library, and the
   library's own calls within it, giving the result fresh node ids. An
   alias shadowed by a comprehension variable is not a library. *)
let inline ?(max_nodes = 10_000) (libs : (string * t) list) (e : expr) =
  let next = ref (max_id e) in
  let mk kind = incr next; { id = !next; kind } in
  let fresh_id (e : expr) = incr next; { e with id = !next } in
  let rec renumber (e : expr) = fresh_id (map_children renumber e) in
  let counter = ref 0 in
  let rec go ~lib ~locals depth (e : expr) =
    if depth > 64 then fail "library calls nest too deeply";
    let call_of (alias, lib) fn args =
      match find lib fn with
      | None -> fail "library %s has no function '%s'" alias fn
      | Some f ->
          if List.length args <> List.length f.params then
            fail "%s.%s takes %d arguments, not %d" alias fn (List.length f.params) (List.length args);
          incr counter;
          let fresh = List.map (fun (p, _) -> (p, Printf.sprintf "@%s.%s.%s#%d" alias fn p !counter)) f.params in
          let body = go ~lib:(Some (alias, lib)) ~locals:[] (depth + 1) (rename fresh (renumber f.body)) in
          (* a call is strict: an argument that is an error is the result,
             whether or not the body uses it *)
          let body =
            if fresh = [] then body
            else mk (Call { fn = "@strict"; target = None; args = List.map (fun (_, p') -> mk (Ident p')) fresh @ [ body ] })
          in
          List.fold_right2 (fun (_, p') a acc -> Ext_cel.bind ~mk p' a acc) fresh args body
    in
    match e.kind with
    | Call { fn; target = Some { kind = Ident alias; _ }; args }
      when List.mem_assoc alias libs && not (List.mem alias locals) ->
        call_of (alias, List.assoc alias libs) fn (List.map (go ~lib ~locals depth) args)
    | Call { fn; target = None; args } when (match lib with Some (_, l) -> find l fn <> None | None -> false)
                                           && not (List.mem fn locals) ->
        call_of (Option.get lib) fn (List.map (go ~lib ~locals depth) args)
    | Comprehension c ->
        let loop = c.accu_var :: c.iter_var :: Option.to_list c.iter_var2 in
        { e with
          kind =
            Comprehension
              { c with range = go ~lib ~locals depth c.range; accu_init = go ~lib ~locals depth c.accu_init;
                loop_cond = go ~lib ~locals:(loop @ locals) depth c.loop_cond;
                loop_step = go ~lib ~locals:(loop @ locals) depth c.loop_step;
                result = go ~lib ~locals:(c.accu_var :: locals) depth c.result } }
    | _ -> map_children (go ~lib ~locals depth) e
  in
  match go ~lib:None ~locals:[] 0 e with
  | inlined ->
      let n = size inlined in
      if n > max_nodes then Error (Printf.sprintf "inlined expression has %d nodes, over the bound of %d" n max_nodes)
      else Ok inlined
  | exception Library_error m -> Error m

(* Check an expression that calls imported libraries against their
   signatures, then inline them: the checked type and the expression to
   evaluate and estimate. *)
let rec missing_function ?(locals = []) libs (e : expr) =
  let here =
    match e.kind with
    | Call { fn; target = Some { kind = Ident alias; _ }; _ } when not (List.mem alias locals) -> (
        match List.assoc_opt alias libs with
        | Some lib when find lib fn = None -> Some (Printf.sprintf "library %s has no function '%s'" alias fn)
        | _ -> None)
    | _ -> None
  in
  match here with
  | Some m -> Some m
  | None ->
      let found = ref None in
      let locals =
        match e.kind with
        | Comprehension c -> c.accu_var :: c.iter_var :: Option.to_list c.iter_var2 @ locals
        | _ -> locals
      in
      ignore (map_children (fun c -> (if !found = None then found := missing_function ~locals libs c); c) e);
      !found

let compile ?max_nodes env (libs : (string * t) list) (e : expr) =
  let env = List.fold_left (fun env (alias, lib) -> declare ~prefix:(alias ^ ".") env lib) env libs in
  match missing_function libs e with
  | Some m -> Error m
  | None ->
  match Checker.check env e with
  | Error m -> Error m
  | Ok t -> Result.map (fun e -> (t, e)) (inline ?max_nodes libs e)
