(* The run store (docs/cordis/rfc/0006-turn-runtime.md, revision 2,
   decision 2: SQLite behind a [Store] signature until the unikernel host
   exists). Records are appended in memory and made durable by [sync], all
   at once: one transaction is one group commit (quint/engine.qnt's sync).
   A crash loses what was appended and not synced; [load] returns what
   survives, in order, for [Engine.recover]. *)

module type S = sig
  type t

  val append : t -> Engine.record -> unit

  val sync : t -> int
  (** make every appended record durable; the last durable sequence number *)

  val load : t -> Engine.record list

  val crash : t -> unit
  (** lose what is not durable, as a crash of the process would *)
end

(* For the simulator and tests: the durable records are a list. *)
module Memory = struct
  type t = { mutable durable : Engine.record list; mutable pending : Engine.record list }

  let create () = { durable = []; pending = [] }
  let append t r = t.pending <- t.pending @ [ r ]

  let sync t =
    (* through the codec, so the memory store keeps exactly what SQLite would *)
    t.durable <- t.durable @ List.map (fun r -> Codec.of_record (Codec.record r)) t.pending;
    t.pending <- [];
    match List.rev t.durable with r :: _ -> r.seq | [] -> 0

  let load t = t.durable
  let crash t = t.pending <- []
end

module Sqlite = struct
  type t = { path : string; mutable db : Sqlite3.db; mutable pending : Engine.record list }

  let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "sqlite %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

  let open_db path =
    let db = Sqlite3.db_open path in
    check db (Sqlite3.exec db "PRAGMA journal_mode=WAL") "journal_mode";
    check db (Sqlite3.exec db "PRAGMA synchronous=FULL") "synchronous";
    check db (Sqlite3.exec db "CREATE TABLE IF NOT EXISTS records (seq INTEGER PRIMARY KEY, body TEXT NOT NULL)") "create";
    db

  let create path = { path; db = open_db path; pending = [] }
  let append t r = t.pending <- t.pending @ [ r ]

  let last t =
    let st = Sqlite3.prepare t.db "SELECT COALESCE(MAX(seq), 0) FROM records" in
    let n = match Sqlite3.step st with Sqlite3.Rc.ROW -> Int64.to_int (Sqlite3.column_int64 st 0) | rc -> check t.db rc "max"; 0 in
    ignore (Sqlite3.finalize st);
    n

  let sync t =
    if t.pending <> [] then begin
      check t.db (Sqlite3.exec t.db "BEGIN IMMEDIATE") "begin";
      let st = Sqlite3.prepare t.db "INSERT INTO records (seq, body) VALUES (?, ?)" in
      List.iter
        (fun (r : Engine.record) ->
          check t.db (Sqlite3.bind_int st 1 r.seq) "bind";
          check t.db (Sqlite3.bind_text st 2 (Codec.record r)) "bind";
          check t.db (Sqlite3.step st) "insert";
          check t.db (Sqlite3.reset st) "reset")
        t.pending;
      ignore (Sqlite3.finalize st);
      check t.db (Sqlite3.exec t.db "COMMIT") "commit";
      t.pending <- []
    end;
    last t

  let load t =
    let st = Sqlite3.prepare t.db "SELECT body FROM records ORDER BY seq" in
    let rec go acc =
      match Sqlite3.step st with
      | Sqlite3.Rc.ROW -> go (Codec.of_record (Sqlite3.column_text st 0) :: acc)
      | Sqlite3.Rc.DONE -> List.rev acc
      | rc -> check t.db rc "select"; List.rev acc
    in
    let rs = go [] in
    ignore (Sqlite3.finalize st);
    rs

  (* a crash: the connection goes, with whatever was not committed *)
  let crash t =
    t.pending <- [];
    ignore (Sqlite3.db_close t.db);
    t.db <- open_db t.path

  let close t = ignore (Sqlite3.db_close t.db)
end
