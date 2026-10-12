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
   as refused again. A row [Superseded] is a send of a keyed intent whose
   later send was answered from the same generation; a row [Charged], one
   whose later send's answer does not cover it (generated afresh, or from a
   world that does not say): whatever it did is in no answer, so the engine
   charged it.

   The journal is APPENDED FACTS (RFC 0007, "History"; quint/journal.qnt;
   after casselc/observability's R-L6). Nothing is updated in place. An
   intent is written once ([intents]); everything that happens to it is a
   fact ([facts]) with a stable id ("<jkey>@<rev>"), the writer's revision,
   a custody position (the order the store committed it in), and the fact
   it supersedes. The attempt's state is the RESOLUTION of its facts: the
   fact nothing supersedes, of the highest revision. That is the writer's
   order, not the arrival order: a fact whose predecessor has not arrived
   leaves its own predecessor looking current. Views [heads] and [journal]
   present the resolution, so a reader of [journal] sees the row it always
   did. An audit names a [basis] (store identity and position); the view at
   it is the facts up to that position, refused if the store is another (a
   restore reuses positions) or if compaction has taken the history at or
   below it. Compaction deletes only a fact superseded by a fact also
   inside the compacted prefix, so the resolved value at any basis above
   the horizon is unchanged. Accounting adjustments are [ledger] events
   with a stable id, applied once however often they are delivered. *)

type state = Waiting | Sent | Done | Unknown | Fenced | Refused | Superseded | Charged

let state_name = function
  | Waiting -> "waiting" | Sent -> "sent" | Done -> "done" | Unknown -> "unknown" | Fenced -> "fenced" | Refused -> "refused"
  | Superseded -> "superseded" | Charged -> "charged"

let state_of = function
  | "waiting" -> Waiting | "sent" -> Sent | "done" -> Done | "unknown" -> Unknown | "fenced" -> Fenced | "refused" -> Refused
  | "superseded" -> Superseded | "charged" -> Charged
  | s -> failwith ("journal state " ^ s)

type row = {
  jkey : string;
  state : state;
  value : string option;
  cost : int;
  source : string option;  (** where the world said its answer came from *)
  discarded : int option;
  admitted : int option;
  world : string option;  (** the incarnation and clock of the world that answered, as JSON (Executors.world) *)
}

type t = Sqlite3.db

let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "journal %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

let exec db sql what = check db (Sqlite3.exec db sql) what

