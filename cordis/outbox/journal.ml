(* The outbox journal (docs/cordis/DESIGN.md "The outbox"; quint/outbox.qnt,
   quint/handoff.qnt): every intent the outbox takes, by its journal key,
   before it runs. The journal lives outside the agent's step snapshots, so
   a rollback never rewinds it, and an intent released again is answered
   from it, never run twice. An effectful intent is journaled by its key;
   an observational one by key and attempt, since each attempt runs once
   (RFC 0006, "Observational emissions"). A row still [Sent] when the
   outbox restarts became [Unknown]: it may or may not have taken effect.
   A row [Waiting] has no token from the limiter yet: nothing was sent, so
   it waits on across a restart (outbox.qnt's WaitGrant), as does one the
   world asked to come back later (an HTTP 429 or 503). A row [Refused]
   was refused by the world (an HTTP 4xx): it did not run, and is answered
   as refused again. *)

type state = Waiting | Sent | Done | Unknown | Fenced | Refused

let state_name = function
  | Waiting -> "waiting" | Sent -> "sent" | Done -> "done" | Unknown -> "unknown" | Fenced -> "fenced" | Refused -> "refused"

let state_of = function
  | "waiting" -> Waiting | "sent" -> Sent | "done" -> Done | "unknown" -> Unknown | "fenced" -> Fenced | "refused" -> Refused
  | s -> failwith ("journal state " ^ s)

type row = { jkey : string; state : state; value : string option; cost : int }

type t = Sqlite3.db

let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "journal %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

let open_ path =
  let db = Sqlite3.db_open path in
  check db (Sqlite3.exec db "PRAGMA journal_mode=WAL") "journal_mode";
  check db (Sqlite3.exec db "PRAGMA synchronous=FULL") "synchronous";
  check db
    (Sqlite3.exec db
       "CREATE TABLE IF NOT EXISTS journal (jkey TEXT PRIMARY KEY, key TEXT NOT NULL, attempt INTEGER NOT NULL, kind TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, value TEXT, cost INTEGER NOT NULL DEFAULT 0)")
    "create";
  (* what was running when the last outbox stopped may or may not have happened *)
  check db (Sqlite3.exec db "UPDATE journal SET state = 'unknown' WHERE state = 'sent'") "restart";
  db

let with_stmt db sql binds f =
  let st = Sqlite3.prepare db sql in
  List.iteri (fun i b -> check db (match b with `T s -> Sqlite3.bind_text st (i + 1) s | `I n -> Sqlite3.bind_int st (i + 1) n | `N -> Sqlite3.bind st (i + 1) Sqlite3.Data.NULL) "bind") binds;
  Fun.protect ~finally:(fun () -> ignore (Sqlite3.finalize st)) (fun () -> f st)

let find db jkey =
  with_stmt db "SELECT state, value, cost FROM journal WHERE jkey = ?" [ `T jkey ] (fun st ->
      match Sqlite3.step st with
      | Sqlite3.Rc.ROW ->
          let value = match Sqlite3.column st 1 with Sqlite3.Data.TEXT s -> Some s | _ -> None in
          Some { jkey; state = state_of (Sqlite3.column_text st 0); value; cost = Int64.to_int (Sqlite3.column_int64 st 2) }
      | _ -> None)

(* the journal key that answers for an intent key: the key itself (an effectful intent), else, for an
   intent journaled per attempt (observational, keyed), a done attempt if any, else the latest *)
let answering db key =
  match find db key with
  | Some _ -> Some key
  | None ->
      with_stmt db "SELECT jkey FROM journal WHERE key = ? ORDER BY (state = 'done') DESC, attempt DESC LIMIT 1" [ `T key ] (fun st ->
          match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)

let step_done db st what = check db (Sqlite3.step st) what

(* journal before running: the row is durable when this returns *)
let begin_ ?(state = Sent) db ~jkey ~key ~attempt ~kind ~request =
  with_stmt db "INSERT INTO journal (jkey, key, attempt, kind, request, state) VALUES (?, ?, ?, ?, ?, ?)"
    [ `T jkey; `T key; `I attempt; `T kind; `T request; `T (state_name state) ] (fun st -> step_done db st "insert")

(* the rows waiting for a token: (jkey, key, attempt, kind, request) *)
let waiting db =
  with_stmt db "SELECT jkey, key, attempt, kind, request FROM journal WHERE state = 'waiting' ORDER BY rowid" [] (fun st ->
      let rec go acc =
        match Sqlite3.step st with
        | Sqlite3.Rc.ROW ->
            go ((Sqlite3.column_text st 0, Sqlite3.column_text st 1, Int64.to_int (Sqlite3.column_int64 st 2), Sqlite3.column_text st 3, Sqlite3.column_text st 4) :: acc)
        | _ -> List.rev acc
      in
      go [])

let finish db ~jkey ~value ~cost =
  with_stmt db "UPDATE journal SET state = 'done', value = ?, cost = ? WHERE jkey = ?" [ `T value; `I cost; `T jkey ] (fun st -> step_done db st "finish")

(* a definite failure: it did not run, and answers the same again *)
let refuse db ~jkey ~why =
  with_stmt db "UPDATE journal SET state = 'refused', value = ? WHERE jkey = ?" [ `T why; `T jkey ] (fun st -> step_done db st "refuse")

let set_state db ~jkey s =
  with_stmt db "UPDATE journal SET state = ? WHERE jkey = ?" [ `T (state_name s); `T jkey ] (fun st -> step_done db st "state")

let request db jkey =
  with_stmt db "SELECT request FROM journal WHERE jkey = ?" [ `T jkey ] (fun st ->
      match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)

let count db = with_stmt db "SELECT COUNT(*) FROM journal" [] (fun st -> match Sqlite3.step st with Sqlite3.Rc.ROW -> Int64.to_int (Sqlite3.column_int64 st 0) | _ -> 0)
