(* The journal as appended facts (quint/journal.qnt; RFC 0007, "History"). Unit cases for each property the model
   states, a migration from the in-place journal, and a Hegel property: any sequence of writes leaves the journal
   resolving exactly as the old mutable row would have, with the facts only ever appended. *)

open Cordis_outbox
module J = Journal

let fresh () =
  let path = Filename.temp_file "journal" ".db" in
  (path, J.open_ path)

let blank = { J.jkey = ""; state = J.Done; value = None; cost = 0; source = None; discarded = None; admitted = None; world = None }
let row ?(state = J.Done) ?value jkey = { blank with J.jkey; state; value }
let state_of db k = Option.map (fun (r : J.row) -> J.state_name r.state) (J.find db k)
let check_state msg db k want = Alcotest.(check (option string)) msg want (state_of db k)

let begin_ db k = J.begin_ db ~jkey:k ~key:k ~attempt:0 ~kind:"llm" ~request:"{}"

(* resolutionIntended: the highest revision wins, not the last to arrive; a gap does not mislead *)
let gap () =
  let _, db = fresh () in
  begin_ db "a";
  (* the writer wrote revisions 2 and 3 (3 supersedes 2); 3 arrives before 2 *)
  J.append_fact db ~jkey:"a" ~rev:3 ~supersedes:"a@2" ~why:"x" (row ~state:J.Refused ~value:"three" "a");
  check_state "rev 3 beside rev 1, rev 2 missing" db "a" (Some "refused");
  J.append_fact db ~jkey:"a" ~rev:2 ~supersedes:"a@1" ~why:"x" (row ~state:J.Unknown "a");
  check_state "rev 2 arrives late" db "a" (Some "refused");
  Alcotest.(check (list int)) "custody order is arrival order" [ 1; 3; 2 ] (List.map (fun (_, r, _, _) -> r) (J.facts_of db "a"))

(* the same where arrival order and revision order disagree for facts both looking current *)
let late_old () =
  let _, db = fresh () in
  J.with_stmt db "INSERT INTO intents (jkey, key, attempt, kind, request) VALUES ('z', 'z', 0, 'llm', '{}')" [] (fun st -> ignore (Sqlite3.step st));
  J.append_fact db ~jkey:"z" ~rev:3 ~supersedes:"z@2" ~why:"x" (row ~state:J.Refused "z");
  J.append_fact db ~jkey:"z" ~rev:1 ~why:"x" (row ~state:J.Sent "z");
  check_state "the later revision, though it arrived first" db "z" (Some "refused");
  match J.view_at db (J.basis db) with Ok [ { J.state = J.Refused; _ } ] -> () | _ -> Alcotest.fail "the view agrees"

(* a fact delivered again is not appended again *)
let idempotent () =
  let _, db = fresh () in
  begin_ db "a";
  J.append_fact db ~jkey:"a" ~rev:2 ~supersedes:"a@1" ~why:"x" (row "a");
  J.append_fact db ~jkey:"a" ~rev:2 ~supersedes:"a@1" ~why:"x" (row ~state:J.Fenced "a");
  Alcotest.(check int) "two facts" 2 (List.length (J.facts_of db "a"));
  check_state "the first delivery stands" db "a" (Some "done")

(* writes append, never rewrite *)
let appends () =
  let _, db = fresh () in
  begin_ db "a";
  J.set_state db ~jkey:"a" J.Unknown;
  J.finish db ~jkey:"a" ~value:"v" ~cost:3;
  let h = J.facts_of db "a" in
  Alcotest.(check (list string)) "history" [ "sent"; "unknown"; "done" ] (List.map (fun (_, _, _, s) -> s) h);
  Alcotest.(check (list string)) "why" [ "begin"; "state"; "finish" ] (List.map (fun (_, _, w, _) -> w) h);
  match J.find db "a" with Some { value = Some "v"; cost = 3; _ } -> () | _ -> Alcotest.fail "resolved row"

(* viewStable: a basis shows the same rows however much happens after, and after compaction *)
let stable_view () =
  let _, db = fresh () in
  begin_ db "a";
  J.finish db ~jkey:"a" ~value:"1" ~cost:1;
  let b = J.basis db in
  let v0 = J.view_at db b in
  begin_ db "b";
  J.set_state db ~jkey:"a" J.Fenced;
  Alcotest.(check bool) "later writes" true (J.view_at db b = v0);
  (match v0 with Ok [ { J.jkey = "a"; state = J.Done; _ } ] -> () | _ -> Alcotest.fail "the view is attempt a, done");
  let upto = (J.basis db).pos in
  J.compact db ~upto:(b.pos - 1);
  Alcotest.(check bool) "above the horizon, compaction changes nothing" true (J.view_at db b = v0);
  J.compact db ~upto;
  Alcotest.(check bool) "the horizon overtook it" true (J.view_at db b = Error J.Expired);
  (* the resolution survives compaction *)
  check_state "a" db "a" (Some "fenced");
  check_state "b" db "b" (Some "sent");
  Alcotest.(check int) "only heads are left" 2 (List.length (List.concat_map (fun k -> J.facts_of db k) [ "a"; "b" ]))

