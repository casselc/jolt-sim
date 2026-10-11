(* A minimal HTTP/1.1 client for the outbox's executors: send a request
   (any method, a body if any) to a plain-HTTP endpoint (a local
   OpenAI-compatible server, such as halo-flash's, or an imported API
   behind the egress proxy) and read the answer, with a timeout. TLS is the platform's
   egress proxy's job, not this client's. *)

type response = { status : int; body : string }

exception Failed of string

let fail fmt = Printf.ksprintf (fun m -> raise (Failed m)) fmt

(* http://host:port/path -> (host, port, path) *)
let parse_url url =
  let prefix = "http://" in
  let n = String.length prefix in
  if String.length url < n || String.sub url 0 n <> prefix then fail "only http:// URLs: %s" url;
  let rest = String.sub url n (String.length url - n) in
  let hostport, path =
    match String.index_opt rest '/' with
    | Some i -> (String.sub rest 0 i, String.sub rest i (String.length rest - i))
    | None -> (rest, "/")
  in
  match String.split_on_char ':' hostport with
  | [ h; p ] -> (h, int_of_string p, path)
  | [ h ] -> (h, 80, path)
  | _ -> fail "bad host in %s" url

let read_all fd =
  let buf = Buffer.create 4096 and chunk = Bytes.create 4096 in
  let rec go () =
    match Unix.read fd chunk 0 4096 with
    | 0 -> ()
    | k -> Buffer.add_subbytes buf chunk 0 k; go ()
    | exception Unix.Unix_error ((Unix.EAGAIN | Unix.EWOULDBLOCK), _, _) -> fail "timed out reading the response"
  in
  go ();
  Buffer.contents buf

let dechunk s =
  let out = Buffer.create (String.length s) in
  let rec go i =
    match String.index_from_opt s i '\r' with
    | None -> ()
    | Some j ->
        let size = int_of_string ("0x" ^ String.trim (List.hd (String.split_on_char ';' (String.sub s i (j - i))))) in
        if size > 0 then begin
          Buffer.add_string out (String.sub s (j + 2) size);
          go (j + 2 + size + 2)
        end
  in
  go 0;
  Buffer.contents out

let request ?(timeout = 120.) ?(headers = []) ~meth url (body : (string * string) option) =
  let host, port, path = parse_url url in
  let addr =
    match Unix.getaddrinfo host (string_of_int port) [ Unix.AI_SOCKTYPE Unix.SOCK_STREAM ] with
    | a :: _ -> a.Unix.ai_addr
    | [] -> fail "cannot resolve %s" host
  in
  let fd = Unix.socket (Unix.domain_of_sockaddr addr) Unix.SOCK_STREAM 0 in
  Fun.protect ~finally:(fun () -> Unix.close fd) @@ fun () ->
  Unix.setsockopt_float fd Unix.SO_RCVTIMEO timeout;
  Unix.setsockopt_float fd Unix.SO_SNDTIMEO timeout;
  (try Unix.connect fd addr with Unix.Unix_error (e, _, _) -> fail "connect %s:%d: %s" host port (Unix.error_message e));
  let content = match body with Some (media, b) -> Printf.sprintf "Content-Type: %s\r\nContent-Length: %d\r\n" media (String.length b) | None -> "" in
  let req =
    Printf.sprintf "%s %s HTTP/1.1\r\nHost: %s:%d\r\n%sConnection: close\r\n%s\r\n%s" meth path host port content
      (String.concat "" (List.map (fun (k, v) -> k ^ ": " ^ v ^ "\r\n") headers))
      (match body with Some (_, b) -> b | None -> "")
  in
  let b = Bytes.of_string req in
  let rec send off = if off < Bytes.length b then send (off + Unix.write fd b off (Bytes.length b - off)) in
  send 0;
  let raw = read_all fd in
  let sep = "\r\n\r\n" in
  let rec find i = if i + 4 > String.length raw then fail "no header end" else if String.sub raw i 4 = sep then i else find (i + 1) in
  let h = find 0 in
  let head = String.sub raw 0 h and body = String.sub raw (h + 4) (String.length raw - h - 4) in
  let status = match String.split_on_char ' ' (List.hd (String.split_on_char '\r' head)) with _ :: code :: _ -> int_of_string code | _ -> fail "bad status line" in
  let lower = String.lowercase_ascii head in
  let chunked =
    let k = "transfer-encoding: chunked" in
    let n = String.length k in
    let rec has i = i + n <= String.length lower && (String.sub lower i n = k || has (i + 1)) in
    has 0
  in
  { status; body = (if chunked then dechunk body else body) }

let post ?timeout ?headers url body = request ?timeout ?headers ~meth:"POST" url (Some ("application/json", body))
