(* What the outbox runs: an executor per intent kind. [run] performs the
   intent and returns its receipt and actual cost; [reconcile], for an
   effectful or keyed kind, reads the world to say whether an intent whose
   outcome was lost took effect (None: it did not, and the key is fenced).
   Both are given the intent's key, which a keyed kind sends as its
   idempotency key. *)

module V = Cordis_cel.Value
module J = Yojson.Safe

type t = { run : key:string -> V.t -> V.t * int; reconcile : key:string -> V.t -> (V.t * int) option }

(* The idempotency key a call carries (an Idempotency-Key header): the same for every send of one
   intent, and different for any other. The intent's key alone is not enough: it names a computation
   within one graph instance (review_loop/verdict#1.1 in every run of the graph), and a server keeping
   keys would replay one run's answer into another. So it digests the agent (the instance, as the
   outbox's --agent names it), the intent's key and its request. *)
let idempotency_key ~agent ~key ~request =
  "cordis-" ^ String.sub (Cordis_cel.Sha256.digest (String.concat "\n" [ agent; key; Yojson.Safe.to_string (Cordis_engine.Codec.value request) ])) 0 32

(* How a call fails, besides with no answer at all (any other exception: the outcome is unknown, a
   lost receipt): *)
(* a definite failure: the world refused it, and it did not run (an HTTP 4xx) *)
exception Refused of string

(* not run now, try later (HTTP 429 or 503): deferred, definitely not executed *)
exception Busy of string

(* An HTTP status as one of those; 2xx is the caller's to read *)
let classify_status what status =
  if status = 429 || status = 503 then raise (Busy (Printf.sprintf "%s: HTTP %d" what status))
  else if status >= 400 && status < 500 && status <> 408 then raise (Refused (Printf.sprintf "%s: HTTP %d" what status))
  else if status < 200 || status >= 300 then failwith (Printf.sprintf "%s: HTTP %d" what status)

let field v f = match v with V.Map m -> V.map_find m (V.String f) | _ -> None
let text s = V.make_map [ (V.String "text", V.String s) ]

(* A completion read from a chat-completions answer, streamed (server-sent events) or not: its text, why
   it stopped, and the tokens the server reports (None: it did not say) *)
let read_completion body =
  let open J.Util in
  let content = Buffer.create 1024 and finish = ref "" and tokens = ref None and reasoning = ref 0 in
  let usage j =
    match j |> member "usage" with
    | `Assoc _ as u ->
        (match u |> member "total_tokens" with `Int n -> tokens := Some n | _ -> ());
        (match u |> member "completion_tokens_details" with
         | `Assoc _ as d -> (match d |> member "reasoning_tokens" with `Int n -> reasoning := n | _ -> ())
         | _ -> ())
    | _ -> ()
  in
  let choice j =
    match j |> member "choices" with
    | `List (c :: _) ->
        (match c |> member "delta" with `Assoc _ as d -> (match d |> member "content" with `String s -> Buffer.add_string content s | _ -> ()) | _ -> ());
        (match c |> member "message" with `Assoc _ as m -> (match m |> member "content" with `String s -> Buffer.add_string content s | _ -> ()) | _ -> ());
        (match c |> member "finish_reason" with `String f -> finish := f | _ -> ())
    | _ -> ()
  in
  let lines = String.split_on_char '\n' body in
  if List.exists (fun l -> String.length l >= 5 && String.sub l 0 5 = "data:") lines then
    List.iter
      (fun l ->
        let l = String.trim l in
        if String.length l >= 5 && String.sub l 0 5 = "data:" then
          let d = String.trim (String.sub l 5 (String.length l - 5)) in
          if d <> "[DONE]" then match J.from_string d with j -> choice j; usage j | exception Yojson.Json_error _ -> ())
      lines
  else (let j = J.from_string body in choice j; usage j);
  (Buffer.contents content, !finish, !tokens, !reasoning)

(* llm.complete against an OpenAI-compatible chat-completions endpoint
   (halo-flash serves one), streamed, so the client's timeout is a limit on
   silence, not on a long answer. A request's thinking field, if it has
   one, says whether the model may reason first (chat_template_kwargs'
   enable_thinking: halo-flash reasons by default, and its reasoning counts
   against max_tokens). Every send carries the intent's idempotency key: a
   server that keeps keys (halo-flash's scheduler, docs/SCHEDULER.md
   section 7) replays a finished answer and adopts one still running, so a
   send again after a lost answer does not generate again, and the kind
   may be run keyed (cordis-run --keyed llm.complete). The receipt is the text, the reasoning tokens the
   server reports, and why it
   stopped (a completion cut off by its length is not one that finished);
   the cost is the tokens the server reports, or unknown (charged the
   worst case) when it does not say. Observational: a completion has no
   outside effect but its cost. *)
let llm_complete ?(idle = 120.) ~url () =
  let run ~key req =
    let prompt =
      match field req "prompt" with
      | Some (V.List xs) -> String.concat "\n\n" (Array.to_list (Array.map (function V.String s -> s | v -> V.to_string v) xs))
      | _ -> failwith "llm.complete: no prompt"
    in
    let max_tokens = match field req "max_tokens" with Some (V.Int n) -> Int64.to_int n | _ -> 1024 in
    (* a model that thinks by default spends max_tokens on it: the request says whether it may *)
    let thinking = match field req "thinking" with Some (V.Bool b) -> [ ("chat_template_kwargs", `Assoc [ ("enable_thinking", `Bool b) ]) ] | _ -> [] in
    let body =
      J.to_string
        (`Assoc
          ([ ("model", `String "default"); ("max_tokens", `Int max_tokens); ("stream", `Bool true);
             ("stream_options", `Assoc [ ("include_usage", `Bool true) ]);
             ("messages", `List [ `Assoc [ ("role", `String "user"); ("content", `String prompt) ] ]) ]
          @ thinking))
    in
    let r = Http.post ~timeout:idle ~headers:[ ("Idempotency-Key", key) ] (url ^ "/v1/chat/completions") body in
    classify_status "llm.complete" r.status;
    let content, finish, tokens, reasoning = read_completion r.body in
    ( V.make_map [ (V.String "text", V.String content); (V.String "finish", V.String finish); (V.String "reasoning", V.Int (Int64.of_int reasoning)) ],
      match tokens with Some n -> n | None -> -1 )
  in
  (* a send again with the same key is answered by a server that keeps keys from what it kept (a finished
     answer replayed, one still running adopted), so reconciling is sending again *)
  { run = (fun ~key req -> run ~key req); reconcile = (fun ~key req -> Some (run ~key req)) }