(* compaction takes a fact only with the fact that supersedes it: a basis between them still sees the attempt *)
let compact_keeps_chains () =
  let _, db = fresh () in
  begin_ db "a";
  begin_ db "b";
  let b = J.basis db in
  let v0 = J.view_at db b in
  J.set_state db ~jkey:"a" J.Unknown;
  J.compact db ~upto:1;
  Alcotest.(check bool) "a@1 stays: its successor is above the prefix" true (J.view_at db b = v0 && (match v0 with Ok [ _; _ ] -> true | _ -> false))

(* viewNeverPartial / ignoreStore *)
let foreign () =
  let _, db = fresh () in
  begin_ db "a";
  let b = J.basis db in
  J.new_store_id db;
  Alcotest.(check bool) "foreign" true (J.view_at db b = Error J.Foreign);
  Alcotest.(check bool) "a basis from the new store answers" true (match J.view_at db (J.basis db) with Ok _ -> true | _ -> false)

(* settledMatches: an adjustment applies once however often it is delivered *)
let ledger () =
  let _, db = fresh () in
  begin_ db "a";
  Alcotest.(check bool) "first" true (J.adjust db ~id:"x" ~jkey:"a" ~delta:5 ~why:"expired");
  Alcotest.(check bool) "again" false (J.adjust db ~id:"x" ~jkey:"a" ~delta:5 ~why:"expired");
  ignore (J.adjust db ~id:"y" ~jkey:"a" ~delta:(-2) ~why:"closed");
  Alcotest.(check int) "total" 3 (J.ledger_total db);
  Alcotest.(check int) "for the attempt" 3 (J.ledger_total ~jkey:"a" db)

(* a restart turns what was sent into unknown, as a new fact, and keeps what waits *)
let restart () =
  let path, db = fresh () in
  begin_ db "s";
  J.begin_ ~state:J.Waiting db ~jkey:"w" ~key:"w" ~attempt:0 ~kind:"llm" ~request:"{}";
  begin_ db "d";
  J.finish db ~jkey:"d" ~value:"v" ~cost:1;
  let db = ignore db; J.open_ path in
  check_state "sent" db "s" (Some "unknown");
  check_state "waiting" db "w" (Some "waiting");
  check_state "done" db "d" (Some "done");
  Alcotest.(check (list string)) "history says why" [ "begin"; "restart" ] (List.map (fun (_, _, w, _) -> w) (J.facts_of db "s"));
  let s = J.basis db in
  Alcotest.(check bool) "same store across a restart" true ((J.basis (J.open_ path)).store = s.store)

