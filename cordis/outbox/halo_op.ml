(* llm.complete through halo's operation API (docs/cordis/rfc/0007-boundaries.md, "Verbs" and "The durable
   handoff"; quint/operation.qnt, capture.qnt, expiry.qnt). The attempt is the unit: (owner, operation id,
   attempt), and the operation id is the intent's idempotency key. A submit is journaled before it is sent, as the
   exact bytes, with the incarnation it names and its deadline, and every send of it is those bytes (rule 4): its
   SHA-256 is the attempt's digest, carried by every other verb. The call returns once the result is fetched; the
   outbox journals it, and only then runs [committed], which acknowledges halo, which releases the body (commit
   before ack). A lost acknowledgement only keeps the body longer; [recover] repeats those it still owes.

   What each answer does (RFC 0007, "Status answers"):
   - 202/200 accepted or a duplicate's view: wait for done;
   - 429/503: this copy was not accepted: Busy, the attempt is kept and sent again as first sent;
   - 410, closed, cancelled_before_accept: terminal, nothing spent: Refused, the attempt settled;
   - unknown, stale incarnation: the old incarnation's work is beyond reach: a lost receipt (charged the worst case),
     the attempt settled, never sent into another incarnation;
   - never_accepted: sent again as first sent;
   - expired: the body is gone; settled like a lost receipt (the actual usage it reports is not yet carried: RFC 0007,
     open questions).
   The attempt's records live in the adapter's own table, beside the journal, in the same file. *)

module V = Cordis_cel.Value
module J = Yojson.Safe
open Executors

