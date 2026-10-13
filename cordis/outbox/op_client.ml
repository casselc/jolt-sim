(* The operation envelope (docs/cordis/rfc/0007-boundaries.md; RFC 0008, "The outbox: one envelope, two adapters").
   What every operation executor shares, and nothing about what one means:

   - identity: (owner, operation id, attempt), the id the intent's idempotency key salted with the journal's store
     identity, so ids are never reused;
   - the exact submission: journaled before it is sent, with the incarnation it names and its deadline, and every send of
     it is those bytes (rule 4); its SHA-256 is the digest every other verb carries;
   - polling, with the deadline carried on every verb, and the patience to repeat a call that failed in transit;
   - durable capture: the answer and what the executor is owed (the acknowledgement) commit in one transaction, the
     obligation is paid after, and an obligation still open at start is paid again (commit before ack);
   - the refusal rule: a refused copy closes the copy, not the attempt. The attempt is fenced by a cancel and the
     reservation is released only on an authoritative terminal answer (`cancelled_before_accept`, `closed`).

   What a status means, what a result is, what is charged and which artifacts are kept belong to an [adapter]: Halo's
   (Halo_adapter) and, to come, yard's. The attempt's records are in this module's own table, beside the journal. *)

module J = Yojson.Safe
open Executors

type cfg = {
  url : string;
  principal : string;  (** X-Halo-Principal: the owner of the attempts *)
  window_ms : int;  (** how long, by the executor's clock, a submit may still be accepted *)
  poll_s : float;
  wait_s : float;  (** how long one call waits for a result before it gives up with no answer (it resumes the same attempt) *)
}

(* what a status view means to an executor *)
type step =
  | Wait  (** running *)
  | Resend  (** not accepted yet: the same submission is sent again *)
  | Take  (** finished: fetch the result *)
  | Refuse of string  (** an authoritative terminal answer, not executed: nothing spent, the attempt settled *)
  | Lose of string  (** the outcome is beyond reach: charged the worst case, the attempt settled *)
  | Unusable of string * int  (** no result, but the executor says what the attempt spent *)
  | Fail of string  (** an answer this adapter does not understand *)

type adapter = {
  contract_path : string;
  check_contract : J.t -> unit;  (** raise [Refused] if the executor cannot serve the task as it is configured *)
  request : op:string -> attempt:int -> not_after:int -> V.t -> string;  (** the exact bytes of the submit *)
  interpret : J.t -> step;
  decode : view:J.t -> status:Http.response -> result:string -> answer;  (** a fetched result, before the envelope's hooks *)
}

type submit = {
  op : string;
  attempt : int;
  incarnation : string;
  not_after : int;
  body : string;  (** the exact bytes sent *)
  digest : string;
  mutable settled : bool;
}

(* ---- the envelope's own records ---------------------------------------------------------------- *)

let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "op_client %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

let open_store path =
  let db = Sqlite3.db_open path in
  Sqlite3.busy_timeout db 10_000;
  check db (Sqlite3.exec db "PRAGMA journal_mode=WAL") "wal";
  check db (Sqlite3.exec db "PRAGMA synchronous=FULL") "sync";
  check db
    (Sqlite3.exec db
       "CREATE TABLE IF NOT EXISTS ops (op TEXT NOT NULL, attempt INTEGER NOT NULL, incarnation TEXT NOT NULL, not_after INTEGER NOT NULL, body TEXT NOT NULL, digest TEXT NOT NULL, settled INTEGER NOT NULL DEFAULT 0, committed INTEGER NOT NULL DEFAULT 0, acked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (op, attempt))")
    "create";
  check db (Sqlite3.exec db Journal.obligations_sql) "obligations";
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
let new_attempt cfg (ad : adapter) db ~op ~attempt req =
  let r = Http.request ~timeout:30. ~headers:[ ("X-Halo-Principal", cfg.principal) ] ~meth:"GET" (cfg.url ^ ad.contract_path) None in
  if r.status <> 200 then failwith (Printf.sprintf "contract: HTTP %d" r.status);
  let c = json r in
  ad.check_contract c;
  let incarnation = match str c "incarnation" with Some i -> i | None -> failwith "contract: no incarnation"
  and now = match int_ c "time_ms" with Some t -> t | None -> failwith "contract: no time_ms" in
  let not_after = now + cfg.window_ms in
  let body = ad.request ~op ~attempt ~not_after req in
  let s = { op; attempt; incarnation; not_after; body; digest = sha256_hex body; settled = false } in
  record db s;
  (* what the store holds is what is sent, a competing writer's included *)
  Option.get (latest db op)

let submit cfg s =
  Http.request ~timeout:30. ~headers:[ ("X-Halo-Principal", cfg.principal); ("Halo-Incarnation", s.incarnation); ("Halo-Digest", s.digest) ] ~meth:"POST"
    (cfg.url ^ "/v1/operations") (Some ("application/json", s.body))

(* acknowledge until answered: the outcome is committed, so the executor may release the body. Whether it was (a stale
   incarnation's is as good: its body went with it) *)
let ack cfg db s =
  let rec go n =
    match call cfg s ~meth:"POST" "/ack" with
    | r when r.status = 200 -> mark db "acked" s; true
    | r when r.status = 409 && str (J.Util.member "error" (json r)) "type" = Some "stale_incarnation" -> mark db "acked" s; true
    | _ | (exception _) -> if n < 20 then (Thread.delay (Float.min 5. (0.2 *. float_of_int (n + 1))); go (n + 1)) else false
  in
  go 0

(* what the executor is owed for an attempt, as the journal records it with the answer *)
let owes_id s = Printf.sprintf "%s/%d" s.op s.attempt

(* pay it: the answer (or the unusable outcome's spend) is durable in the journal together with the obligation, so
   settle the attempt, acknowledge, and discharge the obligation once the executor has been told *)
let pay cfg db s () =
  mark db "committed" s;
  mark db "settled" s;
  if ack cfg db s then Journal.discharge db (owes_id s)

(* ---- one call --------------------------------------------------------------------------------- *)

let settle db s = mark db "settled" s; s.settled <- true

(* The operation id is the intent's idempotency key salted with the journal's store identity: ids are never reused
   (RFC 0007), and the same computation key in a fresh journal (another run, a restore from a backup: [new_store_id])
   is another operation, not a rebinding of one the executor still holds under another deadline (409 conflict) *)
let operation_id db key =
  let store = match Journal.meta db "store" with Some s -> s | None -> failwith "op_client: the journal has no store identity yet" in
  "cordis-" ^ String.sub (sha256_hex (store ^ "\n" ^ key)) 0 32

let run cfg (ad : adapter) db ~key req =
  let key = operation_id db key in
  let s =
    match latest db key with
    | Some s when not s.settled -> s
    | Some s -> new_attempt cfg ad db ~op:key ~attempt:(s.attempt + 1) req
    | None -> new_attempt cfg ad db ~op:key ~attempt:0 req
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
        | Some "stale_incarnation" -> lost "unknown: the executor restarted; its work is beyond reach"
        | _ ->
            (* the identity is already bound to another request in that incarnation, which may have run: not a
               refusal, whose cost is nothing. Charged like an unknown outcome, and loud: with ids salted by the store
               identity it means an id bug, or a journal restored without [new_store_id] *)
            prerr_endline ("op_client: CONFLICT on " ^ s.op ^ "/" ^ string_of_int s.attempt ^ ": the attempt is bound to another request; charged as unknown");
            lost "conflict: the attempt is bound to another request")
    | 410 -> refuse "closed: past not_after, nothing accepted"
    | 422 -> fenced_refusal "mode_unavailable"
    | 400 -> fenced_refusal "bad submit"
    | n -> failwith (Printf.sprintf "submit: HTTP %d" n)
  (* A refused copy closes the copy, not the attempt (RFC 0007 rule 2): a delayed copy of the same submission, or an
     earlier one, may yet be accepted and run. Fence the attempt with a cancel and release the reservation only on
     the terminal answer it gets; if a copy was accepted after all, the attempt goes on to its own end *)
  and fenced_refusal why =
    let r = patient (fun () -> call cfg s ~meth:"POST" "/cancel") in
    if r.status <> 200 then failwith (Printf.sprintf "cancel after a refused copy (%s): HTTP %d" why r.status);
    match str (json r) "status" with
    | Some ("cancelled_before_accept" | "closed") -> refuse why
    | _ -> ()  (* a copy was accepted after all: the status that follows tells how it ends *)
  and wait () =
    if Unix.gettimeofday () > deadline then failwith "no result yet; the attempt stays open"
    else begin
      let r = patient (fun () -> call cfg s ~meth:"GET" "") in
      if r.status <> 200 then failwith (Printf.sprintf "status: HTTP %d" r.status);
      let v = json r in
      match ad.interpret v with
      | Wait -> Thread.delay cfg.poll_s; wait ()
      | Resend -> accept (); Thread.delay cfg.poll_s; wait ()
      | Take -> fetch v r
      | Refuse why -> refuse why
      | Lose why -> lost why
      | Unusable (why, spent) ->
          (* the body is gone, the usage is not: settled at that. As for a result, the usage is durable in the outbox's
             journal before the outcome is acknowledged: the outbox pays the obligation after journaling it *)
          raise (Expired (why, spent, owes_id s, pay cfg db s))
      | Fail why -> failwith why
    end
  and fetch v (status : Http.response) =
    let r = patient (fun () -> call cfg s ~meth:"GET" "/result") in
    if r.status <> 200 then failwith (Printf.sprintf "result: HTTP %d" r.status);
    let a = ad.decode ~view:v ~status ~result:r.body in
    { a with owes = Some (owes_id s); committed = Some (pay cfg db s) }
  in
  accept ();
  wait ()

(* obligations an earlier outbox owed: the journal's, committed with the answers they belong to, are paid again (the
   executor answers a repeat of an acknowledgement with the attempt's status) *)
let recover cfg db =
  let owed = Journal.owed db in
  List.iter
    (fun id ->
      match String.rindex_opt id '/' with
      | None -> Journal.discharge db id
      | Some i -> (
          let op = String.sub id 0 i and attempt = int_of_string (String.sub id (i + 1) (String.length id - i - 1)) in
          let s =
            Journal.with_stmt db ("SELECT " ^ cols ^ " FROM ops WHERE op = ? AND attempt = ?") [ `T op; `I attempt ] (fun st ->
                match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (sub_of st) | _ -> None)
          in
          match s with None -> Journal.discharge db id | Some s -> ignore (Thread.create (pay cfg db s) ())))
    owed;
  List.length owed

(* an executor over an adapter; its records are in [store], a file (the journal's own is fine: another connection) *)
let executor ~store cfg (ad : adapter) =
  let db = open_store store in
  ignore (recover cfg db);
  (* a send again after no answer resumes the attempt it left open; it is not a new one *)
  let go ~key req = run cfg ad db ~key req in
  { run = go; reconcile = (fun ~key req -> Some (go ~key req)) }
