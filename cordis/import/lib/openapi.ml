(* The OpenAPI importer (docs/cordis/rfc/0003-types-importers-scaffolds.md,
   revision 4, "The OpenAPI importer"): an OpenAPI 3.0 or 3.1 document to
   graph-language types, one effect per operation, and the operation map the
   outbox's http executor reads.

   The schemas go through the JSON Schema importer, unchanged: this module
   builds one JSON Schema document whose $defs are the document's component
   schemas, each operation's request object (its parameters and body, as
   properties) and each response body, and imports it. A 3.0 schema is
   rewritten to 2020-12 first. What JSON Schema cannot say, the response
   variants and the effects, is emitted here. *)

module J = Yojson.Safe
module S = Cordis_graph.Syntax
module JS = Jsonschema

type location = Path | Query | Header | Cookie

type param = {
  field : string;  (** the request record's field *)
  wire : string;  (** the parameter's name *)
  loc : location;
  style : string;
  explode : bool;
  required : bool;
}

type case = {
  case : string;  (** the response variant's case *)
  status : string;  (** "200", "2XX" *)
  media : string option;
  payload : string option;  (** the case's type, a graph-language type name *)
}

type cls = Observational | Keyed | Effectful

(* how a form or multipart body writes one of its properties (an Encoding Object) *)
type encoding = { content_type : string option; style : string option; explode : bool option }

(* where a security scheme's credential goes *)
type scheme = Api_key of location * string | Bearer | Basic

type operation = {
  kind : string;  (** the effect: service.op *)
  meth : string;  (** upper case *)
  path : string;  (** the template, as the document writes it *)
  request : string;  (** the request record *)
  response : string;  (** the response variant *)
  params : param list;
  body : (string * string) option;  (** the body's field and media type *)
  encoding : (string * encoding) list;  (** a form or multipart body's properties, as its Encoding Objects say *)
  cases : case list;
  security : string list;  (** the schemes any of which authorises it *)
  cls : cls;
  idempotency : string option;  (** the header the executor sets to the intent's key *)
  default : string;  (** the record Other carries: the status and the body *)
}

type t = {
  imp : JS.import;
  service : string;
  ops : operation list;
  servers : string list;
  schemes : (string * scheme) list;
  other : string;  (** the record for an undocumented status *)
  defaults : (string * string) list;  (** an operation's own record for its default response -> the body's type *)
  variants : (string * (string * string option) list) list;  (** response variant -> case, payload *)
  report : (string * string) list;
}

exception Refused of string

let refuse fmt = Printf.ksprintf (fun m -> raise (Refused m)) fmt
let get = JS.get
let str j k = match get j k with Some (`String s) -> Some s | _ -> None
let assoc j k = match get j k with Some (`Assoc kv) -> kv | _ -> []
let list j k = match get j k with Some (`List l) -> l | _ -> []

let lower_camel s = let c = JS.camel s in String.uncapitalize_ascii c

(* ---- reading ---------------------------------------------------------------------------------------- *)

(* YAML as JSON: OpenAPI documents are mostly YAML *)
let rec of_yaml (v : Yaml.value) : J.t =
  match v with
  | `Null -> `Null
  | `Bool b -> `Bool b
  | `Float f -> if Float.is_integer f && Float.abs f < 1e15 then `Int (int_of_float f) else `Float f
  | `String s -> `String s
  | `A l -> `List (List.map of_yaml l)
  | `O kv -> `Assoc (List.map (fun (k, v) -> (k, of_yaml v)) kv)

let document text =
  match J.from_string text with
  | j -> Ok j
  | exception Yojson.Json_error _ -> (
      match Yaml.of_string text with
      | Ok v -> Ok (of_yaml v)
      | Error (`Msg m) -> (
          (* libyaml refuses a line of nothing but a tab, which other readers take as blank: blank such
             lines and try once more (at worst this drops whitespace inside a block scalar) *)
          let blank l = String.for_all (fun c -> c = ' ' || c = '\t' || c = '\r') l in
          let text' = String.concat "\n" (List.map (fun l -> if blank l then "" else l) (String.split_on_char '\n' text)) in
          match if text' = text then Error (`Msg m) else Yaml.of_string text' with
          | Ok v -> Ok (of_yaml v)
          | Error _ -> Error ("neither JSON nor YAML: " ^ m)))

(* ---- references within the document --------------------------------------------------------------- *)

let unescape seg = Schema_pred.replace ~sub:"~0" ~by:"~" (Schema_pred.replace ~sub:"~1" ~by:"/" seg)

let pointer doc r =
  if String.length r < 2 || String.sub r 0 2 <> "#/" then refuse "a reference outside the document: %s" r;
  let segs = String.split_on_char '/' (String.sub r 2 (String.length r - 2)) |> List.map unescape in
  List.fold_left
    (fun j s ->
      match j with
      | `Assoc kv -> (match List.assoc_opt s kv with Some x -> x | None -> refuse "a dangling reference: %s" r)
      | `List l -> (match int_of_string_opt s with Some i when i < List.length l -> List.nth l i | _ -> refuse "a dangling reference: %s" r)
      | _ -> refuse "a dangling reference: %s" r)
    doc segs

(* a parameter, request body or response given by reference *)
let rec deref doc j = match str j "$ref" with Some r -> deref doc (pointer doc r) | None -> j

(* ---- schemas --------------------------------------------------------------------------------------- *)

let schema_ref_prefix = "#/components/schemas/"
let segment k = Schema_pred.replace ~sub:"%" ~by:"%25" (Schema_pred.replace ~sub:"/" ~by:"~1" (Schema_pred.replace ~sub:"~" ~by:"~0" k))

(* OpenAPI's own keywords inside a schema, which JSON Schema does not know *)
let oas_only = [ "example"; "xml"; "externalDocs" ]

(* A schema as JSON Schema 2020-12: references to component schemas point
   at $defs; for 3.0, nullable becomes a null type and boolean exclusive
   bounds numeric ones. *)
let rec fix ?(doc = `Null) ?(depth = 0) ~v30 (s : J.t) : J.t =
  let sub = fix ~doc ~depth in
  match s with
  (* a reference into the document that is not a component schema (a parameter's schema, say):
     inlined, since $defs holds only the component schemas *)
  | `Assoc kv
    when (match List.assoc_opt "$ref" kv with
          | Some (`String r) ->
              String.length r > 2 && String.sub r 0 2 = "#/"
              && not (String.length r > String.length schema_ref_prefix && String.sub r 0 (String.length schema_ref_prefix) = schema_ref_prefix)
          | _ -> false)
         && doc <> `Null ->
      if depth > 32 then refuse "references nested too deep";
      let r = match List.assoc "$ref" kv with `String r -> r | _ -> "" in
      fix ~doc ~depth:(depth + 1) ~v30 (pointer doc r)
  | `Assoc kv ->
      let kv = List.filter (fun (k, _) -> not (List.mem k oas_only)) kv in
      let kv =
        List.map
          (fun (k, v) ->
            match k, v with
            | "$ref", `String r when String.length r > String.length schema_ref_prefix
                                      && String.sub r 0 (String.length schema_ref_prefix) = schema_ref_prefix ->
                let name = String.sub r (String.length schema_ref_prefix) (String.length r - String.length schema_ref_prefix) in
                (k, `String ("#/$defs/" ^ name))
            | "discriminator", `Assoc d ->
                (* its mapping names component schemas too *)
                ( k,
                  `Assoc
                    (List.map
                       (function
                         | "mapping", `Assoc m ->
                             ( "mapping",
                               `Assoc
                                 (List.map
                                    (fun (n, x) ->
                                      match x with
                                      | `String r when String.length r > String.length schema_ref_prefix
                                                        && String.sub r 0 (String.length schema_ref_prefix) = schema_ref_prefix ->
                                          (n, `String ("#/$defs/" ^ String.sub r (String.length schema_ref_prefix) (String.length r - String.length schema_ref_prefix)))
                                      | x -> (n, x))
                                    m) )
                         | x -> x)
                       d) )
            (* maps from names to schemas: each value is a schema, the names are not keywords *)
            | ("properties" | "patternProperties" | "$defs" | "definitions" | "dependentSchemas"), `Assoc ps ->
                (k, `Assoc (List.map (fun (p, x) -> (p, sub ~v30 x)) ps))
            (* values, not schemas: left as they are *)
            | ("enum" | "const" | "default" | "examples"), _ -> (k, v)
            (* anything else may hold schemas (properties, items, allOf, definitions, an extension's
               own): walk it, so a reference anywhere is redirected *)
            | _, (`Assoc _ as x) -> (k, sub ~v30 x)
            | _, `List xs -> (k, `List (List.map (sub ~v30) xs))
            | _ -> (k, v))
          kv
      in
      if not v30 then `Assoc kv
      else
        let nullable = List.assoc_opt "nullable" kv = Some (`Bool true) in
        let kv = List.remove_assoc "nullable" kv in
        (* boolean exclusive bounds: the bound becomes exclusive *)
        let excl key bound kv =
          match List.assoc_opt key kv, List.assoc_opt bound kv with
          | Some (`Bool true), Some b -> (key, b) :: List.remove_assoc key (List.remove_assoc bound kv)
          | Some (`Bool _), _ -> List.remove_assoc key kv
          | _ -> kv
        in
        let kv = excl "exclusiveMinimum" "minimum" (excl "exclusiveMaximum" "maximum" kv) in
        if not nullable then `Assoc kv
        else (
          match List.assoc_opt "type" kv, List.assoc_opt "$ref" kv with
          | Some (`String t), _ -> `Assoc (("type", `List [ `String t; `String "null" ]) :: List.remove_assoc "type" kv)
          | None, Some _ -> `Assoc [ ("anyOf", `List [ `Assoc kv; `Assoc [ ("type", `String "null") ] ]) ]
          | _ ->
              (* an enum must admit null itself to be nullable *)
              let kv = match List.assoc_opt "enum" kv with Some (`List es) when not (List.mem `Null es) -> ("enum", `List (es @ [ `Null ])) :: List.remove_assoc "enum" kv | _ -> kv in
              `Assoc kv)
  | s -> s

(* ---- operations ------------------------------------------------------------------------------------ *)

let methods = [ "get"; "put"; "post"; "delete"; "options"; "head"; "patch"; "trace" ]

let class_of meth idem =
  match meth with
  | "get" | "head" | "options" | "trace" -> Observational
  | "put" | "delete" -> Keyed
  | _ -> if idem <> None then Keyed else Effectful

let base_media m = String.lowercase_ascii (List.hd (String.split_on_char ';' m)) |> String.trim

let is_json m =
  let m = base_media m in
  m = "application/json" || (String.length m > 5 && String.sub m (String.length m - 5) 5 = "+json")

let is_form m = base_media m = "application/x-www-form-urlencoded"
let is_multipart m = base_media m = "multipart/form-data"

let loc_of = function "path" -> Some Path | "query" -> Some Query | "header" -> Some Header | "cookie" -> Some Cookie | _ -> None
let loc_name = function Path -> "path" | Query -> "query" | Header -> "header" | Cookie -> "cookie"
let default_style = function Path | Header -> "simple" | Query | Cookie -> "form"

(* headers the HTTP layer owns, which OpenAPI says to ignore as parameters *)
let reserved_headers = [ "accept"; "content-type"; "authorization" ]

exception Skip of string

let import ?service ?(idempotency_header = "idempotency-key") (doc : J.t) : t =
  let report = ref [] in
  let say loc fmt = Printf.ksprintf (fun m -> report := (loc, m) :: !report) fmt in
  let version = match str doc "openapi" with Some v -> v | None -> if get doc "swagger" <> None then refuse "Swagger 2.0: convert it to OpenAPI 3 first" else refuse "not an OpenAPI document" in
  let v30 =
    if String.length version >= 3 && String.sub version 0 3 = "3.0" then true
    else if String.length version >= 3 && String.sub version 0 3 = "3.1" then false
    else refuse "OpenAPI %s: only 3.0 and 3.1 are imported" version
  in
  let service =
    match service with
    | Some s -> s
    | None -> (match get doc "info" with Some i -> (match str i "title" with Some t -> lower_camel t | None -> "service") | None -> "service")
  in
  let fix ~v30 s = fix ~doc ~v30 s in
  let components = assoc (match get doc "components" with Some c -> c | None -> `Assoc []) "schemas" in
  let defs = ref (List.rev_map (fun (k, s) -> (k, fix ~v30 s)) components) in
  let taken = ref (List.map fst components) in
  let def_key base =
    let rec go i = let k = if i = 0 then base else Printf.sprintf "%s_%d" base i in if List.mem k !taken then go (i + 1) else k in
    let k = go 0 in
    taken := k :: !taken;
    k
  in
  let schemes = assoc (match get doc "components" with Some c -> c | None -> `Assoc []) "securitySchemes" in
  (* the credential parameters the executor adds: apiKey schemes' header, query or cookie names *)
  let credentials =
    List.filter_map
      (fun (_, sc) ->
        let sc = deref doc sc in
        match str sc "type", str sc "in", str sc "name" with
        | Some "apiKey", Some i, Some n -> Option.map (fun l -> (l, String.lowercase_ascii n)) (loc_of i)
        | _ -> None)
      schemes
  in
  let doc_security = list doc "security" in
  let op_names = ref [] in
  let ops = ref [] in
  (* the operations: (operation, the $defs keys of its request and its response bodies) *)
  List.iter
    (fun (path, item) ->
      let item = deref doc item in
      let shared = list item "parameters" in
      List.iter
        (fun meth ->
          match get item meth with
          | None -> ()
          | Some op -> (
              let where = Printf.sprintf "%s %s" (String.uppercase_ascii meth) path in
              try
                let name =
                  match str op "operationId" with
                  | Some id -> id
                  | None ->
                      let n = meth ^ "_" ^ path in
                      say where "no operationId: named %s" (lower_camel n);
                      n
                in
                let base = JS.camel name in
                let base = let rec go i = let b = if i = 0 then base else Printf.sprintf "%s%d" base i in if List.mem b !op_names then go (i + 1) else b in go 0 in
                op_names := base :: !op_names;
                let kind = service ^ "." ^ String.uncapitalize_ascii base in
                (* parameters: the operation's override the path item's by (name, in) *)
                let ps = List.map (deref doc) (shared @ list op "parameters") in
                let key p = (str p "name", str p "in") in
                let ps = List.fold_left (fun acc p -> (key p, p) :: List.remove_assoc (key p) acc) [] ps |> List.rev_map snd in
                let idem = ref None in
                let params, props, required =
                  List.fold_left
                    (fun (params, props, req) p ->
                      let wire = match str p "name" with Some n -> n | None -> raise (Skip "a parameter with no name") in
                      let loc = match Option.bind (str p "in") loc_of with Some l -> l | None -> raise (Skip ("parameter " ^ wire ^ ": an unknown location")) in
                      let lw = String.lowercase_ascii wire in
                      if loc = Header && List.mem lw reserved_headers then (say where "the header %s is the HTTP layer's: not a field" wire; (params, props, req))
                      else if List.mem (loc, lw) credentials then (say where "the %s %s is a credential: the executor adds it, not a field" (loc_name loc) wire; (params, props, req))
                      else if loc = Header && lw = idempotency_header then (idem := Some wire; say where "the header %s carries the intent's key: the executor sets it" wire; (params, props, req))
                      else begin
                        let schema = match get p "schema" with Some s -> s | None -> raise (Skip ("parameter " ^ wire ^ ": described by content, not a schema")) in
                        let style = match str p "style" with Some s -> s | None -> default_style loc in
                        let explode = match get p "explode" with Some (`Bool b) -> b | _ -> style = "form" in
                        let ok =
                          match loc, style with
                          | Path, "simple" | Header, "simple" | Cookie, "form" -> true
                          | Query, "form" -> true
                          | Query, "deepObject" -> true
                          | _ -> false
                        in
                        if not ok then raise (Skip (Printf.sprintf "parameter %s: the style %s" wire style));
                        if List.mem_assoc wire props then raise (Skip ("two parameters named " ^ wire));
                        let required = loc = Path || get p "required" = Some (`Bool true) in
                        ( { field = wire; wire; loc; style; explode; required } :: params,
                          props @ [ (wire, fix ~v30 schema) ],
                          if required then wire :: req else req )
                      end)
                    ([], [], []) ps
                in
                let params = List.rev params in
                let body, encoding, props, required =
                  match get op "requestBody" with
                  | None -> (None, [], props, required)
                  | Some rb ->
                      let rb = deref doc rb in
                      let content = assoc rb "content" in
                      let req = get rb "required" = Some (`Bool true) in
                      if List.mem_assoc "body" props then raise (Skip "a parameter named body and a request body");
                      (* JSON first, then a form, then multipart, then text: the first the document offers *)
                      let first p = List.find_opt (fun (m, _) -> p m) content in
                      let schema mt = match get mt "schema" with Some s -> fix ~v30 s | None -> `Bool true in
                      (* a form's or multipart body's properties are its fields or parts: it is an object *)
                      let fields m mt =
                        let o = match get mt "schema" with Some s -> deref doc s | None -> `Null in
                        if not (get o "type" = Some (`String "object") || get o "properties" <> None) then
                          raise (Skip (Printf.sprintf "a request body in %s that is not an object" (base_media m)));
                        let enc =
                          List.map
                            (fun (k, e) ->
                              ( k,
                                { content_type = str e "contentType"; style = str e "style";
                                  explode = (match get e "explode" with Some (`Bool b) -> Some b | _ -> None) } ))
                            (assoc mt "encoding")
                        in
                        (* a multipart part's content type by default: a binary string's is application/octet-stream (or
                           its contentMediaType); the encoder gives the rest theirs by their values *)
                        let binary =
                          if not (is_multipart m) then []
                          else
                            List.filter_map
                              (fun (k, ps) ->
                                let ps = deref doc ps in
                                (* a list's parts are its items *)
                                let ps = if get ps "type" = Some (`String "array") then (match get ps "items" with Some i -> deref doc i | None -> ps) else ps in
                                let ct =
                                  match str ps "contentMediaType", str ps "format" with
                                  | Some c, _ -> Some c
                                  | None, Some ("binary" | "base64") -> Some "application/octet-stream"
                                  | _ -> None
                                in
                                match ct with
                                | Some c when not (List.exists (fun (k', e) -> k' = k && e.content_type <> None) enc) ->
                                    Some (k, { content_type = Some c; style = None; explode = None })
                                | _ -> None)
                              (assoc o "properties")
                        in
                        Some (m, schema mt, List.filter (fun (k, _) -> not (List.mem_assoc k binary)) enc @ binary)
                      in
                      let pick =
                        match first is_json with
                        | Some (m, mt) -> Some (m, schema mt, [])
                        | None -> (
                            match first is_form, first is_multipart with
                            | Some (m, mt), _ | None, Some (m, mt) -> fields m mt
                            | None, None -> (
                                match first (fun m -> String.length m >= 5 && String.sub m 0 5 = "text/") with
                                | Some (m, _) -> Some (m, `Assoc [ ("type", `String "string") ], [])
                                | None -> None))
                      in
                      (match pick with
                       | None -> raise (Skip ("a request body in " ^ String.concat ", " (List.map fst content)))
                       | Some (m, s, enc) -> (Some ("body", m), enc, props @ [ ("body", s) ], if req then "body" :: required else required))
                in
                let request_key = def_key (base ^ "Request") in
                defs :=
                  ( request_key,
                    `Assoc
                      [ ("type", `String "object"); ("properties", `Assoc props);
                        ("required", `List (List.rev_map (fun r -> `String r) required));
                        ("additionalProperties", `Bool false) ] )
                  :: !defs;
                (* responses: a case per documented status *)
                let responses = assoc op "responses" in
                let cases =
                  List.filter_map
                    (fun (status, r) ->
                      if status = "default" then None
                      else
                        let r = deref doc r in
                        List.iter (fun l -> say where "the %s response's link %s is not imported" status l) (List.map fst (assoc r "links"));
                        let content = assoc r "content" in
                        let case = "S" ^ String.map (fun c -> match c with 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' -> c | _ -> '_') status in
                        match List.find_opt (fun (m, _) -> is_json m) content with
                        | Some (m, mt) ->
                            let k = def_key (base ^ "_" ^ status) in
                            defs := (k, match get mt "schema" with Some s -> fix ~v30 s | None -> `Bool true) :: !defs;
                            Some ({ case; status; media = Some m; payload = None }, Some k)
                        | None -> (
                            match List.find_opt (fun (m, _) -> String.length m >= 5 && String.sub m 0 5 = "text/") content with
                            | Some (m, _) ->
                                let k = def_key (base ^ "_" ^ status) in
                                defs := (k, `Assoc [ ("type", `String "string") ]) :: !defs;
                                Some ({ case; status; media = Some m; payload = None }, Some k)
                            | None ->
                                if content <> [] then say where "the %s response's body (%s) is not imported" status (String.concat ", " (List.map fst content));
                                Some ({ case; status; media = None; payload = None }, None)))
                    responses
                in
                (* the default response's body, if it has a JSON one: Other carries it with the status *)
                let default_key =
                  match List.assoc_opt "default" responses with
                  | None -> None
                  | Some r -> (
                      let r = deref doc r in
                      match List.find_opt (fun (m, _) -> is_json m) (assoc r "content") with
                      | Some (_, mt) ->
                          let k = def_key (base ^ "_default") in
                          defs := (k, match get mt "schema" with Some s -> fix ~v30 s | None -> `Bool true) :: !defs;
                          Some k
                      | None -> None)
                in
                let security =
                  let reqs = match get op "security" with Some (`List l) -> l | _ -> doc_security in
                  List.concat_map (function `Assoc kv -> List.map fst kv | _ -> []) reqs |> List.sort_uniq compare
                in
                List.iter (fun cb -> say where "the callback %s is not imported" cb) (List.map fst (assoc op "callbacks"));
                let cls = class_of meth !idem in
                ops :=
                  ( { kind; meth = String.uppercase_ascii meth; path; request = request_key; response = base ^ "Response"; params; body; encoding; cases = List.map fst cases;
                      security; cls; idempotency = !idem; default = "" },
                    request_key,
                    List.map snd cases,
                    default_key )
                  :: !ops
              with Skip why -> say where "skipped: %s" why))
        methods)
    (assoc doc "paths");
  List.iter (fun (w, _) -> say "webhooks" "the webhook %s is not imported" w) (assoc doc "webhooks");
  let ops = List.rev !ops in
  let all_defs = List.rev !defs in
  let uri = "urn:cordis:openapi" in
  (* the $defs keys a schema names, through $ref and discriminator mappings *)
  let prefix = "#/$defs/" in
  let named r =
    let n = String.length prefix in
    if String.length r > n && String.sub r 0 n = prefix then
      Some (Schema_pred.replace ~sub:"%25" ~by:"%" (unescape (String.sub r n (String.length r - n))))
    else None
  in
  let rec refs acc (j : J.t) =
    match j with
    | `Assoc kv ->
        List.fold_left
          (fun acc (k, v) ->
            match k, v with
            | "$ref", `String r -> (match named r with Some x -> x :: acc | None -> acc)
            | "mapping", `Assoc m -> List.fold_left (fun acc (_, x) -> match x with `String r -> (match named r with Some x -> x :: acc | None -> acc) | _ -> acc) acc m
            | _ -> refs acc v)
          acc kv
    | `List l -> List.fold_left refs acc l
    | _ -> acc
  in
  let reach keys =
    let rec go seen = function
      | [] -> seen
      | k :: rest when List.mem k seen -> go seen rest
      | k :: rest -> (match List.assoc_opt k all_defs with Some sc -> go (k :: seen) (refs [] sc @ rest) | None -> go seen rest)
    in
    let seen = go [] keys in
    List.filter (fun (k, _) -> List.mem k seen) all_defs
  in
  let keys_of (_, req, payloads, default) = req :: List.filter_map Fun.id payloads @ Option.to_list default in
  let attempt ops =
    let synthetic =
      `Assoc
        [ ("$schema", `String "https://json-schema.org/draft/2020-12/schema");
          ("$defs", `Assoc (reach (List.concat_map keys_of ops))) ]
    in
    match JS.import ~name:"OpenapiRoot" ~uri synthetic with imp -> Ok imp | exception JS.Refused m -> Error m
  in
  (* an operation whose schemas the JSON Schema importer refuses is skipped, not the document: halve
     the operations until each refusal is pinned on one *)
  let rec refused ops =
    match ops with
    | [] -> []
    | _ -> (
        match attempt ops with
        | Ok _ -> []
        | Error m -> (
            match ops with
            | [ op ] -> [ (op, m) ]
            | _ ->
                let n = List.length ops / 2 in
                refused (List.filteri (fun i _ -> i < n) ops) @ refused (List.filteri (fun i _ -> i >= n) ops)))
  in
  let imp, ops =
    match attempt ops with
    | Ok imp -> (imp, ops)
    | Error first ->
        let bad = refused ops in
        if bad = [] then refuse "%s" first;
        List.iter (fun ((op, _, _, _), m) -> say (op.meth ^ " " ^ op.path) "skipped: %s" m) bad;
        let ops = List.filter (fun o -> not (List.mem_assq o bad)) ops in
        match attempt ops with Ok imp -> (imp, ops) | Error m -> refuse "%s" m
  in
  (* the synthetic root is not a type of the service *)
  let imp =
    { imp with
      defs = List.filter (fun (n, _) -> n <> imp.root) imp.defs;
      residue = List.filter (fun (n, _) -> n <> imp.root) imp.residue;
      local = List.filter (fun (n, _) -> n <> imp.root) imp.local }
  in
  let name_of k = match List.assoc_opt (uri ^ "#/$defs/" ^ segment k) imp.by_def with Some n -> n | None -> refuse "no type for %s" k in
  let used = ref (List.map fst imp.defs) in
  let fresh base = let rec go i = let n = if i = 0 then base else Printf.sprintf "%s%d" base i in if List.mem n !used then go (i + 1) else n in let n = go 0 in used := n :: !used; n in
  let other = fresh "HttpOther" in
  let defaults = ref [] in
  let ops, variants =
    List.split
      (List.map
         (fun (op, req_key, payload_keys, default_key) ->
           let response = fresh op.response in
           let cases = List.map2 (fun c k -> { c with payload = Option.map name_of k }) op.cases payload_keys in
           let other =
             match default_key with
             | None -> other
             | Some k ->
                 let n = fresh (String.sub op.response 0 (String.length op.response - String.length "Response") ^ "Default") in
                 defaults := (n, name_of k) :: !defaults;
                 n
           in
           let op = { op with request = name_of req_key; response; cases; default = other } in
           (op, (response, List.map (fun c -> (c.case, c.payload)) cases @ [ ("Other", Some other) ])))
         ops)
  in
  let servers =
    List.filter_map
      (fun s ->
        match str s "url" with
        | None -> None
        | Some u ->
            let vars = assoc s "variables" in
            if vars <> [] then say "servers" "%s: variables at their defaults (%s)" u (String.concat ", " (List.map fst vars));
            Some
              (List.fold_left
                 (fun u (v, d) -> match str d "default" with Some x -> Schema_pred.replace ~sub:("{" ^ v ^ "}") ~by:x u | None -> u)
                 u vars))
      (list doc "servers")
  in
  (* check cells for the requests only: a cell holds its type's residue with every type it reaches
     written out, which for a large document is most of the document again per type. A request is
     what a cell builds and may check before it sends; a response is checked by the codec as the
     executor reads it, each nested value against its own residue *)
  let boundary = List.map (fun op -> op.request) ops in
  let imp = { imp with residue = List.filter (fun (n, _) -> List.mem n boundary) imp.residue } in
  let schemes =
    List.filter_map
      (fun (n, sc) ->
        let sc = deref doc sc in
        match str sc "type", str sc "in", str sc "name", str sc "scheme" with
        | Some "apiKey", Some i, Some k, _ -> Option.map (fun l -> (n, Api_key (l, k))) (loc_of i)
        | Some "http", _, _, Some b when String.lowercase_ascii b = "basic" -> Some (n, Basic)
        | Some ("http" | "oauth2" | "openIdConnect"), _, _, _ -> Some (n, Bearer)
        | _ -> None)
      schemes
  in
  { imp; service; ops; servers; schemes; other; defaults = List.rev !defaults; variants; report = List.rev !report @ imp.report }

(* ---- output ---------------------------------------------------------------------------------------- *)

let types_file (t : t) : S.file =
  let base = JS.types_file t.imp in
  let other = S.Type_def (t.other, S.Alias (S.Record ([ ("status", S.Scalar ("int", None)); ("body", S.Scalar ("dyn", None)) ], false)), None) in
  let defaults =
    List.map (fun (n, b) -> S.Type_def (n, S.Alias (S.Record ([ ("status", S.Scalar ("int", None)); ("body", S.Named b) ], false)), None)) t.defaults
  in
  let variants =
    List.map (fun (n, cases) -> S.Type_def (n, S.Variant (List.map (fun (c, p) -> (c, Option.map (fun p -> S.Named p) p)) cases), None)) t.variants
  in
  let effects = List.map (fun op -> S.Effect (op.kind, S.Named op.request, S.Named op.response)) t.ops in
  let types, checks = List.partition (function S.Type_def _ -> true | _ -> false) base in
  types @ (other :: defaults) @ variants @ effects @ checks

let cls_name = function Observational -> "observational" | Keyed -> "keyed" | Effectful -> "effectful"

let operations (t : t) : J.t =
  `Assoc
    [ ("service", `String t.service);
      ("servers", `List (List.map (fun s -> `String s) t.servers));
      ("other", `String t.other);
      ( "operations",
        `Assoc
          (List.map
             (fun op ->
               ( op.kind,
                 `Assoc
                   ([ ("method", `String op.meth); ("path", `String op.path); ("request", `String op.request); ("response", `String op.response);
                      ( "params",
                        `List
                          (List.map
                             (fun p ->
                               `Assoc
                                 [ ("field", `String p.field); ("name", `String p.wire); ("in", `String (loc_name p.loc)); ("style", `String p.style);
                                   ("explode", `Bool p.explode); ("required", `Bool p.required) ])
                             op.params) );
                      ( "cases",
                        `List
                          (List.map
                             (fun c ->
                               `Assoc
                                 ([ ("case", `String c.case); ("status", `String c.status) ]
                                 @ (match c.media with Some m -> [ ("media", `String m) ] | None -> [])
                                 @ match c.payload with Some p -> [ ("payload", `String p) ] | None -> []))
                             op.cases) );
                      ("default", `String op.default); ("security", `List (List.map (fun s -> `String s) op.security)); ("class", `String (cls_name op.cls)) ]
                   @ (match op.body with
                     | Some (f, m) ->
                         let enc (k, e) =
                           ( k,
                             `Assoc
                               (List.filter_map Fun.id
                                  [ Option.map (fun c -> ("contentType", `String c)) e.content_type; Option.map (fun c -> ("style", `String c)) e.style;
                                    Option.map (fun b -> ("explode", `Bool b)) e.explode ]) )
                         in
                         [ ("body", `Assoc ([ ("field", `String f); ("media", `String m) ] @ if op.encoding = [] then [] else [ ("encoding", `Assoc (List.map enc op.encoding)) ])) ]
                     | None -> [])
                   @ match op.idempotency with Some h -> [ ("idempotency", `String h) ] | None -> []) ))
             t.ops) ) ]

(* ---- egress ------------------------------------------------------------------------------------------ *)

(* where an operation may send: a server's scheme, host, port and base path *)
type endpoint = { scheme : string; host : string; port : int; base : string }

(* A server URL as an endpoint; None for a relative one (it has no host: a deployment's --api-base says
   where to send) and for one this cannot read (a userinfo part, an unknown scheme) *)
let endpoint url =
  match String.index_opt url ':' with
  | Some k when k + 2 < String.length url && String.sub url k 3 = "://" -> (
      let scheme = String.lowercase_ascii (String.sub url 0 k) in
      let rest = String.sub url (k + 3) (String.length url - k - 3) in
      let auth, base = match String.index_opt rest '/' with Some j -> (String.sub rest 0 j, String.sub rest j (String.length rest - j)) | None -> (rest, "/") in
      let default = match scheme with "https" -> Some 443 | "http" -> Some 80 | _ -> None in
      if String.contains auth '@' || auth = "" then None
      else
        (* an IPv6 literal is bracketed; a port follows the last colon outside brackets *)
        let host, port =
          match String.rindex_opt auth ':' with
          | Some c when not (String.contains (String.sub auth c (String.length auth - c)) ']') ->
              (String.sub auth 0 c, int_of_string_opt (String.sub auth (c + 1) (String.length auth - c - 1)))
          | _ -> (auth, default)
        in
        match port with Some port -> Some { scheme; host = String.lowercase_ascii host; port; base } | None -> None)
  | _ -> None

(* the document's servers (variables at their defaults) as the egress a deployment allows its outbox:
   the endpoints, and the servers that name none (relative, or unreadable) *)
let allowlist (t : t) =
  let eps, none = List.partition_map (fun u -> match endpoint u with Some e -> Left e | None -> Right u) t.servers in
  (List.sort_uniq compare eps, none)

let allowlist_json (t : t) : J.t =
  let eps, none = allowlist t in
  `Assoc
    [ ("service", `String t.service);
      ( "endpoints",
        `List
          (List.map (fun e -> `Assoc [ ("scheme", `String e.scheme); ("host", `String e.host); ("port", `Int e.port); ("base", `String e.base) ]) eps) );
      ("unresolved", `List (List.map (fun u -> `String u) none)) ]

(* the allowlist as a pf.conf fragment: a table of hosts per port, and a rule passing the outbox's
   traffic to it ($outbox is the outbox jail's address, the deployment's to define) *)
let pf (t : t) =
  let eps, none = allowlist t in
  let name = String.map (fun c -> match c with 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' | '_' -> c | _ -> '_') t.service in
  let ports = List.sort_uniq compare (List.map (fun e -> e.port) eps) in
  let b = Buffer.create 256 in
  Printf.bprintf b "# egress for %s, from its OpenAPI servers (cimport --print pf); $outbox is the outbox jail's address\n" t.service;
  List.iter (fun u -> Printf.bprintf b "# not allowed (no host): %s\n" u) none;
  List.iter
    (fun port ->
      let hosts = List.sort_uniq compare (List.filter_map (fun e -> if e.port = port then Some e.host else None) eps) in
      Printf.bprintf b "table <cordis_%s_%d> const { %s }\n" name port (String.concat " " hosts);
      Printf.bprintf b "pass out quick proto tcp from $outbox to <cordis_%s_%d> port %d\n" name port port)
    ports;
  Buffer.contents b