(* repo.read: a file under the repository root. Reading has no outside
   effect, so reconciling is reading again. *)
let repo_read ~root =
  let run req =
    let path = match req with V.String p -> p | _ -> failwith "repo.read: not a path" in
    if String.length path = 0 || path.[0] = '/' || List.mem ".." (String.split_on_char '/' path) then failwith ("repo.read: outside the repository: " ^ path);
    let file = Filename.concat root path in
    let content = if Sys.file_exists file then In_channel.with_open_bin file In_channel.input_all else "" in
    (text content, 1)
  in
  { run = (fun ~key:_ req -> run req); reconcile = (fun ~key:_ req -> Some (run req)) }

(* An imported API (RFC 0003 revision 4): an executor per operation, sending
   to [base] (the document's server, or the egress proxy's address for it)
   with the credential for the operation's scheme added where the scheme
   says (an API key's header, query parameter or cookie; a bearer or basic
   Authorization header); [credential] gives a scheme's secret. Any HTTP
   status is a definite answer, read as the response variant; no response
   at all is a lost receipt. Each call costs one unit. A keyed or
   observational operation reconciles by sending again with the same key,
   which a keyed one's server deduplicates; an effectful one cannot be
   asked without a read the policy names, so it fails loudly rather than
   answer None, which would say it did not happen. *)
let openapi ~(api : Cordis_import.Openapi.t) ~base ~(credential : string -> string option) =
  let module O = Cordis_import.Openapi in
  let module H = Cordis_import.Openapi_http in
  List.map
    (fun (op : O.operation) ->
      let run ~key req =
        let r = match H.encode api ~kind:op.kind ~key req with Ok r -> r | Error m -> failwith (op.kind ^ ": " ^ m) in
        (* the first of the operation's schemes the outbox holds a credential for *)
        let r =
          match List.find_map (fun s -> match List.assoc_opt s api.schemes, credential s with Some sc, Some c -> Some (sc, c) | _ -> None) op.security with
          | None -> r
          | Some (O.Api_key (O.Header, h), c) -> { r with headers = r.headers @ [ (h, c) ] }
          | Some (O.Api_key (O.Query, q), c) -> { r with target = r.target ^ (if String.contains r.target '?' then "&" else "?") ^ H.pct q ^ "=" ^ H.pct c }
          | Some (O.Api_key (O.Cookie, k), c) -> { r with headers = r.headers @ [ ("Cookie", k ^ "=" ^ H.pct c) ] }
          | Some (O.Api_key (O.Path, _), _) -> r
          | Some (O.Bearer, c) -> { r with headers = r.headers @ [ ("Authorization", "Bearer " ^ c) ] }
          | Some (O.Basic, c) -> { r with headers = r.headers @ [ ("Authorization", "Basic " ^ c) ] }
        in
        let body = match r.body with Some b -> Some (Option.value r.media ~default:"application/json", b) | None -> None in
        let resp = Http.request ~timeout:30. ~headers:r.headers ~meth:r.meth (base ^ r.target) body in
        match H.decode api ~kind:op.kind ~status:resp.status ~body:resp.body with
        | Ok v -> (v, 1)
        | Error m -> failwith (Printf.sprintf "%s: HTTP %d, unreadable: %s" op.kind resp.status m)
      in
      let reconcile ~key req =
        match op.cls with
        | O.Keyed | O.Observational -> Some (run ~key req)
        | O.Effectful -> failwith (op.kind ^ ": an effectful operation cannot be reconciled without a read the policy names")
      in
      (op.kind, { run; reconcile }))
    api.ops
