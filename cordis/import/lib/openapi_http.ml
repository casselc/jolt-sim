(* An imported operation's requests and responses on the wire (docs/cordis/
   rfc/0003-types-importers-scaffolds.md, revision 4): the http executor's
   pure half. [encode] renders an intent's request value as an HTTP
   request, by the operation map; [decode] reads an HTTP response as the
   response variant's value. Sending, the credential and the server are the
   executor's.

   Parameters are serialised by their style: path and header `simple`,
   query `form` (exploded or not) and `deepObject`, cookie `form`. The body
   is the JSON Schema codec's JSON; a form (application/x-www-form-
   urlencoded) or multipart/form-data body writes each of its properties as
   a field or a part, as its Encoding Object says (a form's by style, form
   and exploded by default; a part's content type text/plain for a
   primitive, application/json for anything else); text is the string
   itself. Reading
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

(* ---- bodies ---------------------------------------------------------------------------------------- *)

(* a form's or multipart body's properties; an empty list or object has no field or part of its own (it
   would read back as absent), so it is left out, and a required one cannot be sent *)
let props ~required what (j : J.t) =
  match j with
  | `Assoc kv ->
      List.filter
        (fun (k, x) ->
          match x with
          | `List [] | `Assoc [] ->
              if List.mem k required then raise (C.Bad (Printf.sprintf "not sendable: the %s body's property %s is required and empty" what k));
              false
          | _ -> true)
        kv
  | _ -> raise (C.Bad (Printf.sprintf "the %s body is not an object" what))

let encoding_of (op : operation) k = List.assoc_opt k op.encoding

(* application/x-www-form-urlencoded: each property a field, written as a query parameter of its style
   would be *)
let form_body ~required (op : operation) j =
  String.concat "&"
    (List.concat_map
       (fun (k, x) ->
         let e = encoding_of op k in
         match Option.bind e (fun e -> e.content_type) with
         | Some ct when Openapi.is_json ct -> [ pct k ^ "=" ^ pct (J.to_string x) ]
         | _ ->
             let style = Option.value (Option.bind e (fun e -> e.style)) ~default:"form" in
             let explode = Option.value (Option.bind e (fun e -> e.explode)) ~default:(style = "form") in
             List.map (fun (a, b) -> a ^ "=" ^ b) (query_pairs { field = k; wire = k; loc = Query; style; explode; required = false } x))
       (props ~required "form" j))

(* multipart/form-data: each property a part (a list one part per item), named by the property; the
   boundary is a digest of the parts, so the same body is the same bytes, and occurs in none of them *)
let multipart_body ~required (op : operation) j =
  let part k x =
    let ct = match Option.bind (encoding_of op k) (fun e -> e.content_type) with Some c -> c | None -> (match x with `Assoc _ | `List _ -> "application/json" | _ -> "text/plain") in
    (k, ct, if Openapi.is_json ct then J.to_string x else text x)
  in
  (* a list is a part per item, unless its Encoding Object makes it one JSON part *)
  let json_part k = match Option.bind (encoding_of op k) (fun e -> e.content_type) with Some c -> Openapi.is_json c | None -> false in
  let parts = List.concat_map (fun (k, x) -> match x with `List xs when not (json_part k) -> List.map (part k) xs | x -> [ part k x ]) (props ~required "multipart" j) in
  let rec boundary n =
    let b = "cordis-" ^ String.sub (Cordis_cel.Sha256.digest (string_of_int n ^ String.concat "\n" (List.map (fun (_, _, c) -> c) parts))) 0 24 in
    if List.exists (fun (k, _, c) -> Schema_pred.replace ~sub:b ~by:"" (k ^ c) <> k ^ c) parts then boundary (n + 1) else b
  in
  let b = boundary 0 in
  let quote k = Schema_pred.replace ~sub:"\"" ~by:"%22" (Schema_pred.replace ~sub:"\r" ~by:"%0D" (Schema_pred.replace ~sub:"\n" ~by:"%0A" k)) in
  let body =
    String.concat ""
      (List.map (fun (k, ct, c) -> Printf.sprintf "--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\nContent-Type: %s\r\n\r\n%s\r\n" b (quote k) ct c) parts)
    ^ Printf.sprintf "--%s--\r\n" b
  in
  (body, "multipart/form-data; boundary=" ^ b)

(* ---- requests -------------------------------------------------------------------------------------- *)

(* a value the codec accepts that HTTP cannot carry *)
let unsendable e = String.length e >= 13 && String.sub e 0 13 = "not sendable:"

let fields (t : t) n = match List.assoc_opt n t.imp.defs with Some (JS.Record fs) -> fs | _ -> raise (C.Bad ("no record " ^ n))

(* the properties a body requires: its record's required fields, by the request's body field *)
let body_required (t : t) (fs : JS.field list) f =
  let rec named = function JS.Named n -> Some n | JS.Opt ty -> named ty | _ -> None in
  match List.find_opt (fun (x : JS.field) -> x.json = f) fs with
  | Some x -> (
      match Option.bind (named x.ty) (fun n -> List.assoc_opt n t.imp.defs) with
      | Some (JS.Record gs) -> List.filter_map (fun (g : JS.field) -> if g.required then Some g.json else None) gs
      | _ -> [])
  | None -> []

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
          | Some j ->
              if Openapi.is_json media then (Some (J.to_string j), Some media)
              else if Openapi.is_form media then (Some (form_body ~required:(body_required t fs f) op j), Some media)
              else if Openapi.is_multipart media then (let b, m = multipart_body ~required:(body_required t fs f) op j in (Some b, Some m))
              else (Some (text j), Some media))
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