let with_stmt db sql binds f =
  let st = Sqlite3.prepare db sql in
  List.iteri (fun i b -> check db (match b with `T s -> Sqlite3.bind_text st (i + 1) s | `I n -> Sqlite3.bind_int st (i + 1) n | `N -> Sqlite3.bind st (i + 1) Sqlite3.Data.NULL) "bind") binds;
  Fun.protect ~finally:(fun () -> ignore (Sqlite3.finalize st)) (fun () -> f st)

let step_done db st what = check db (Sqlite3.step st) what

let opt_t = function Some s -> `T s | None -> `N
let opt_i = function Some n -> `I n | None -> `N

let text st i = match Sqlite3.column st i with Sqlite3.Data.TEXT s -> Some s | _ -> None
let int_opt st i = match Sqlite3.column st i with Sqlite3.Data.INT n -> Some (Int64.to_int n) | _ -> None
let int st i = Int64.to_int (Sqlite3.column_int64 st i)

(* run [f] as one transaction: the facts it appends commit together, or none does *)
let tx db f =
  exec db "BEGIN IMMEDIATE" "begin";
  match f () with
  | v -> exec db "COMMIT" "commit"; v
  | exception e -> ignore (Sqlite3.exec db "ROLLBACK"); raise e

let now_ms () = int_of_float (Unix.gettimeofday () *. 1000.)

let random_id () =
  let r = Random.State.make_self_init () in
  Printf.sprintf "%08x%08x%08x%08x" (Random.State.bits r) (Random.State.bits r) (Random.State.bits r) (Random.State.bits r)

(* ---- the schema ------------------------------------------------------------------------------- *)

let schema =
  [ "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)";
    "CREATE TABLE IF NOT EXISTS intents (jkey TEXT PRIMARY KEY, key TEXT NOT NULL, attempt INTEGER NOT NULL, kind TEXT NOT NULL, request TEXT NOT NULL)";
    "CREATE TABLE IF NOT EXISTS facts (pos INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, jkey TEXT NOT NULL, rev INTEGER NOT NULL, supersedes TEXT, why TEXT NOT NULL, at_ms INTEGER NOT NULL, state TEXT NOT NULL, value TEXT, cost INTEGER NOT NULL DEFAULT 0, source TEXT, discarded INTEGER, admitted INTEGER, world TEXT)";
    "CREATE INDEX IF NOT EXISTS facts_jkey ON facts (jkey, rev)";
    "CREATE INDEX IF NOT EXISTS facts_supersedes ON facts (supersedes)";
    "CREATE TABLE IF NOT EXISTS ledger (id TEXT PRIMARY KEY, jkey TEXT NOT NULL, delta INTEGER NOT NULL, why TEXT NOT NULL, at_ms INTEGER NOT NULL)";
    (* the resolution: the fact nothing supersedes, of the highest revision of its attempt *)
    "CREATE VIEW IF NOT EXISTS heads AS SELECT f.* FROM facts f WHERE NOT EXISTS (SELECT 1 FROM facts g WHERE g.supersedes = f.id) AND NOT EXISTS (SELECT 1 FROM facts h WHERE h.jkey = f.jkey AND h.rev > f.rev AND NOT EXISTS (SELECT 1 FROM facts g WHERE g.supersedes = h.id))";
    "CREATE VIEW IF NOT EXISTS journal AS SELECT i.rowid AS rowid, i.jkey AS jkey, i.key AS key, i.attempt AS attempt, i.kind AS kind, i.request AS request, h.state AS state, h.value AS value, h.cost AS cost, h.source AS source, h.discarded AS discarded, h.admitted AS admitted, h.world AS world FROM intents i JOIN heads h ON h.jkey = i.jkey" ]

(* ---- facts ------------------------------------------------------------------------------------ *)

let fact_id jkey rev = Printf.sprintf "%s@%d" jkey rev

(* append one fact. Idempotent by its id: a fact delivered again is not appended again. The low-level
   entry (the resolution, not arrival, decides which fact is current), for the writer and for tests *)
let append_fact ?(at_ms = now_ms ()) db ~jkey ~rev ?supersedes ~why (r : row) =
  with_stmt db
    "INSERT OR IGNORE INTO facts (id, jkey, rev, supersedes, why, at_ms, state, value, cost, source, discarded, admitted, world) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    [ `T (fact_id jkey rev); `T jkey; `I rev; opt_t supersedes; `T why; `I at_ms; `T (state_name r.state); opt_t r.value; `I r.cost; opt_t r.source;
      opt_i r.discarded; opt_i r.admitted; opt_t r.world ]
    (fun st -> step_done db st "append")

let row_cols = "jkey, state, value, cost, source, discarded, admitted, world"

let row_of st =
  { jkey = Sqlite3.column_text st 0; state = state_of (Sqlite3.column_text st 1); value = text st 2; cost = int st 3; source = text st 4;
    discarded = int_opt st 5; admitted = int_opt st 6; world = text st 7 }

(* the head fact of an attempt: its revision, its id, its row *)
let head db jkey =
  with_stmt db ("SELECT rev, id, " ^ row_cols ^ " FROM heads WHERE jkey = ?") [ `T jkey ] (fun st ->
      match Sqlite3.step st with
      | Sqlite3.Rc.ROW ->
          let rev = int st 0 and id = Sqlite3.column_text st 1 in
          let row = { jkey = Sqlite3.column_text st 2; state = state_of (Sqlite3.column_text st 3); value = text st 4; cost = int st 5; source = text st 6;
                      discarded = int_opt st 7; admitted = int_opt st 8; world = text st 9 } in
          Some (rev, id, row)
      | _ -> None)

(* the writer's next fact: the head's row changed by [f], superseding the head. Inside the caller's transaction *)
let append_next db ~jkey ~why f =
  match head db jkey with
  | None -> ()
  | Some (rev, id, r) -> append_fact db ~jkey ~rev:(rev + 1) ~supersedes:id ~why (f r)

let find db jkey =
  with_stmt db ("SELECT " ^ row_cols ^ " FROM journal WHERE jkey = ?") [ `T jkey ] (fun st ->
      match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (row_of st) | _ -> None)

(* ---- opening ---------------------------------------------------------------------------------- *)

let table_exists db name kind =
  with_stmt db "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?" [ `T kind; `T name ] (fun st -> Sqlite3.step st = Sqlite3.Rc.ROW)

(* a journal from before it was facts: one mutable row each. Each becomes an intent and a first fact *)
let migrate db =
  if table_exists db "journal" "table" then begin
    List.iter (fun c -> match Sqlite3.exec db ("ALTER TABLE journal ADD COLUMN " ^ c) with _ -> ()) [ "source TEXT"; "discarded INTEGER"; "admitted INTEGER"; "world TEXT" ];
    tx db (fun () ->
        exec db "ALTER TABLE journal RENAME TO journal_v1" "rename";
        List.iter (fun sql -> exec db sql "schema") schema;
        exec db "INSERT INTO intents (jkey, key, attempt, kind, request) SELECT jkey, key, attempt, kind, request FROM journal_v1 ORDER BY rowid" "migrate intents";
        exec db
          (Printf.sprintf
             "INSERT INTO facts (id, jkey, rev, supersedes, why, at_ms, state, value, cost, source, discarded, admitted, world) SELECT jkey || '@1', jkey, 1, NULL, 'migrated', %d, state, value, cost, source, discarded, admitted, world FROM journal_v1 ORDER BY rowid"
             (now_ms ()))
          "migrate facts";
        exec db "DROP TABLE journal_v1" "drop")
  end

let meta db k =
  with_stmt db "SELECT v FROM meta WHERE k = ?" [ `T k ] (fun st -> match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)

let set_meta db k v = with_stmt db "INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)" [ `T k; `T v ] (fun st -> step_done db st "meta")

let open_ path =
  let db = Sqlite3.db_open path in
  (* the adapter keeps its own records in the same file, on a connection of its own: wait for its writes, not fail *)
  Sqlite3.busy_timeout db 10_000;
  exec db "PRAGMA journal_mode=WAL" "journal_mode";
  exec db "PRAGMA synchronous=FULL" "synchronous";
  migrate db;
  List.iter (fun sql -> exec db sql "schema") schema;
  if meta db "store" = None then set_meta db "store" (random_id ());
  if meta db "horizon" = None then set_meta db "horizon" "0";
  (* what was running when the last outbox stopped may or may not have happened: a new fact says so *)
  tx db (fun () ->
      let sent = with_stmt db "SELECT jkey FROM heads WHERE state = 'sent'" [] (fun st ->
          let rec go acc = match Sqlite3.step st with Sqlite3.Rc.ROW -> go (Sqlite3.column_text st 0 :: acc) | _ -> List.rev acc in
          go []) in
      List.iter (fun jkey -> append_next db ~jkey ~why:"restart" (fun r -> { r with state = Unknown })) sent);
  db

(* ---- reading ---------------------------------------------------------------------------------- *)

(* the journal key that answers for an intent key: the key itself (an effectful intent), else, for an
   intent journaled per attempt (observational, keyed), a done attempt if any, else the latest *)
let answering db key =
  match find db key with
  | Some _ -> Some key
  | None ->
      with_stmt db "SELECT jkey FROM journal WHERE key = ? ORDER BY (state = 'done') DESC, attempt DESC LIMIT 1" [ `T key ] (fun st ->
          match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)

(* the rows waiting for a token: (jkey, key, attempt, kind, request) *)
let waiting db =
  with_stmt db "SELECT jkey, key, attempt, kind, request FROM journal WHERE state = 'waiting' ORDER BY rowid" [] (fun st ->
      let rec go acc =
        match Sqlite3.step st with
        | Sqlite3.Rc.ROW -> go ((Sqlite3.column_text st 0, Sqlite3.column_text st 1, int st 2, Sqlite3.column_text st 3, Sqlite3.column_text st 4) :: acc)
        | _ -> List.rev acc
      in
      go [])

let request db jkey =
  with_stmt db "SELECT request FROM intents WHERE jkey = ?" [ `T jkey ] (fun st ->
      match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)

let count db = with_stmt db "SELECT COUNT(*) FROM intents" [] (fun st -> match Sqlite3.step st with Sqlite3.Rc.ROW -> int st 0 | _ -> 0)

(* ---- writing: every write appends ------------------------------------------------------------- *)

let blank jkey state = { jkey; state; value = None; cost = 0; source = None; discarded = None; admitted = None; world = None }

(* journal before running: the intent and its first fact are durable when this returns *)
let begin_ ?(state = Sent) db ~jkey ~key ~attempt ~kind ~request =
  tx db (fun () ->
      with_stmt db "INSERT INTO intents (jkey, key, attempt, kind, request) VALUES (?, ?, ?, ?, ?)"
        [ `T jkey; `T key; `I attempt; `T kind; `T request ] (fun st -> step_done db st "insert");
      append_fact db ~jkey ~rev:1 ~why:"begin" (blank jkey state))

