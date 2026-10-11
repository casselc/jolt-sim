(* The OpenAPI importer and its wire encoding against openapi-core
   (docs/cordis/rfc/0003-types-importers-scaffolds.md, revision 4,
   "Testing"), by two Hegel properties over each document given:

   - requests: for a value drawn from an operation's request type, the
     encoder's HTTP request (with the credential the executor would add) is
     valid for openapi-core exactly when the codec's strict read accepts
     the value's JSON, that is, when the value satisfies its schema;
   - responses: for a case drawn from an operation's response variant and
     a payload drawn from its type, the HTTP response is valid for
     openapi-core exactly when the codec accepts the payload, and then the
     decoder reads it back as the same case and value; a status the
     document does not describe at all is Other, which openapi-core calls
     invalid and the RFC calls a definite answer, so only the decoding is
     checked.

     openapi_agreement.exe OASVALID.py DOCUMENT... *)

open Cordis_import
open Typed_values
module J = Yojson.Safe
module V = Cordis_cel.Value
module C = Jsonschema_codec
module H = Openapi_http

let settings =
  let cases = match Sys.getenv_opt "IMPORT_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  let seed = match Sys.getenv_opt "IMPORT_PROPS_SEED" with Some s -> int_of_string s | None -> 41 in
  Hegel.Settings.create ~test_cases:cases ~seed ()

let oasvalid = Sys.argv.(1)
let documents = Array.to_list (Array.sub Sys.argv 2 (Array.length Sys.argv - 2))
let read path = In_channel.with_open_bin path In_channel.input_all

(* one validator per document *)
let procs = Hashtbl.create 4

let validate doc (q : J.t) =
  let ic, oc =
    match Hashtbl.find_opt procs doc with
    | Some p -> p
    | None ->
        let p = Unix.open_process_args "python3" [| "python3"; oasvalid; doc |] in
        Hashtbl.add procs doc p;
        p
  in
  output_string oc (J.to_string q ^ "\n");
  flush oc;
  match J.from_string (input_line ic) with
  | `Assoc (("valid", `Bool b) :: ("errors", `List es) :: rest) -> (b, List.map J.to_string es, List.assoc_opt "body" rest)
  | j -> failwith ("oasvalid: " ^ J.to_string j)

(* the same body, as JSON: numbers by value; an empty list or object, or null, is absent (the encoder
   leaves an empty one out, and openapi-core reads an absent multipart list as []) *)
let rec same_json (a : J.t) (b : J.t) =
  let num = function `Int i -> Some (float_of_int i) | `Float f -> Some f | `Intlit s -> float_of_string_opt s | _ -> None in
  let present = List.filter (fun (_, v) -> v <> `List [] && v <> `Assoc [] && v <> `Null) in
  match a, b with
  | `Assoc x, `Assoc y ->
      let x = present x and y = present y in
      List.length x = List.length y && List.for_all (fun (k, v) -> match List.assoc_opt k y with Some w -> same_json v w | None -> false) x
  | `List x, `List y -> List.length x = List.length y && List.for_all2 same_json x y
  | _ -> (match num a, num b with Some x, Some y -> x = y | _ -> a = b)

let request_json (r : H.request) : J.t =
  `Assoc
    [ ("method", `String r.meth); ("target", `String r.target);
      ("headers", `List (List.map (fun (k, v) -> `List [ `String k; `String v ]) r.headers));
      ("body", match r.body with Some b -> `String b | None -> `Null);
      ("media", match r.media with Some m -> `String m | None -> `Null) ]

