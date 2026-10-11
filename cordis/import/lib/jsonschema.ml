(* The JSON Schema importer (docs/cordis/rfc/0003-types-importers-
   scaffolds.md, "The JSON Schema importer"): a 2020-12 schema to graph-
   language type definitions, a check_T cell per type with residue, a
   report of what was approximated, and the name map the codec uses.

   Every location is either given a type that captures its structural
   keywords, its other keywords becoming typed residue, or made dyn and
   checked by the whole validation predicate (Schema_pred). So the types
   are never stricter than the schema except where the RFC says so
   (objects are closed), and the residue never weaker. *)

module J = Yojson.Safe
module S = Cordis_graph.Syntax
module P = Schema_pred

type t =
  | Str of int option
  | Int
  | Num
  | Bool
  | Dyn  (** plain JSON *)
  | Named of string
  | Opt of t
  | Lst of t * int option
  | Mp of t * int option  (** map(string, T) *)
  | Rec of string  (** a recursive schema reference: dyn in the graph language; the codec reads it as the referenced type *)
  | Checked of J.t
      (** dyn in the graph language, its value checked at the boundary by the interpreter against this
          subschema: where the compiled predicate cannot go (recursion, size) *)

type field = {
  name : string;  (** in the graph language *)
  json : string;  (** the property's name *)
  ty : t;  (** option(T) unless required and not nullable *)
  required : bool;
  nullable : bool;
}

type def =
  | Record of field list
  | Enum of (string * string) list  (** case, the JSON string *)
  | Variant of { disc : string; cases : (string * string * string) list  (** case, the discriminator's value, the payload record *) }
  | Alias of t

type import = {
  defs : (string * def) list;  (** graph name -> definition, in emission order *)
  residue : (string * string) list;
      (** graph name -> the check_T cell's residue at x, every reachable type's inlined (CEL has no calls
          between cells); a type whose inlined residue would be too large has none, reported *)
  local : (string * string) list;
      (** graph name -> the type's own residue at x, not looking into the named types it holds: the
          codec checks each named value it reads against its own *)
  root : string;  (** the root schema's type *)
  docs : P.docs;  (** the documents, as loaded, which references resolve in *)
  by_def : (string * string) list;  (** schema reference -> graph name *)
  report : (string * string) list;  (** location, what was approximated *)
}

exception Refused of string

(* ---- names ------------------------------------------------------------------------------------- *)

let reserved = Protobuf.cel_reserved
let sel f = Protobuf.sel f

let ident s =
  let b = Buffer.create (String.length s) in
  String.iteri
    (fun i c ->
      match c with
      | 'a' .. 'z' | 'A' .. 'Z' | '_' -> Buffer.add_char b c
      | '0' .. '9' -> if i = 0 then Buffer.add_char b '_'; Buffer.add_char b c
      | _ -> Buffer.add_char b '_')
    s;
  let r = Buffer.contents b in
  if r = "" then "_" else r

let camel s =
  let parts = String.split_on_char '_' (ident s) |> List.filter (( <> ) "") in
  let r = String.concat "" (List.map String.capitalize_ascii parts) in
  if r = "" then "T" else if r.[0] >= '0' && r.[0] <= '9' then "T" ^ r else r

(* ---- keywords ------------------------------------------------------------------------------------- *)

let annotations =
  [ "$schema"; "$id"; "$anchor"; "$comment"; "title"; "description"; "default"; "examples"; "deprecated"; "readOnly";
    "writeOnly"; "format"; "contentEncoding"; "contentMediaType"; "$defs"; "definitions" ]

let keys (s : J.t) = match s with `Assoc kv -> List.filter (fun k -> not (List.mem k annotations)) (List.map fst kv) | _ -> []
let get (s : J.t) k = match s with `Assoc kv -> List.assoc_opt k kv | _ -> None
let only s allowed = List.for_all (fun k -> List.mem k allowed) (keys s)
let without s ks = match s with `Assoc kv -> `Assoc (List.filter (fun (k, _) -> not (List.mem k ks)) kv) | s -> s
let nat = function Some (`Int n) when n >= 0 -> Some n | _ -> None

(* ---- the import ---------------------------------------------------------------------------------- *)

type ctx = {
  docs : P.docs;
  mutable defs : (string * def) list;  (** reversed *)
  mutable residues : (string * (string -> string list)) list;
  mutable report : (string * string) list;  (** reversed *)
  mutable visiting : string list;  (** references being translated *)
  mutable by_def : (string * string) list;  (** schema reference -> graph name *)
  mutable names : string list;
}

(* a reference as the report shows it: relative to the main document, so
   the report does not depend on where the schema was read from *)
let shown c r =
  let m = c.docs.Schema_pred.main in
  let n = String.length m in
  if n > 0 && String.length r > n && String.sub r 0 n = m && r.[n] = '#' then String.sub r n (String.length r - n) else r

let say c loc fmt = Printf.ksprintf (fun m -> if not (List.mem (loc, m) c.report) then c.report <- (loc, m) :: c.report) fmt

let fresh_name c base =
  let base = camel base in
  let rec go i = let n = if i = 0 then base else Printf.sprintf "%s%d" base i in if List.mem n c.names then go (i + 1) else n in
  let n = go 0 in
  c.names <- n :: c.names;
  n

let conj = function [] -> "true" | [ p ] -> p | ps -> "(" ^ String.concat " && " ps ^ ")"

(* Residues are generated in one of two modes: inlined (a named type's
   residue written out where it is held, for check_T cells) or local (a
   named type contributes nothing; the codec checks it where it reads it).
   Inlined generation is bounded: shared definitions make it exponential. *)
let inline_named = ref true

exception Too_large

let budget = ref max_int

let spend (ps : string list) =
  List.iter (fun p -> budget := !budget - String.length p) ps;
  if !budget < 0 then raise Too_large;
  ps

let named n r = (Named n, fun p -> if !inline_named then spend (r p) else [])

(* A dyn location: checked by the whole validation predicate where it
   compiles; else, where the interpreter reads it (recursion, size), a
   Checked location the codec checks by interpretation, its check_T
   residue the predicate with each recursive edge cut; else refused. *)
let dyn_location c loc (s : J.t) : t * (string -> string list) =
  match P.compile ~docs:c.docs s "x" with
  | _ -> (Dyn, fun p -> match P.compile ~docs:c.docs s p with "true" -> [] | src -> [ src ])
  | exception P.Unsupported why -> (
      match Schema_eval.supported ~docs:c.docs s with
      | Error why2 -> raise (Refused (Printf.sprintf "%s: %s" loc (if why2 = why then why else why2)))
      | Ok () ->
          say c loc "%s: checked at the boundary by the interpreter; check_ cells check it only up to each recursive edge" why;
          ( Checked s,
            fun p ->
              if not !inline_named then []
              else
                match P.compile ~docs:c.docs ~cut:true s p with
                | "true" -> []
                | src -> [ src ]
                | exception P.Unsupported why2 ->
                    say c loc "check_ cells do not check it: %s" why2;
                    [] ))

(* A scalar's residue: its keywords, but not the type the graph language
   already holds. *)
exception Fall_back

(* decided when the location is translated, not when its residue is
   written: a typed location whose other keywords the predicate cannot
   compile (a recursive reference under contains, say) falls back to a dyn
   location the interpreter checks *)
let scalar_residue c loc s =
  let s = without s [ "type" ] in
  match P.compile ~docs:c.docs s "x" with
  | _ -> fun p -> (match P.compile ~docs:c.docs s p with "true" -> [] | src -> [ src ])
  | exception P.Unsupported why ->
      if Schema_eval.supported ~docs:c.docs s = Ok () then raise Fall_back
      else (* the boundary could not check it: refused, not approximated *)
        raise (Refused (Printf.sprintf "%s: %s" loc why))

(* a reference's canonical form: an empty fragment is no fragment *)
let canon r = if String.length r > 0 && r.[String.length r - 1] = '#' then String.sub r 0 (String.length r - 1) else r

(* the name a definition takes from its reference: the last pointer segment *)
let ref_base r =
  match List.rev (String.split_on_char '/' r) with
  | last :: _ when last <> "#" && last <> "" -> P.unescape_pointer (P.percent_decode last)
  | _ -> "Root"

let is_null s = get s "type" = Some (`String "null") && only s [ "type" ]

let rec translate c ~loc (s : J.t) : t * (string -> string list) =
  match translate_typed c ~loc s with v -> v | exception Fall_back -> dyn_location c loc s

and translate_typed c ~loc (s : J.t) : t * (string -> string list) =
  let none _ = [] in
  match s with
  | `Bool true -> (Dyn, none)
  | `Bool false -> raise (Refused (loc ^ ": the schema false admits nothing"))
  | `Assoc _ when keys s = [] -> (Dyn, none)
  | `Assoc _ -> (
      let ty = get s "type" in
      match ty with
      (* a reference, alone *)
      | _ when only s [ "$ref" ] -> reference c ~loc s
      (* nullable: [T, "null"] *)
      | Some (`List [ `String t; `String "null" ]) | Some (`List [ `String "null"; `String t ]) ->
          nullable c ~loc ~whole:s (match s with `Assoc kv -> `Assoc (("type", `String t) :: List.remove_assoc "type" kv) | s -> s)
      | _ when (match get s "anyOf", get s "oneOf" with
                | Some (`List [ a; b ]), None | None, Some (`List [ a; b ]) -> only s [ "anyOf"; "oneOf" ] && (is_null a || is_null b)
                | _ -> false) ->
          let a, b = match get s "anyOf", get s "oneOf" with Some (`List [ a; b ]), _ | _, Some (`List [ a; b ]) -> (a, b) | _ -> assert false in
          let inner = if is_null a then b else a in
          (* oneOf: null must match only the null branch *)
          if get s "oneOf" <> None && admits_null c [] inner then begin
            say c loc "oneOf of null and a type that admits null: dyn, checked by the whole schema";
            dyn_location c loc s
          end
          else nullable c ~loc ~whole:s inner
      | Some (`String "string") when only s [ "type"; "minLength"; "maxLength"; "pattern"; "enum"; "const" ] -> (
          match get s "enum" with
          | Some (`List vs) when vs <> [] && List.for_all (function `String _ -> true | _ -> false) vs && only s [ "type"; "enum" ] ->
              (Named (enum c ~loc (List.map (function `String v -> v | _ -> "") vs)), none)
          | _ -> (Str (nat (get s "maxLength")), scalar_residue c loc s))
      | None when (match get s "enum" with Some (`List vs) -> vs <> [] && List.for_all (function `String _ -> true | _ -> false) vs | _ -> false) && only s [ "enum" ] ->
          (Named (enum c ~loc (match get s "enum" with Some (`List vs) -> List.map (function `String v -> v | _ -> "") vs | _ -> [])), none)
      (* a constant alone: its value's type *)
      | None when only s [ "const" ] -> (
          match get s "const" with
          | Some (`String _) -> (Str None, scalar_residue c loc s)
          | Some (`Bool _) -> (Bool, scalar_residue c loc s)
          | Some (`Int _ | `Intlit _) -> (Int, scalar_residue c loc s)
          | Some (`Float _) -> (Num, scalar_residue c loc s)
          | _ ->
              say c loc "a constant of no scalar type: dyn, checked by the whole schema";
              dyn_location c loc s)
      | Some (`String "integer") when only s [ "type"; "minimum"; "maximum"; "exclusiveMinimum"; "exclusiveMaximum"; "multipleOf"; "enum"; "const" ] ->
          (Int, scalar_residue c loc s)
      | Some (`String "number") when only s [ "type"; "minimum"; "maximum"; "exclusiveMinimum"; "exclusiveMaximum"; "multipleOf"; "enum"; "const" ] ->
          (Num, scalar_residue c loc s)
      | Some (`String "boolean") when only s [ "type"; "enum"; "const" ] -> (Bool, scalar_residue c loc s)
      | Some (`String "array") when only s [ "type"; "items"; "minItems"; "maxItems"; "uniqueItems" ] ->
          let et, er = match get s "items" with Some i -> translate c ~loc:(loc ^ "[]") i | None -> (Dyn, none) in
          let own = scalar_residue c loc (without s [ "items" ]) in
          ( Lst (et, nat (get s "maxItems")),
            fun p ->
              let v = Printf.sprintf "v%d" (String.length p) in
              own p @ (match er v with [] -> [] | inner -> [ Printf.sprintf "%s.all(%s, %s)" p v (conj inner) ]) )
      | (Some (`String "object") | None) when get s "properties" <> None && only s [ "type"; "properties"; "required"; "additionalProperties" ]
                                             && (match get s "additionalProperties" with None | Some (`Bool _) -> true | _ -> false) ->
          let name = fresh_name c loc in
          named name (record c ~loc ~name s)
      | (Some (`String "object") | None) when only s [ "type"; "additionalProperties"; "maxProperties"; "minProperties"; "propertyNames" ]
                                             && (match get s "additionalProperties" with Some (`Assoc _) | Some (`Bool true) -> true | _ -> false) ->
          let vt, vr = translate c ~loc:(loc ^ "{}") (Option.get (get s "additionalProperties")) in
          let own = scalar_residue c loc (without s [ "additionalProperties" ]) in
          ( Mp (vt, nat (get s "maxProperties")),
            fun p ->
              let k = Printf.sprintf "k%d" (String.length p) in
              own p @ (match vr (Printf.sprintf "%s[%s]" p k) with [] -> [] | inner -> [ Printf.sprintf "%s.all(%s, %s)" p k (conj inner) ]) )
      | _ when (match get s "oneOf", get s "anyOf" with Some (`List bs), None | None, Some (`List bs) -> only s [ "oneOf"; "anyOf"; "discriminator" ] && discriminator c bs s <> None | _ -> false) ->
          let bs = match get s "oneOf", get s "anyOf" with Some (`List bs), _ | _, Some (`List bs) -> bs | _ -> [] in
          variant c ~loc s bs (Option.get (discriminator c bs s))
      | _ when (match get s "allOf" with Some (`List bs) -> only s [ "allOf"; "type" ] && List.for_all (fun b -> plain_object (deref c b)) bs | _ -> false) ->
          let bs = match get s "allOf" with Some (`List bs) -> List.map (deref c) bs | _ -> [] in
          let props = List.concat_map (fun b -> match get b "properties" with Some (`Assoc ps) -> ps | _ -> []) bs in
          let names = List.sort_uniq compare (List.map fst props) in
          let props =
            List.map
              (fun n ->
                match List.sort_uniq compare (List.filter_map (fun (m, v) -> if m = n then Some (J.to_string v) else None) props) with
                | [ _ ] -> (n, List.assoc n props)
                | _ ->
                    say c (loc ^ "." ^ n) "allOf defines this property differently in two branches: dyn, checked by both";
                    (n, `Assoc [ ("allOf", `List (List.filter_map (fun (m, v) -> if m = n then Some v else None) props)) ]))
              names
          in
          let required = List.sort_uniq compare (List.concat_map (fun b -> match get b "required" with Some (`List r) -> r | _ -> []) bs) in
          let name = fresh_name c loc in
          named name (record c ~loc ~name (`Assoc [ ("type", `String "object"); ("properties", `Assoc props); ("required", `List required) ]))
      | _ ->
          say c loc "%s: dyn, checked by the whole schema" (if keys s = [] then "no type" else "keywords the types cannot hold (" ^ String.concat ", " (keys s) ^ ")");
          dyn_location c loc s)
  | _ -> raise (Refused (loc ^ ": not a schema"))

(* whether a schema may admit null, judged from its type keywords and
   references: when unsure, yes (the caller then falls back to dyn) *)
and admits_null c seen s =
  match s with
  | `Bool b -> b
  | _ -> (
      match get s "type", get s "$ref" with
      | Some (`String t), _ -> t = "null"
      | Some (`List ts), _ -> List.mem (`String "null") ts
      | None, Some (`String r) when not (List.mem r seen) -> (match P.resolve c.docs r with t -> admits_null c (r :: seen) t | exception P.Unsupported _ -> true)
      | None, _ -> (
          match get s "enum", get s "const" with
          | Some (`List vs), _ -> List.mem `Null vs
          | _, Some v -> v = `Null
          | _ -> true)
      | Some _, _ -> true)

and nullable c ~loc ~whole s =
  let t, r = translate c ~loc s in
  match t with
  | Opt _ -> (t, r)
  (* dyn holds null already *)
  | Dyn | Rec _ | Checked _ -> dyn_location c loc whole
  | _ ->
      (* whether null itself satisfies the schema (const, enum, not, ... see
         it too): decided now, since null is one value *)
      let null_ok =
        match Schema_eval.check ~docs:c.docs whole `Null with
        | Ok b -> b
        | Error why -> raise (Refused (Printf.sprintf "%s: %s" loc why))
      in
      ( Opt t,
        fun p ->
          match r (p ^ ".value()"), null_ok with
          | [], true -> []
          | inner, true -> [ Printf.sprintf "(!%s.hasValue() || %s)" p (conj inner) ]
          | inner, false -> [ Printf.sprintf "(%s.hasValue() && %s)" p (conj (inner @ [ "true" ])) ] )

and deref c s = match get s "$ref" with Some (`String r) when only s [ "$ref" ] -> (try P.resolve c.docs r with P.Unsupported m -> raise (Refused m)) | _ -> s

and plain_object s =
  get s "properties" <> None && only s [ "type"; "properties"; "required"; "additionalProperties" ]
  && (match get s "type" with None | Some (`String "object") -> true | _ -> false)
  && (match get s "additionalProperties" with None | Some (`Bool _) -> true | _ -> false)

(* Every local reference is a definition, keyed by the reference and
   translated once; a reference back into one being translated is a
   recursive edge. *)
and reference c ~loc s =
  let r = match get s "$ref" with Some (`String r) -> canon r | _ -> "" in
  if List.mem r c.visiting then begin
    say c loc "a recursive reference to %s: dyn in the graph language (check_ cells do not look beneath it); the codec reads it as %s" (shown c r) (shown c r);
    (Rec r, fun _ -> [])
  end
  else
    let n = define c r in
    let res p = match List.assoc_opt n c.residues with Some r -> r p | None -> [] in
    (* an alias is read as its type, so its residue is always written out *)
    match List.assoc_opt n c.defs with Some (Alias t) -> (t, res) | _ -> named n res

(* the type for the schema reference r names, translated once *)
and define ?name c r =
  match List.assoc_opt r c.by_def with
  | Some n -> n
  | None ->
      let schema = match P.resolve c.docs r with s -> s | exception P.Unsupported m -> raise (Refused m) in
      let schema = if r = c.docs.main then without schema [ "$defs"; "definitions" ] else schema in
      let base = match name with Some n -> n | None -> ref_base r in
      c.visiting <- r :: c.visiting;
      let n =
        if plain_object schema then begin
          let n = fresh_name c base in
          c.by_def <- (r, n) :: c.by_def;
          let (_ : string -> string list) = record c ~loc:base ~name:n schema in
          n
        end
        else begin
          let t, res = translate c ~loc:base schema in
          match t with
          | Named n -> c.by_def <- (r, n) :: c.by_def; n
          | t ->
              let n = fresh_name c base in
              c.by_def <- (r, n) :: c.by_def;
              c.defs <- (n, Alias t) :: c.defs;
              c.residues <- (n, res) :: c.residues;
              n
        end
      in
      c.visiting <- List.filter (( <> ) r) c.visiting;
      n

and enum c ~loc values =
  (* the same values: the same type *)
  match List.find_opt (function _, Enum cases -> List.map snd cases = values | _ -> false) c.defs with
  | Some (n, _) -> n
  | None ->
  let name = fresh_name c loc in
  let cases = ref [] in
  List.iter
    (fun v ->
      let base = camel v in
      let rec go i = let n = if i = 0 then base else Printf.sprintf "%s%d" base i in if List.mem_assoc n !cases then go (i + 1) else n in
      let case = go 0 in
      if case <> v then say c loc "the value %S is the case %s" v case;
      cases := (case, v) :: !cases)
    values;
  c.defs <- (name, Enum (List.rev !cases)) :: c.defs;
  name

and record c ~loc ~name s =
  let props = match get s "properties" with Some (`Assoc ps) -> ps | _ -> [] in
  let required = match get s "required" with Some (`List r) -> List.filter_map (function `String r -> Some r | _ -> None) r | _ -> [] in
  (match get s "additionalProperties" with
   | Some (`Bool false) -> ()
   | _ -> say c loc "the object is open in the schema; the record is closed: a property it does not name is refused");
  if get s "type" = None then say c loc "the schema does not say type: object; the record requires an object, and anything else is refused";
  (* a required property the schema does not describe: any value, unless no
     other property is allowed, when no object satisfies the schema *)
  let unlisted = List.filter (fun r -> not (List.mem_assoc r props)) required in
  let closed = get s "additionalProperties" = Some (`Bool false) in
  List.iter
    (fun r ->
      if closed then say c loc "required %s is not among the properties, and no other is allowed: nothing satisfies the schema" r
      else say c loc "required %s is not among the properties: a required field of any value" r)
    unlisted;
  let props = if closed then props else props @ List.map (fun r -> (r, `Bool true)) unlisted in
  let used = ref [] in
  let fields =
    List.map
      (fun (p, ps) ->
        let base = let i = ident p in if List.mem i reserved then i else i in
        let rec go i = let n = if i = 0 then base else Printf.sprintf "%s_%d" base i in if List.mem n !used then go (i + 1) else n in
        let fname = go 0 in
        used := fname :: !used;
        if fname <> p then say c (loc ^ "." ^ p) "the property is the field %s" fname;
        let t, r = translate c ~loc:(name ^ "_" ^ fname) ps in
        let req = List.mem p required in
        let nullable = match t with Opt _ -> true | _ -> false in
        (* a property that need not be present, with a type that admits null but a schema that does not
           (an enum without null): absent and null are both None, so it is not nullable (the codec refuses
           null) and its residue lets absence through *)
        let null_refused =
          (not req) && nullable && (match Schema_eval.check ~docs:c.docs ps `Null with Ok b -> not b | Error _ -> false)
        in
        let nullable = nullable && not null_refused in
        let ty, r =
          if null_refused then (t, fun q -> match r q with [] -> [] | inner -> [ Printf.sprintf "(!%s.hasValue() || %s)" q (conj inner) ])
          else if req || nullable then (t, r)
          else (Opt t, fun q -> match r (q ^ ".value()") with [] -> [] | inner -> [ Printf.sprintf "(!%s.hasValue() || %s)" q (conj inner) ])
        in
        ({ name = fname; json = p; ty; required = req; nullable }, r))
      props
  in
  let residue p = (if closed && unlisted <> [] then [ "false" ] else []) @ List.concat_map (fun (f, r) -> r (p ^ "." ^ sel f.name)) fields in
  c.defs <- (name, Record (List.map fst fields)) :: c.defs;
  c.residues <- (name, residue) :: c.residues;
  residue

(* the property every branch fixes to a distinct string, if any *)
and discriminator c bs s =
  let branches = List.map (deref c) bs in
  if not (List.for_all plain_object branches) then None
  else
    let const_of b d =
      match get b "properties" with
      | Some (`Assoc ps) -> (
          match List.assoc_opt d ps with
          | Some p -> (match get p "const", get p "enum" with Some (`String v), _ | None, Some (`List [ `String v ]) -> Some v | _ -> None)
          | None -> None)
      | _ -> None
    in
    let required b d = match get b "required" with Some (`List r) -> List.mem (`String d) r | _ -> false in
    let candidates =
      match get s "discriminator" with
      | Some d -> (match get d "propertyName" with Some (`String p) -> [ p ] | _ -> [])
      | None -> (match branches with b :: _ -> (match get b "properties" with Some (`Assoc ps) -> List.map fst ps | _ -> []) | [] -> [])
    in
    List.find_opt
      (fun d ->
        let vs = List.map (fun b -> if required b d then const_of b d else None) branches in
        List.for_all Option.is_some vs && List.length (List.sort_uniq compare vs) = List.length vs)
      candidates

and variant c ~loc _s bs disc =
  let name = fresh_name c loc in
  let branches = List.map (deref c) bs in
  let cases =
    List.map
      (fun b ->
        let v = match get b "properties" with Some (`Assoc ps) -> (match List.assoc_opt disc ps with Some p -> (match get p "const", get p "enum" with Some (`String v), _ | _, Some (`List [ `String v ]) -> v | _ -> "") | None -> "") | _ -> "" in
        let case = camel v in
        let payload = fresh_name c (name ^ "_" ^ case) in
        let b = match b with
          | `Assoc kv ->
              `Assoc
                (List.map
                   (fun (k, x) ->
                     match k, x with
                     | "properties", `Assoc ps -> (k, `Assoc (List.remove_assoc disc ps))
                     | "required", `List r -> (k, `List (List.filter (( <> ) (`String disc)) r))
                     | _ -> (k, x))
                   kv)
          | b -> b
        in
        let r = record c ~loc:payload ~name:payload b in
        (case, v, payload, r))
      branches
  in
  c.defs <- (name, Variant { disc; cases = List.map (fun (cs, v, p, _) -> (cs, v, p)) cases }) :: c.defs;
  let residue p =
    let arms =
      List.filter_map
        (fun (cs, _, _, r) ->
          let b = Printf.sprintf "c%d" (String.length p) in
          match r b with [] -> None | inner -> Some (Printf.sprintf "%s(%s) -> %s" cs b (conj inner)))
        cases
    in
    match arms with [] -> [] | arms -> [ Printf.sprintf "match %s { %s; _ -> true }" p (String.concat "; " arms) ]
  in
  c.residues <- (name, residue) :: c.residues;
  named name residue

(* Import the document [docs.main] of a loaded set (P.documents): many
   imports can share one set. *)
let rec import_docs ?(name = "Root") (docs : P.docs) =
  let doc = P.main_doc docs in
  let c = { docs; defs = []; residues = []; report = []; visiting = []; by_def = []; names = []; } in
  let root_name = match get doc "title" with Some (`String t) -> t | _ -> name in
  (* every $defs entry, then the root, which is the reference "#" *)
  (* a key as a JSON Pointer segment in a URI fragment: ~ is ~0, / is ~1,
     % is %25 *)
  let segment k = P.replace ~sub:"%" ~by:"%25" (P.replace ~sub:"/" ~by:"~1" (P.replace ~sub:"~" ~by:"~0" k)) in
  let defs =
    (match get doc "$defs" with Some (`Assoc ds) -> List.map (fun (k, _) -> docs.main ^ "#/$defs/" ^ segment k) ds | _ -> [])
    @ match get doc "definitions" with Some (`Assoc ds) -> List.map (fun (k, _) -> docs.main ^ "#/definitions/" ^ segment k) ds | _ -> []
  in
  List.iter (fun r -> ignore (define c r)) defs;
  let root = define ~name:root_name c docs.main in
  let bounded = ref false in
  let rec unbounded = function
    | Str None | Lst (_, None) | Mp (_, None) | Dyn | Rec _ | Checked _ -> true
    | Opt t -> unbounded t
    | Lst (t, _) | Mp (t, _) -> unbounded t
    | _ -> false
  in
  List.iter
    (fun (_, d) ->
      match d with
      | Record fs -> if List.exists (fun f -> unbounded f.ty) fs then bounded := true
      | Alias t -> if unbounded t then bounded := true
      | _ -> ())
    c.defs;
  if !bounded then say c "(all)" "where the schema gives no maxLength, maxItems or maxProperties, the configured default ceiling applies";
  let defs = List.rev c.defs in
  let gen inline n r =
    inline_named := inline;
    budget := if inline then 400_000 else max_int;
    match r "x" with [] -> None | ps -> Some (n, conj ps)
  in
  let local = List.filter_map (fun (n, _) -> Option.bind (List.assoc_opt n c.residues) (gen false n)) defs in
  let residue =
    List.filter_map
      (fun (n, _) ->
        match List.assoc_opt n c.residues with
        | None -> None
        | Some r -> (
            (* the graph checker's bound on a cell's expression, counted as it
               counts (Library.inline's default) *)
            let fits (_, src) = match Cordis_cel.Cel.parse src with Ok e -> Cordis_cel.Library.size e <= 10_000 | Error _ -> true in
            match (match gen true n r with Some v when not (fits v) -> raise Too_large | v -> v) with
            | v -> v
            | exception Too_large ->
                say c n "check_%s is not written: its residue, every type it holds written out, is too large for a cell; the codec checks it type by type" n;
                None))
      defs
  in
  inline_named := true;
  budget := max_int;
  { defs; residue; local; root; docs; by_def = c.by_def; report = List.rev c.report }

(* Import [doc], given under [uri]; [others] are the further documents its
   references may name, each under its URI ("" to rely on its $id). *)
and import ?name ?uri ?others (doc : J.t) = import_docs ?name (P.documents ?uri ?others doc)

(* Import the document under [uri] in a shared set. *)
let import_in ?name (docs : P.docs) uri = import_docs ?name { docs with main = uri }

(* ---- the types file ------------------------------------------------------------------------------- *)

let rec graph_ty = function
  | Str n -> S.Scalar ("string", n)
  | Int -> S.Scalar ("int", None)
  | Num -> S.Scalar ("double", None)
  | Bool -> S.Scalar ("bool", None)
  | Dyn | Rec _ | Checked _ -> S.Scalar ("dyn", None)
  | Named n -> S.Named n
  | Opt t -> S.Option (graph_ty t)
  | Lst (t, n) -> S.List (graph_ty t, n)
  | Mp (t, n) -> S.Map (S.Scalar ("string", None), graph_ty t, n)

let types_file (imp : import) : S.file =
  let types =
    List.map
      (fun (n, d) ->
        match d with
        | Record fs -> S.Type_def (n, S.Alias (S.Record (List.map (fun f -> (f.name, graph_ty f.ty)) fs, false)), None)
        | Enum cases -> S.Type_def (n, S.Variant (List.map (fun (c, _) -> (c, None)) cases), None)
        | Variant { cases; _ } -> S.Type_def (n, S.Variant (List.map (fun (c, _, p) -> (c, Some (S.Named p))) cases), None)
        | Alias t -> S.Type_def (n, S.Alias (graph_ty t), None))
      imp.defs
  in
  let checks =
    List.map
      (fun (n, src) ->
        match Cordis_graph.Parse.file (Printf.sprintf "cell check_%s(x : %s) : bool = { %s; }" n n src) with
        | Ok [ c ] -> c
        | Ok _ -> failwith ("residue for " ^ n)
        | Error m -> failwith ("residue for " ^ n ^ ": " ^ m ^ "\n" ^ src))
      imp.residue
  in
  types @ checks

let name_map (imp : import) : J.t =
  `Assoc
    (("$root", `String imp.root)
    :: List.map
         (fun (n, d) ->
           ( n,
             match d with
             | Record fs ->
                 `Assoc
                   [ ( "properties",
                       `Assoc (List.map (fun f -> (f.name, `Assoc [ ("json", `String f.json); ("required", `Bool f.required); ("nullable", `Bool f.nullable) ])) fs) ) ]
             | Enum cases -> `Assoc [ ("enum", `Assoc (List.map (fun (c, v) -> (c, `String v)) cases)) ]
             | Variant { disc; cases } ->
                 `Assoc [ ("discriminator", `String disc); ("cases", `Assoc (List.map (fun (c, v, p) -> (c, `Assoc [ ("value", `String v); ("payload", `String p) ])) cases)) ]
             | Alias _ -> `Assoc [ ("alias", `Bool true) ] ))
         imp.defs)
