(* The central limiter (docs/cordis/DESIGN.md "Where the outbox runs";
   quint/outbox.qnt's grant; RFC 0006 decision 4F): one service beside pf
   that every agent's outbox asks for a token before an emission leaves.
   It holds no credentials. A grant is keyed by the emission (agent and
   journal key), so a request asked again (a resend after a lost answer, a
   re-release after a crash) gets the same grant back, never a second
   token; a new emission is granted only while the window's count is under
   the limit. A window of 0 seconds is one window forever (outbox.qnt's
   LIMIT); a positive window is a fixed-window rate. *)

module J = Yojson.Safe

type t = { db : Sqlite3.db; limit : int; window : float }

let check db rc what = if not (Sqlite3.Rc.is_success rc) then failwith (Printf.sprintf "limiter %s: %s (%s)" what (Sqlite3.Rc.to_string rc) (Sqlite3.errmsg db))

let open_ path ~limit ~window =
  let db = Sqlite3.db_open path in
  check db (Sqlite3.exec db "PRAGMA journal_mode=WAL") "journal_mode";
  check db (Sqlite3.exec db "PRAGMA synchronous=FULL") "synchronous";
  check db (Sqlite3.exec db "CREATE TABLE IF NOT EXISTS grants (emission TEXT PRIMARY KEY, win INTEGER NOT NULL)") "create";
  { db; limit; window }

let window_now t = if t.window <= 0. then 0 else int_of_float (Unix.gettimeofday () /. t.window)

let one_int db sql binds =
  let st = Sqlite3.prepare db sql in
  List.iteri (fun i b -> check db (match b with `T s -> Sqlite3.bind_text st (i + 1) s | `I n -> Sqlite3.bind_int st (i + 1) n) "bind") binds;
  let r = match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Int64.to_int (Sqlite3.column_int64 st 0)) | _ -> None in
  ignore (Sqlite3.finalize st);
  r

(* grant or deny the token for one emission; the same emission gets the same answer once granted *)
let grant t ~emission =
  match one_int t.db "SELECT win FROM grants WHERE emission = ?" [ `T emission ] with
  | Some _ -> true
  | None ->
      let w = window_now t in
      check t.db (Sqlite3.exec t.db "BEGIN IMMEDIATE") "begin";
      let used = Option.value (one_int t.db "SELECT COUNT(*) FROM grants WHERE win = ?" [ `I w ]) ~default:0 in
      let ok = used < t.limit in
      if ok then begin
        let st = Sqlite3.prepare t.db "INSERT INTO grants (emission, win) VALUES (?, ?)" in
        check t.db (Sqlite3.bind_text st 1 emission) "bind";
        check t.db (Sqlite3.bind_int st 2 w) "bind";
        check t.db (Sqlite3.step st) "insert";
        ignore (Sqlite3.finalize st)
      end;
      check t.db (Sqlite3.exec t.db "COMMIT") "commit";
      ok

let granted t = Option.value (one_int t.db "SELECT COUNT(*) FROM grants" []) ~default:0

(* {"op":"grant","emission":E} -> {"op":"granted"|"denied","emission":E}, one request per connection *)
let serve t ~socket =
  Sys.set_signal Sys.sigpipe Sys.Signal_ignore;
  (try Sys.remove socket with Sys_error _ -> ());
  let fd = Unix.socket Unix.PF_UNIX Unix.SOCK_STREAM 0 in
  Unix.bind fd (Unix.ADDR_UNIX socket);
  Unix.listen fd 16;
  print_endline "ready";
  flush stdout;
  while true do
    let c, _ = Unix.accept fd in
    let ic = Unix.in_channel_of_descr c and oc = Unix.out_channel_of_descr c in
    (try
       let j = J.from_string (input_line ic) in
       let emission = J.Util.(j |> member "emission" |> to_string) in
       let ok = grant t ~emission in
       output_string oc (J.to_string (`Assoc [ ("op", `String (if ok then "granted" else "denied")); ("emission", `String emission) ]) ^ "\n");
       flush oc
     with _ -> ());
    (try Unix.close c with Unix.Unix_error _ -> ())
  done

(* the outbox's side: ask for one emission's token; an unreachable limiter is a denial, asked again later *)
let ask ~socket ~emission =
  match
    let fd = Unix.socket Unix.PF_UNIX Unix.SOCK_STREAM 0 in
    Fun.protect ~finally:(fun () -> try Unix.close fd with Unix.Unix_error _ -> ()) @@ fun () ->
    Unix.setsockopt_float fd Unix.SO_RCVTIMEO 5.;
    Unix.connect fd (Unix.ADDR_UNIX socket);
    let oc = Unix.out_channel_of_descr fd and ic = Unix.in_channel_of_descr fd in
    output_string oc (J.to_string (`Assoc [ ("op", `String "grant"); ("emission", `String emission) ]) ^ "\n");
    flush oc;
    J.Util.(J.from_string (input_line ic) |> member "op" |> to_string) = "granted"
  with
  | ok -> ok
  | exception _ -> false