type cfg = {
  url : string;
  principal : string;  (** X-Halo-Principal: the owner of the attempts *)
  mode : string;  (** "relaxed" or "exact": what the intent needs, which the server must run *)
  window_ms : int;  (** how long, by halo's clock, a submit may still be accepted *)
  poll_s : float;
  wait_s : float;  (** how long one call waits for a result before it gives up with no answer (it resumes the same attempt) *)
}

let default_cfg ~url ~principal = { url; principal; mode = "relaxed"; window_ms = 120_000; poll_s = 0.1; wait_s = 300. }

type submit = {
  op : string;
  attempt : int;
  incarnation : string;
  not_after : int;
  body : string;  (** the exact bytes sent *)
  digest : string;
  mutable settled : bool;
}

(* ---- the adapter's own records ---------------------------------------------------------------- *)

let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "halo_op %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

let open_store path =
  let db = Sqlite3.db_open path in
  Sqlite3.busy_timeout db 10_000;
  check db (Sqlite3.exec db "PRAGMA journal_mode=WAL") "wal";
  check db (Sqlite3.exec db "PRAGMA synchronous=FULL") "sync";
  check db
    (Sqlite3.exec db
       "CREATE TABLE IF NOT EXISTS ops (op TEXT NOT NULL, attempt INTEGER NOT NULL, incarnation TEXT NOT NULL, not_after INTEGER NOT NULL, body TEXT NOT NULL, digest TEXT NOT NULL, settled INTEGER NOT NULL DEFAULT 0, committed INTEGER NOT NULL DEFAULT 0, acked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (op, attempt))")
    "create";
  db

let sub_of st =
  { op = Sqlite3.column_text st 0; attempt = Int64.to_int (Sqlite3.column_int64 st 1); incarnation = Sqlite3.column_text st 2;
    not_after = Int64.to_int (Sqlite3.column_int64 st 3); body = Sqlite3.column_text st 4; digest = Sqlite3.column_text st 5;
    settled = Sqlite3.column_int64 st 6 <> 0L }

let cols = "op, attempt, incarnation, not_after, body, digest, settled"

let latest db op =
  Journal.with_stmt db ("SELECT " ^ cols ^ " FROM ops WHERE op = ? ORDER BY attempt DESC LIMIT 1") [ `T op ] (fun st ->
      match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (sub_of st) | _ -> None)

let record db s =
  Journal.with_stmt db "INSERT OR IGNORE INTO ops (op, attempt, incarnation, not_after, body, digest) VALUES (?, ?, ?, ?, ?, ?)"
    [ `T s.op; `I s.attempt; `T s.incarnation; `I s.not_after; `T s.body; `T s.digest ] (fun st -> check db (Sqlite3.step st) "record")

let mark db col s =
  Journal.with_stmt db (Printf.sprintf "UPDATE ops SET %s = 1 WHERE op = ? AND attempt = ?" col) [ `T s.op; `I s.attempt ] (fun st -> check db (Sqlite3.step st) col)

(* ---- the wire --------------------------------------------------------------------------------- *)

let sha256_hex = Cordis_cel.Sha256.digest

let hdr (r : Http.response) k = List.assoc_opt k r.headers

let world_of (r : Http.response) =
  match hdr r "halo-incarnation", Option.bind (hdr r "halo-time") int_of_string_opt with Some incarnation, Some time_ms -> Some { incarnation; time_ms } | _ -> None

let json (r : Http.response) = try J.from_string r.body with _ -> `Null
let str j k = match J.Util.member k j with `String s -> Some s | _ -> None
let int_ j k = match J.Util.member k j with `Int n -> Some n | _ -> None
let retry_after (r : Http.response) = Option.bind (hdr r "retry-after") float_of_string_opt

let verb_headers cfg s =
  [ ("X-Halo-Principal", cfg.principal); ("Halo-Incarnation", s.incarnation); ("Halo-Not-After", string_of_int s.not_after); ("Halo-Digest", s.digest) ]

let path s what = Printf.sprintf "/v1/operations/%s/%d%s" s.op s.attempt what

let call cfg s ~meth what =
  Http.request ~timeout:30. ~headers:(verb_headers cfg s) ~meth (cfg.url ^ path s what) (if meth = "POST" then Some ("application/json", "") else None)

(* a new attempt: the contract names the incarnation and its clock; the deadline is that clock plus the window *)
let new_attempt cfg db ~op ~attempt req =
  let r = Http.request ~timeout:30. ~headers:[ ("X-Halo-Principal", cfg.principal) ] ~meth:"GET" (cfg.url ^ "/v1/halo/contract") None in
  if r.status <> 200 then failwith (Printf.sprintf "contract: HTTP %d" r.status);
  let c = json r in
  (match J.Util.member "inference" c with
   | `Assoc _ as i -> (match str i "mode" with Some m when m <> cfg.mode -> raise (Refused (Printf.sprintf "mode_unavailable: the server runs %s, the intent needs %s" m cfg.mode)) | _ -> ())
   | _ -> ());
  let incarnation = match str c "incarnation" with Some i -> i | None -> failwith "contract: no incarnation"
  and now = match int_ c "time_ms" with Some t -> t | None -> failwith "contract: no time_ms" in
  let not_after = now + cfg.window_ms in
  let fields = List.filter (fun (k, _) -> k <> "model") (chat_fields req) in
  let body =
    J.to_string
      (`Assoc [ ("operation_id", `String op); ("attempt", `Int attempt); ("not_after", `Int not_after); ("api", `String "chat");
                ("request", `Assoc fields); ("mode", `String cfg.mode) ])
  in
  let s = { op; attempt; incarnation; not_after; body; digest = sha256_hex body; settled = false } in
  record db s;
  (* what the store holds is what is sent, a competing writer's included *)
  Option.get (latest db op)

let submit cfg s =
  Http.request ~timeout:30. ~headers:[ ("X-Halo-Principal", cfg.principal); ("Halo-Incarnation", s.incarnation); ("Halo-Digest", s.digest) ] ~meth:"POST"
    (cfg.url ^ "/v1/operations") (Some ("application/json", s.body))

(* acknowledge until answered: the outcome is committed, so halo may release the body *)
let ack cfg db s =
  let rec go n =
    match call cfg s ~meth:"POST" "/ack" with
    | r when r.status = 200 -> mark db "acked" s
    (* the incarnation the result was held by is gone, and its body with it: nothing is left to release *)
    | r when r.status = 409 && str (J.Util.member "error" (json r)) "type" = Some "stale_incarnation" -> mark db "acked" s
    | _ | (exception _) -> if n < 20 then (Thread.delay (Float.min 5. (0.2 *. float_of_int (n + 1))); go (n + 1))
  in
  go 0

(* [committed]: the answer is durable in the outbox's journal. Record that, then acknowledge *)
let committed cfg db s () =
  mark db "committed" s;
  mark db "settled" s;
  ack cfg db s

let tokens_of v =
  match J.Util.member "usage" v with
  | `Assoc _ as u -> Option.value (int_ u "prompt_tokens") ~default:0 + Option.value (int_ u "completion_tokens") ~default:0
  | _ -> 0

(* ---- one call --------------------------------------------------------------------------------- *)

let settle db s = mark db "settled" s; s.settled <- true

(* The operation id is the intent's idempotency key salted with the journal's store identity: ids are never reused
   (RFC 0007), and the same computation key in a fresh journal (another run, a restore from a backup: [new_store_id])
   is another operation, not a rebinding of one halo still holds under another deadline (409 conflict) *)
let operation_id db key =
  let store = match Journal.meta db "store" with Some s -> s | None -> failwith "halo_op: the journal has no store identity yet" in
  "cordis-" ^ String.sub (sha256_hex (store ^ "\n" ^ key)) 0 32

let run cfg db ~key req =
  let key = operation_id db key in
  let s =
    match latest db key with
    | Some s when not s.settled -> s
    | Some s -> new_attempt cfg db ~op:key ~attempt:(s.attempt + 1) req
    | None -> new_attempt cfg db ~op:key ~attempt:0 req
  in
  let lost why = settle db s; failwith why in
  let refuse why = settle db s; raise (Refused why) in
  let deadline = Unix.gettimeofday () +. cfg.wait_s in
  (* a call that fails in transit (nothing came back) is made again while the call's patience lasts: a submit goes
     out as first sent, so the attempt's one identity makes a repeat harmless, and the answer a lost one hid is found *)
  let patient f =
    let rec go () =
      match f () with
      | r -> r
      | exception ((Http.Failed _ | Unix.Unix_error _ | Sys_error _ | End_of_file) as e) ->
          if Unix.gettimeofday () > deadline then raise e else (Thread.delay cfg.poll_s; go ())
    in
    go ()
  in
  let rec accept () =
    let r = patient (fun () -> submit cfg s) in
    match r.status with
    | 202 | 200 -> ()
    | 429 | 503 -> raise (Busy (Printf.sprintf "submit: HTTP %d" r.status, retry_after r))
    | 409 -> (
        match Option.bind (Some (json r)) (fun j -> str (J.Util.member "error" j) "type") with
        | Some "stale_incarnation" -> lost "unknown: halo restarted; its work is beyond reach"
        | _ ->
            (* the identity is already bound to another request in that incarnation, which may have run: not a
               refusal, whose cost is nothing. Charged like an unknown outcome, and loud: with ids salted by the store
               identity it means an id bug, or a journal restored without [new_store_id] *)
            prerr_endline ("halo_op: CONFLICT on " ^ s.op ^ "/" ^ string_of_int s.attempt ^ ": the attempt is bound to another request; charged as unknown");
            lost "conflict: the attempt is bound to another request")
    | 410 -> refuse "closed: past not_after, nothing accepted"
    | 422 -> refuse "mode_unavailable"
    | 400 -> refuse "bad submit"
    | n -> failwith (Printf.sprintf "submit: HTTP %d" n)
  and wait () =
    if Unix.gettimeofday () > deadline then failwith "no result yet; the attempt stays open"
    else begin
      let r = patient (fun () -> call cfg s ~meth:"GET" "") in
      if r.status <> 200 then failwith (Printf.sprintf "status: HTTP %d" r.status);
      let v = json r in
      match str v "status" with
      | Some "running" -> Thread.delay cfg.poll_s; wait ()
      | Some "never_accepted" -> accept (); Thread.delay cfg.poll_s; wait ()
      | Some "done" -> fetch v r
      | Some ("closed" | "cancelled_before_accept") -> refuse "closed: no copy was accepted"
      | Some "expired" ->
          (* the body is gone, the usage is not: settled at that. The outcome is acknowledged like any other *)
          settle db s;
          ignore (Thread.create (fun () -> ack cfg db s) ());
          raise (Expired ("expired: the capture window passed before the result was taken", tokens_of v))
      | Some "released" -> lost "released: acknowledged earlier, the result is no longer held"
      | Some "unknown" -> lost "unknown: another incarnation"
      | st -> failwith ("status: " ^ Option.value st ~default:"none")
    end
  and fetch v (status : Http.response) =
    let r = patient (fun () -> call cfg s ~meth:"GET" "/result") in
    if r.status <> 200 then failwith (Printf.sprintf "result: HTTP %d" r.status);
    let content, _, _, reasoning = read_completion r.body in
    let finish = Option.value (str v "finish") ~default:"" in
    let tokens = tokens_of v in
    { value = completion_value content finish reasoning;
      cost = (if tokens > 0 then tokens else -1);
      source = Some "operation"; discarded = None; admitted = None;
      world = world_of status;
      committed = Some (committed cfg db s) }
  in
  accept ();
  wait ()

(* acknowledgements an earlier outbox committed but did not finish: send them again *)
let recover cfg db =
  let owed =
    Journal.with_stmt db ("SELECT " ^ cols ^ " FROM ops WHERE committed = 1 AND acked = 0") [] (fun st ->
        let rec go acc = match Sqlite3.step st with Sqlite3.Rc.ROW -> go (sub_of st :: acc) | _ -> List.rev acc in
        go [])
  in
  List.iter (fun s -> ignore (Thread.create (fun () -> ack cfg db s) ())) owed;
  List.length owed

(* the executor; its records are in [store], a file (the journal's own is fine: another connection) *)
let llm_complete ~store cfg =
  let db = open_store store in
  (* one connection serves the calls running at once: sqlite serialises it; each statement is its own transaction *)
  ignore (recover cfg db);
  (* a send again after no answer resumes the attempt it left open; it is not a new one *)
  let go ~key req = run cfg db ~key req in
  { run = go; reconcile = (fun ~key req -> Some (go ~key req)) }
