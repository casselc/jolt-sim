(* What the outbox runs: an executor per intent kind. [run] performs the
   intent and returns its receipt and actual cost; [reconcile], for an
   effectful kind, reads the world to say whether an intent whose outcome
   was lost took effect (None: it did not, and the key is fenced). *)

module V = Cordis_cel.Value
module J = Yojson.Safe

type t = { run : V.t -> V.t * int; reconcile : V.t -> (V.t * int) option }

let field v f = match v with V.Map m -> V.map_find m (V.String f) | _ -> None
let text s = V.make_map [ (V.String "text", V.String s) ]

(* llm.complete against an OpenAI-compatible chat-completions endpoint
   (halo-flash serves one); the cost is the tokens the server reports.
   Observational: a completion has no outside effect but its cost. *)
let llm_complete ~url =
  let run req =
    let prompt =
      match field req "prompt" with
      | Some (V.List xs) -> String.concat "\n\n" (Array.to_list (Array.map (function V.String s -> s | v -> V.to_string v) xs))
      | _ -> failwith "llm.complete: no prompt"
    in
    let max_tokens = match field req "max_tokens" with Some (V.Int n) -> Int64.to_int n | _ -> 1024 in
    let body =
      J.to_string
        (`Assoc [ ("model", `String "default"); ("max_tokens", `Int max_tokens);
                  ("messages", `List [ `Assoc [ ("role", `String "user"); ("content", `String prompt) ] ]) ])
    in
    let r = Http.post (url ^ "/v1/chat/completions") body in
    if r.status <> 200 then failwith (Printf.sprintf "llm.complete: HTTP %d" r.status);
    let j = J.from_string r.body in
    let open J.Util in
    let content = j |> member "choices" |> index 0 |> member "message" |> member "content" |> to_string in
    let tokens = match j |> member "usage" |> member "total_tokens" with `Int n -> n | _ -> 0 in
    (text content, tokens)
  in
  { run; reconcile = (fun _ -> None) }

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
  { run; reconcile = (fun req -> Some (run req)) }