let finish ?source ?discarded ?admitted ?world db ~jkey ~value ~cost =
  tx db (fun () -> append_next db ~jkey ~why:"finish" (fun r -> { r with state = Done; value = Some value; cost; source; discarded; admitted; world }))

(* the other sends of an intent one of whose sends was answered: under one idempotency key, an answer
   the world says covers them (replayed, adopted) supersedes them; any other leaves their work, if any,
   discarded; either way their outcome is no longer unknown *)
let supersede ?(state = Superseded) db ~key ~answered =
  tx db (fun () ->
      let others =
        with_stmt db "SELECT jkey FROM journal WHERE key = ? AND jkey <> ? AND state IN ('unknown', 'sent')" [ `T key; `T answered ] (fun st ->
            let rec go acc = match Sqlite3.step st with Sqlite3.Rc.ROW -> go (Sqlite3.column_text st 0 :: acc) | _ -> List.rev acc in
            go [])
      in
      List.iter (fun jkey -> append_next db ~jkey ~why:"supersede" (fun r -> { r with state })) others)

(* a definite failure: it did not run, and answers the same again *)
let refuse db ~jkey ~why =
  tx db (fun () -> append_next db ~jkey ~why:"refuse" (fun r -> { r with state = Refused; value = Some why }))

let set_state db ~jkey s = tx db (fun () -> append_next db ~jkey ~why:"state" (fun r -> { r with state = s }))

