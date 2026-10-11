(* An imported operation's requests and responses on the wire (docs/cordis/
   rfc/0003-types-importers-scaffolds.md, revision 4): the http executor's
   pure half. [encode] renders an intent's request value as an HTTP
   request, by the operation map; [decode] reads an HTTP response as the
   response variant's value. Sending, the credential and the server are the
   executor's.

   Parameters are serialised by their style: path and header `simple`,
   query `form` (exploded or not) and `deepObject`, cookie `form`. The body
   is the JSON Schema codec's JSON, or the string itself for text. Reading
   a response is lenient about properties a server adds (they are
   dropped); everything else is as strict as the codec. *)

module J = Yojson.Safe
module V = Cordis_cel.Value
module JS = Jsonschema
module C = Jsonschema_codec
open Openapi

type request = {
  meth : string;
  target : string;  (** the path, with its query, percent-encoded *)
  headers : (string * string) list;  (** without the credential, which the executor adds *)
  body : string option;
  media : string option;
}

let operation (t : t) kind = match List.find_opt (fun op -> op.kind = kind) t.ops with Some op -> op | None -> raise (C.Bad ("no operation " ^ kind))

(* ---- percent-encoding (RFC 3986) ------------------------------------------------------------------- *)

let unreserved = function 'A' .. 'Z' | 'a' .. 'z' | '0' .. '9' | '-' | '.' | '_' | '~' -> true | _ -> false

let pct ?(keep = fun _ -> false) s =
  let b = Buffer.create (String.length s) in
  String.iter (fun c -> if unreserved c || keep c then Buffer.add_char b c else Buffer.add_string b (Printf.sprintf "%%%02X" (Char.code c))) s;
  Buffer.contents b

(* ---- parameters ------------------------------------------------------------------------------------ *)