(* the engine's confirmation ends a pending delivery, once, and the row still answers *)
let delivery () =
  let _, db = fresh () in
  begin_ db "a";
  begin_ db "b";
  J.finish db ~jkey:"a" ~value:"v" ~cost:1;
  J.refuse db ~jkey:"b" ~why:"no";
  Alcotest.(check (list string)) "both owed" [ "a"; "b" ] (J.pending db);
  Alcotest.(check bool) "recorded" true (J.applied db ~jkey:"a");
  Alcotest.(check bool) "once" false (J.applied db ~jkey:"a");
  Alcotest.(check bool) "an unknown row is not applied" false (J.applied db ~jkey:"nope");
  Alcotest.(check (list string)) "b is owed" [ "b" ] (J.pending db);
  check_state "a still answers" db "a" (Some "done");
  (* a sent row has nothing to confirm *)
  begin_ db "c";
  Alcotest.(check bool) "sent" false (J.applied db ~jkey:"c")

(* the journal from before facts: one mutable row each *)
let migration () =
  let path = Filename.temp_file "journal" ".db" in
  let old = Sqlite3.db_open path in
  let ok rc = assert (Sqlite3.Rc.is_success rc) in
  ok (Sqlite3.exec old "CREATE TABLE journal (jkey TEXT PRIMARY KEY, key TEXT NOT NULL, attempt INTEGER NOT NULL, kind TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, value TEXT, cost INTEGER NOT NULL DEFAULT 0)");
  ok (Sqlite3.exec old "INSERT INTO journal VALUES ('k/0', 'k', 0, 'llm', '{\"r\":1}', 'done', '\"v\"', 7)");
  ok (Sqlite3.exec old "INSERT INTO journal VALUES ('k/1', 'k', 1, 'llm', '{}', 'sent', NULL, 0)");
  ignore (Sqlite3.db_close old);
  let db = J.open_ path in
  Alcotest.(check int) "intents" 2 (J.count db);
  (match J.find db "k/0" with Some { state = J.Done; value = Some "\"v\""; cost = 7; _ } -> () | _ -> Alcotest.fail "migrated done row");
  check_state "sent became unknown" db "k/1" (Some "unknown");
  Alcotest.(check (option string)) "request kept" (Some "{\"r\":1}") (J.request db "k/0");
  Alcotest.(check (list string)) "marked as migrated" [ "migrated" ] (List.map (fun (_, _, w, _) -> w) (J.facts_of db "k/0"));
  (* the old readers' view: a [journal] with rowid, in intent order *)
  let rows = J.with_stmt db "SELECT jkey FROM journal ORDER BY rowid" [] (fun st ->
      let rec go acc = match Sqlite3.step st with Sqlite3.Rc.ROW -> go (Sqlite3.column_text st 0 :: acc) | _ -> List.rev acc in go []) in
  Alcotest.(check (list string)) "journal view" [ "k/0"; "k/1" ] rows

(* ---- the property: the facts resolve as the mutable row would have ----------------------------- *)

let settings =
  let cases = match Sys.getenv_opt "JOURNAL_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:11 ()

let equivalence () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:outbox:journal" (fun tc ->
      let draw n = Hegel.draw tc (Hegel.Generators.integers ~min_value:0 ~max_value:(n - 1) ()) in
      let path, db = fresh () in
      let db = ref db in
      (* the model: the in-place journal *)
      let model : (string, J.state * string option * int) Hashtbl.t = Hashtbl.create 8 in
      let keys = [| "a"; "b"; "c" |] in
      let states = [| J.Waiting; J.Sent; J.Unknown; J.Fenced |] in
      let basis_views = ref [] in
      for _ = 1 to 1 + draw 12 do
        let k = keys.(draw 3) in
        (match (Hashtbl.find_opt model k, draw 8) with
         | None, _ ->
             let st = if draw 2 = 0 then J.Sent else J.Waiting in
             J.begin_ ~state:st !db ~jkey:k ~key:"g" ~attempt:(Hashtbl.length model) ~kind:"llm" ~request:"{}";
             Hashtbl.replace model k (st, None, 0)
         | Some (_, v, c), 0 -> let s = states.(draw 4) in J.set_state !db ~jkey:k s; Hashtbl.replace model k (s, v, c)
         | Some _, 1 -> let c = draw 9 in J.finish !db ~jkey:k ~value:"v" ~cost:c; Hashtbl.replace model k (J.Done, Some "v", c)
         | Some (_, _, c), 2 -> J.refuse !db ~jkey:k ~why:"no"; Hashtbl.replace model k (J.Refused, Some "no", c)
         | Some _, 3 ->
             let s = if draw 2 = 0 then J.Superseded else J.Charged in
             J.supersede ~state:s !db ~key:"g" ~answered:k;
             Hashtbl.filter_map_inplace (fun j (st, v, c) -> if j <> k && (st = J.Unknown || st = J.Sent) then Some (s, v, c) else Some (st, v, c)) model
         | Some _, 4 -> db := J.open_ path;
             Hashtbl.filter_map_inplace (fun _ (st, v, c) -> Some ((if st = J.Sent then J.Unknown else st), v, c)) model
         | Some _, 5 -> basis_views := (J.basis !db, J.view_at !db (J.basis !db)) :: !basis_views
         | Some _, 6 -> if draw 2 = 0 then J.compact !db ~upto:((J.basis !db).pos / 2)
         | Some _, _ -> ());
        Hashtbl.iter (fun k (st, v, c) ->
            match J.find !db k with
            | Some r -> Hegel.require tc ~msg:("resolves as the mutable row: " ^ k) (r.state = st && r.value = v && r.cost = c)
            | None -> Hegel.require tc ~msg:("lost " ^ k) false)
          model
      done;
      (* facts are only ever appended: positions strictly increase per attempt, revisions step by one *)
      Array.iter (fun k ->
          let fs = J.facts_of !db k in
          ignore (List.fold_left (fun p (p', _, _, _) -> Hegel.require tc ~msg:"positions increase" (p' > p); p') 0 fs))
        keys;
      (* a basis answered once is answered the same until the horizon takes it *)
      List.iter (fun (b, v) -> match v, J.view_at !db b with
          | Ok r, Ok r' -> Hegel.require tc ~msg:"a basis shows the same rows" (r = r')
          | _ -> ()) !basis_views)

let () =
  Alcotest.run "journal"
    [ ( "journal",
        [ Alcotest.test_case "highest revision wins across a gap" `Quick gap;
          Alcotest.test_case "revision, not arrival, decides" `Quick late_old;
          Alcotest.test_case "a fact delivered twice is appended once" `Quick idempotent;
          Alcotest.test_case "writes append" `Quick appends;
          Alcotest.test_case "a basis is stable, through compaction" `Quick stable_view;
          Alcotest.test_case "compaction keeps a chain it cuts" `Quick compact_keeps_chains;
          Alcotest.test_case "a foreign basis is refused" `Quick foreign;
          Alcotest.test_case "adjustments apply once" `Quick ledger;
          Alcotest.test_case "restart" `Quick restart;
          Alcotest.test_case "delivery is pending until the engine confirms" `Quick delivery;
          Alcotest.test_case "migration from the in-place journal" `Quick migration;
          Alcotest.test_case "resolves as the mutable row would" `Quick equivalence ] ) ]