(* ---- history: bases, views, compaction, the ledger -------------------------------------------- *)

type basis = { store : string; pos : int }

let store db = Option.get (meta db "store")
let horizon db = int_of_string (Option.get (meta db "horizon"))

let basis db =
  let pos = with_stmt db "SELECT COALESCE(seq, 0) FROM sqlite_sequence WHERE name = 'facts'" [] (fun st -> match Sqlite3.step st with Sqlite3.Rc.ROW -> int st 0 | _ -> 0) in
  { store = store db; pos }

type refusal = Foreign | Expired

(* the rows as the facts up to the basis resolve them. A restored store (a new identity: [new_store_id]) refuses
   a basis from before; so does a basis the compaction has overtaken *)
let view_at db (b : basis) : (row list, refusal) result =
  if b.store <> store db then Error Foreign
  else if horizon db > 0 && b.pos <= horizon db then Error Expired
  else begin
    let facts =
      with_stmt db ("SELECT id, supersedes, rev, " ^ row_cols ^ " FROM facts WHERE pos <= ? ORDER BY pos") [ `I b.pos ] (fun st ->
          let rec go acc =
            match Sqlite3.step st with
            | Sqlite3.Rc.ROW ->
                let row = { jkey = Sqlite3.column_text st 3; state = state_of (Sqlite3.column_text st 4); value = text st 5; cost = int st 6; source = text st 7;
                            discarded = int_opt st 8; admitted = int_opt st 9; world = text st 10 } in
                go ((Sqlite3.column_text st 0, text st 1, int st 2, row) :: acc)
            | _ -> List.rev acc
          in
          go [])
    in
    let superseded = List.filter_map (fun (_, s, _, _) -> s) facts in
    let current = List.filter (fun (id, _, _, _) -> not (List.mem id superseded)) facts in
    (* per attempt, the current fact of the highest revision; attempts in the order they first appear *)
    let order = List.fold_left (fun acc (_, _, _, r) -> if List.mem r.jkey acc then acc else acc @ [ r.jkey ]) [] facts in
    Ok
      (List.filter_map
         (fun jkey ->
           List.fold_left (fun best (_, _, rev, r) ->
               if r.jkey <> jkey then best else match best with Some (br, _) when br >= rev -> best | _ -> Some (rev, r)) None current
           |> Option.map snd)
         order)
  end

(* replace the history up to [upto] by what the facts above it still say. Only a fact superseded by a fact
   also within the prefix goes, so every attempt keeps its resolution *)
let compact db ~upto =
  tx db (fun () ->
      exec db (Printf.sprintf "DELETE FROM facts WHERE pos <= %d AND EXISTS (SELECT 1 FROM facts g WHERE g.supersedes = facts.id AND g.pos <= %d)" upto upto) "compact";
      if upto > horizon db then set_meta db "horizon" (string_of_int upto))

(* the store was restored from a backup: its positions are reused by other facts, so it is another store *)
let new_store_id db = set_meta db "store" (random_id ())

(* the facts of an attempt, in custody order: (position, revision, why, state) *)
let facts_of db jkey =
  with_stmt db "SELECT pos, rev, why, state FROM facts WHERE jkey = ? ORDER BY pos" [ `T jkey ] (fun st ->
      let rec go acc = match Sqlite3.step st with Sqlite3.Rc.ROW -> go ((int st 0, int st 1, Sqlite3.column_text st 2, Sqlite3.column_text st 3) :: acc) | _ -> List.rev acc in
      go [])

(* an accounting adjustment, applied once by its id however often it is delivered; whether this delivery applied it *)
let adjust db ~id ~jkey ~delta ~why =
  with_stmt db "INSERT OR IGNORE INTO ledger (id, jkey, delta, why, at_ms) VALUES (?, ?, ?, ?, ?)" [ `T id; `T jkey; `I delta; `T why; `I (now_ms ()) ] (fun st ->
      step_done db st "adjust");
  Sqlite3.changes db > 0

let ledger_total ?jkey db =
  let sql, binds = match jkey with Some j -> ("SELECT COALESCE(SUM(delta), 0) FROM ledger WHERE jkey = ?", [ `T j ]) | None -> ("SELECT COALESCE(SUM(delta), 0) FROM ledger", []) in
  with_stmt db sql binds (fun st -> match Sqlite3.step st with Sqlite3.Rc.ROW -> int st 0 | _ -> 0)