(* a primitive's text *)
let text (j : J.t) =
  match j with
  | `String s -> s
  | `Int i -> string_of_int i
  | `Intlit s -> s
  | `Float f -> if Float.is_integer f && Float.abs f < 1e15 then Printf.sprintf "%.0f" f else Printf.sprintf "%.17g" f
  | `Bool b -> string_of_bool b
  | `Null -> ""
  | j -> J.to_string j

(* simple style: a list joined by commas; an object k,v,... or, exploded, k=v,... *)
let simple ~explode ~enc (j : J.t) =
  match j with
  | `List xs -> String.concat "," (List.map (fun x -> enc (text x)) xs)
  | `Assoc kv -> String.concat "," (List.map (fun (k, x) -> if explode then enc k ^ "=" ^ enc (text x) else enc k ^ "," ^ enc (text x)) kv)
  | j -> enc (text j)

(* query pairs for one parameter, encoded *)
let query_pairs (p : param) (j : J.t) =
  let e = pct in
  match p.style, j with
  | "deepObject", `Assoc kv -> List.map (fun (k, x) -> (e p.wire ^ "%5B" ^ e k ^ "%5D", e (text x))) kv
  | _, `List xs when p.explode -> List.map (fun x -> (e p.wire, e (text x))) xs
  | _, `Assoc kv when p.explode -> List.map (fun (k, x) -> (e k, e (text x))) kv
  | _, j -> [ (e p.wire, simple ~explode:false ~enc:e j) ]

(* ---- requests -------------------------------------------------------------------------------------- *)

(* a value the codec accepts that HTTP cannot carry *)
let unsendable e = String.length e >= 13 && String.sub e 0 13 = "not sendable:"

let fields (t : t) n = match List.assoc_opt n t.imp.defs with Some (JS.Record fs) -> fs | _ -> raise (C.Bad ("no record " ^ n))

let encode (t : t) ~kind ~key (v : V.t) : (request, string) result =
  match
    let op = operation t kind in
    let fs = fields t op.request in
    let m = match v with V.Map m -> m | _ -> raise (C.Bad "the request is not a record") in
    (* each present field as JSON, by the field's own type *)
    let json name =
      match List.find_opt (fun (f : JS.field) -> f.json = name) fs with
      | None -> None
      | Some f -> (
          match C.field_of m f.name with
          | V.Optional None -> None
          | x -> Some (C.to_json t.imp f.ty x))
    in
    (* a parameter's value: an empty list or object has no form of its own on the wire (it reads back
       as one empty item, or as nothing), so it is left out, and a required one cannot be sent *)
    let param (p : param) =
      match json p.field with
      | Some (`List [] | `Assoc []) ->
          if p.required then raise (C.Bad ("not sendable: the parameter " ^ p.wire ^ " is required and empty"));
          None
      | j -> j
    in
    let path =
      List.fold_left
        (fun path (p : param) ->
          if p.loc <> Path then path
          else
            match param p with
            | None -> raise (C.Bad ("no value for the path parameter " ^ p.wire))
            | Some j ->
                let seg = simple ~explode:p.explode ~enc:pct j in
                if seg = "" then raise (C.Bad ("not sendable: the path parameter " ^ p.wire ^ " is empty"));
                Schema_pred.replace ~sub:("{" ^ p.wire ^ "}") ~by:seg path)
        op.path op.params
    in
    let query = List.concat_map (fun (p : param) -> if p.loc <> Query then [] else match param p with None -> [] | Some j -> query_pairs p j) op.params in
    let target = if query = [] then path else path ^ "?" ^ String.concat "&" (List.map (fun (k, v) -> k ^ "=" ^ v) query) in
    let headers =
      List.filter_map
        (fun (p : param) -> if p.loc <> Header then None else Option.map (fun j -> (p.wire, simple ~explode:p.explode ~enc:Fun.id j)) (param p))
        op.params
    in
    let cookies =
      List.filter_map (fun (p : param) -> if p.loc <> Cookie then None else Option.map (fun j -> p.wire ^ "=" ^ simple ~explode:false ~enc:pct j) (param p)) op.params
    in
    let headers = headers @ (if cookies = [] then [] else [ ("Cookie", String.concat "; " cookies) ]) in
    let headers = match op.idempotency with Some h -> headers @ [ (h, key) ] | None -> headers in
    let body, media =
      match op.body with
      | None -> (None, None)
      | Some (f, media) -> (
          match json f with
          | None -> (None, None)
          | Some j -> if Openapi.is_json media then (Some (J.to_string j), Some media) else (Some (text j), Some media))
    in
    { meth = op.meth; target; headers; body; media }
  with
  | r -> Ok r
  | exception C.Bad m -> Error m

(* ---- responses ------------------------------------------------------------------------------------- *)

let status_matches (c : case) status =
  let s = string_of_int status in
  c.status = s || (String.length c.status = 3 && c.status.[1] = 'X' && c.status.[0] = s.[0])

let decode (t : t) ~kind ~status ~(body : string) : (V.t, string) result =
  match
    let op = operation t kind in
    let tagged c v = V.make_map ((V.String "tag", V.String c) :: match v with Some v -> [ (V.String "value", v) ] | None -> []) in
    (* an exact status before a range *)
    let exact = List.find_opt (fun (c : case) -> c.status = string_of_int status) op.cases in
    let case = match exact with Some c -> Some c | None -> List.find_opt (fun c -> status_matches c status) op.cases in
    let json () = match J.from_string body with j -> j | exception Yojson.Json_error m -> raise (C.Bad ("the body is not JSON: " ^ m)) in
    let read n j = match C.read ~drop_unknown:true t.imp n j with Ok v -> v | Error m -> raise (C.Bad m) in
    match case with
    | Some c -> (
        match c.payload with
        | None -> tagged c.case None
        | Some n ->
            let j = match c.media with Some m when Openapi.is_json m -> json () | _ -> `String body in
            tagged c.case (Some (read n j)))
    | None ->
        (* Other: the status and the body, typed if the default response gives it a type *)
        let b =
          match List.assoc_opt op.default t.defaults with
          | Some n -> read n (json ())
          | None -> if body = "" then V.Null else (match J.from_string body with j -> C.dyn j | exception Yojson.Json_error _ -> V.String body)
        in
        tagged "Other" (Some (V.make_map [ (V.String "status", V.Int (Int64.of_int status)); (V.String "body", b) ]))
  with
  | v -> Ok v
  | exception C.Bad m -> Error m