(* the credential the executor adds, for each apiKey scheme the operation names *)
let with_credentials doc (op : Openapi.operation) (r : H.request) =
  let schemes = match doc with `Assoc kv -> (match List.assoc_opt "components" kv with Some (`Assoc c) -> (match List.assoc_opt "securitySchemes" c with Some (`Assoc s) -> s | _ -> []) | _ -> []) | _ -> [] in
  List.fold_left
    (fun (r : H.request) name ->
      match List.assoc_opt name schemes with
      | Some (`Assoc s) -> (
          match List.assoc_opt "type" s, List.assoc_opt "in" s, List.assoc_opt "name" s with
          | Some (`String "apiKey"), Some (`String "header"), Some (`String h) -> { r with headers = (h, "test") :: r.headers }
          | Some (`String "apiKey"), Some (`String "query"), Some (`String q) ->
              { r with target = r.target ^ (if String.contains r.target '?' then "&" else "?") ^ q ^ "=test" }
          | Some (`String "http"), _, _ -> { r with headers = ("Authorization", "Bearer test") :: r.headers }
          | _ -> r)
      | _ -> r)
    r op.security

let loaded =
  List.map
    (fun path ->
      let doc = match Openapi.document (read path) with Ok d -> d | Error m -> failwith m in
      (path, doc, Openapi.import doc))
    documents

let read_back = ref 0

let test_requests () =
  Fun.protect ~finally:(fun () -> Printf.printf "%d form or multipart bodies read back alike\n" !read_back) @@ fun () ->
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:openapi-requests" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let path, doc, t = choose d loaded in
      pools_for doc;
      let op = choose d t.ops in
      (* deep enough for a body's nested record to have fields: each optional, name and record spends a level *)
      let v = value t.imp d 7 (Named op.request) in
      match C.write t.imp op.request v with
      | Error e -> Hegel.require tc ~msg:("the codec could not write a value of its type: " ^ e) false
      | Ok j -> (
          let accepted = C.read t.imp op.request j in
          match H.encode t ~kind:op.kind ~key:"k-1" v with
          | Error e ->
              (* a value the wire cannot carry: an empty path segment *)
              Hegel.require tc ~msg:("the encoder refused: " ^ e) (Openapi_http.unsendable e)
          | Ok r ->
              let r = with_credentials doc op r in
              let q = request_json r in
              Hegel.note tc (Printf.sprintf "%s %s\n%s\ncodec: %s" path op.kind (J.to_string q) (match accepted with Ok _ -> "accepts" | Error e -> e));
              let valid, errors, read = validate path (`Assoc [ ("request", q) ]) in
              Hegel.require tc
                ~msg:(Printf.sprintf "openapi-core says %b (%s), the codec %s" valid (String.concat "; " errors) (match accepted with Ok _ -> "accepts" | Error e -> "refuses: " ^ e))
                (valid = Result.is_ok accepted);
              (* a form's or multipart body's fields read back as the body that was written (a field written
                 in the wrong style reads back absent, which validity alone would not see) *)
              match r.media, read, j with
              | Some m, Some read, `Assoc kv when Openapi.is_form m || Openapi.is_multipart m -> (
                  match List.assoc_opt "body" kv with
                  | Some body ->
                      Hegel.note tc (Printf.sprintf "openapi-core read %s" (J.to_string read));
                      Hegel.require tc ~msg:"openapi-core reads back a different body" (same_json body read);
                      incr read_back
                  | None -> ())
              | _ -> ()))

let status_of (c : Openapi.case) = if String.length c.status = 3 && c.status.[1] = 'X' then (Char.code c.status.[0] - 48) * 100 + 18 else int_of_string c.status

let test_responses () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:openapi-responses" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let path, doc, t = choose d loaded in
      pools_for doc;
      let op = choose d t.ops in
      let req = match H.encode t ~kind:op.kind ~key:"k-1" (value t.imp d 0 (Named op.request)) with Ok r -> with_credentials doc op r | Error _ -> { H.meth = op.meth; target = op.path; headers = []; body = None; media = None } in
      (* a documented case, or a status nothing documents *)
      let cases = List.map Option.some op.cases @ [ None ] in
      let status, payload, case =
        match choose d cases with
        | Some c -> (status_of c, c.payload, c.case)
        | None ->
            let s = choose d (List.filter (fun s -> not (List.exists (fun (c : Openapi.case) -> H.status_matches c s) op.cases)) [ 418; 503; 299 ]) in
            (s, List.assoc_opt op.default t.defaults, "Other")
      in
      let v, body =
        match payload with
        | None -> (None, "")
        | Some n -> (
            let v = value t.imp d 5 (Named n) in
            match C.write t.imp n v with Ok j -> (Some (n, v), J.to_string j) | Error e -> failwith e)
      in
      let accepted = match v with Some (n, v) -> Result.map (fun _ -> ()) (C.read t.imp n (match C.write t.imp n v with Ok j -> j | Error e -> failwith e)) | None -> Ok () in
      let media = match List.find_opt (fun (c : Openapi.case) -> c.case = case) op.cases with Some { media = Some m; _ } -> m | _ -> "application/json" in
      let q = `Assoc [ ("request", request_json req); ("response", `Assoc [ ("status", `Int status); ("body", `String body); ("media", `String media) ]) ] in
      Hegel.note tc (Printf.sprintf "%s %s %d %s" path op.kind status body);
      let valid, errors, _ = validate path q in
      (* a status the document does not describe at all (no case, no default) is invalid for
         openapi-core; the RFC makes it a definite answer, Other, which is all there is to check *)
      let described = case <> "Other" || List.mem_assoc op.default t.defaults in
      if described then
        Hegel.require tc
          ~msg:(Printf.sprintf "openapi-core says %b (%s), the codec %s" valid (String.concat "; " errors) (match accepted with Ok _ -> "accepts" | Error e -> "refuses: " ^ e))
          (valid = Result.is_ok accepted);
      if (if described then valid else true) then
        match H.decode t ~kind:op.kind ~status ~body with
        | Error e -> Hegel.require tc ~msg:("the decoder refused a valid response: " ^ e) false
        | Ok got ->
            let want =
              match case, v with
              | "Other", Some (_, v) -> V.make_map [ (V.String "tag", V.String "Other"); (V.String "value", V.make_map [ (V.String "status", V.Int (Int64.of_int status)); (V.String "body", v) ]) ]
              | "Other", None -> V.make_map [ (V.String "tag", V.String "Other"); (V.String "value", V.make_map [ (V.String "status", V.Int (Int64.of_int status)); (V.String "body", V.Null) ]) ]
              | c, Some (_, v) -> V.make_map [ (V.String "tag", V.String c); (V.String "value", v) ]
              | c, None -> V.make_map [ (V.String "tag", V.String c) ]
            in
            Hegel.require tc ~msg:(Printf.sprintf "decoded %s, not %s" (V.to_string got) (V.to_string want)) (same got want))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "openapi-agreement"
    [ ( "properties",
        [ Alcotest.test_case "requests: openapi-core accepts exactly what the codec accepts" `Quick test_requests;
          Alcotest.test_case "responses: accepted alike, and decoded to the same value" `Quick test_responses ] ) ]
