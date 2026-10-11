(* cordis-run: one graph instance on the engine, as a process (RFC 0006
   revision 2, stage 2): the run store on SQLite, the outbox over a Unix
   socket, receipts that do not come in time taken as lost. Started again
   on the same store after a crash, it recovers from the durable records
   and finishes.

     cordis-run --graph FILE --name GRAPH --store DB --socket PATH --input NAME=JSON
                [--observational KIND]... [--keyed KIND]... [--operations FILE]
                [--worst KIND=N]... [--budget N]
                [--timeout SECONDS] [--crash-after N]

   --timeout is how long to wait for the outbox before sending again an
   intent it has not acknowledged (the same attempt: nothing ran). An
   acknowledged intent is the outbox's to answer, however long its call
   takes; only the outbox reports it lost (quint/intake.qnt).

   An intent kind is effectful unless --observational or --keyed says
   otherwise, or --operations (an OpenAPI import's operations.json) gives
   its class.

   It prints its outcome as JSON: "outcome" (rest, rest_unbound, failed,
   blocked), each asset's value, "refused" (appends a full stream refused),
   and the spend. At rest with every asset bound it exits 0; at rest with
   an asset a guard left unbound (a loop that rested without shipping) 4;
   failed (a computation failed) or blocked (by its budget, or intents the
   limiter has deferred for longer than --max-wait) 2, saying why on
   stderr too. A deferred
   intent was definitely not sent, so its wait is not a loss.
   --admit FILE soaks the candidate revision in a shadow answered from the
   run's own records, and if it comes to rest, stores its text and cuts
   the instance over to it (RFC 0006 decision 6); a failed soak, or a
   candidate that rewires the graph, is refused with exit 2 and changes
   nothing. Admitting a candidate already live or being cut over does
   nothing, so an admission lost to a crash is simply asked for again.
   The store keeps every revision's text, so recovery takes the starting
   revision and the candidates from it, whatever --graph names.
   --audit re-evaluates every recorded turn before resuming (RFC 0006
   decision 5C) and refuses to resume records the graph did not write.
   --crash-after N exits abruptly after N engine actions, and
   --crash-after-receipts N right after applying the Nth receipt, before
   its record is synced: a test's crashes, losing what was not synced. *)

open Cordis_engine
module V = Cordis_cel.Value
module J = Yojson.Safe

(* plain JSON as a CEL value: objects are maps (records), numbers ints or doubles *)
let rec of_json (j : J.t) : V.t =
  match j with
  | `Null -> V.Null
  | `Bool b -> V.Bool b
  | `Int n -> V.Int (Int64.of_int n)
  | `Intlit s -> V.Int (Int64.of_string s)
  | `Float f -> V.Double f
  | `String s -> V.String s
  | `List xs -> V.List (Array.of_list (List.map of_json xs))
  | `Assoc kv -> V.make_map (List.map (fun (k, x) -> (V.String k, of_json x)) kv)

let rec to_json (v : V.t) : J.t =
  match v with
  | V.Null -> `Null
  | V.Bool b -> `Bool b
  | V.Int n -> `Intlit (Int64.to_string n)
  | V.Double d -> `Float d
  | V.String s -> `String s
  | V.List xs -> `List (Array.to_list (Array.map to_json xs))
  | V.Map m -> `Assoc (List.map (fun (k, x) -> ((match k with V.String s -> s | k -> V.to_string k), to_json x)) m)
  | v -> `String (V.to_string v)

(* a revision from a graph's text: its id is the graph definition's hash (which covers its cells); a
   cell's state carries unless Compat's state plan against [from] says to migrate it *)
let revision_of ?from ~name text =
  match Cordis_graph.Parse.file text with
  | Error m -> Error m
  | Ok file -> (
      match Cordis_graph.Check.check file with
      | Error es -> Error (String.concat "\n" es)
      | Ok checked -> (
          let hs = Cordis_graph.Print.hashes file in
          match List.assoc_opt name hs with
          | None -> Error ("no graph " ^ name)
          | Some id ->
              let cell_hashes =
                List.filter_map (fun (c : Cordis_graph.Check.cell_info) -> Option.map (fun h -> (c.cname, h)) (List.assoc_opt c.cname hs)) checked.cells
              in
              let plan =
                match from with
                | None -> []
                | Some (old_file, old_checked) -> Cordis_graph.Compat.state_plan ~old_file ~old_checked ~new_file:file ~new_checked:checked
              in
              let carry c = match List.assoc_opt c plan with Some (Cordis_graph.Compat.Migrate _) -> false | _ -> true in
              Ok ({ Engine.id; checked; cell_hashes; carry }, file)))

(* The shadow's answers, from the instance's own durable records: the receipt recorded for the same
   kind and request, else the last recorded for the kind. A soak shows a candidate runs to rest without
   failing on answers like the live run's; it cannot show they are the answers it would get. *)
let cassette_of (records : Engine.record list) =
  let exact = Hashtbl.create 16 and by_kind = Hashtbl.create 4 and awaiting = Hashtbl.create 4 in
  List.iter
    (fun (r : Engine.record) ->
      List.iter
        (function
          | Engine.Slot (a, s) -> (
              match s.fiber with
              | Engine.Awaiting { intent; _ } -> Hashtbl.replace awaiting a intent
              | Engine.Running { receipts = _ :: _ as rs; _ } -> (
                  match Hashtbl.find_opt awaiting a with
                  | Some (i : Engine.intent) ->
                      let v = snd (List.nth rs (List.length rs - 1)) in
                      Hashtbl.replace exact (i.kind, V.to_string i.request) v;
                      Hashtbl.replace by_kind i.kind v;
                      Hashtbl.remove awaiting a
                  | None -> ())
              | _ -> ())
          | _ -> ())
        r.facts)
    records;
  fun (i : Engine.intent) ->
    match Hashtbl.find_opt exact (i.kind, V.to_string i.request) with
    | Some v -> (v, 0)
    | None -> (
        match Hashtbl.find_opt by_kind i.kind with
        | Some v -> (v, 0)
        | None -> raise (Engine.Engine_error (Printf.sprintf "the shadow asked for %s, which the records cannot answer" i.kind)))

let () =
  let graph = ref "" and name = ref "" and store = ref "" and socket = ref "" and inputs = ref [] in
  let observational = ref [] and keyed = ref [] and worst = ref [] and budget = ref None and timeout = ref 30. and crash_after = ref 0 and crash_receipts = ref 0 and max_wait = ref 0. and audit = ref false and dump = ref false and admit = ref "" in
  let pair s = match String.index_opt s '=' with Some i -> (String.sub s 0 i, String.sub s (i + 1) (String.length s - i - 1)) | None -> failwith ("expected K=V: " ^ s) in
  Arg.parse
    [ ("--graph", Arg.Set_string graph, "FILE"); ("--name", Arg.Set_string name, "GRAPH"); ("--store", Arg.Set_string store, "DB");
      ("--socket", Arg.Set_string socket, "PATH"); ("--input", Arg.String (fun s -> inputs := !inputs @ [ pair s ]), "NAME=JSON");
      ("--observational", Arg.String (fun k -> observational := k :: !observational), "KIND");
      ("--keyed", Arg.String (fun k -> keyed := k :: !keyed), "KIND");
      ("--operations", Arg.String (fun f ->
           match J.from_file f with
           | `Assoc kv -> (
               match List.assoc_opt "operations" kv with
               | Some (`Assoc ops) ->
                   List.iter
                     (fun (k, o) ->
                       match o with
                       | `Assoc ov -> (
                           match List.assoc_opt "class" ov with
                           | Some (`String "observational") -> observational := k :: !observational
                           | Some (`String "keyed") -> keyed := k :: !keyed
                           | _ -> ())
                       | _ -> ())
                     ops
               | _ -> ())
           | _ -> ()), "FILE an OpenAPI import's operations.json: each operation's class");
      ("--worst", Arg.String (fun s -> let k, n = pair s in worst := (k, int_of_string n) :: !worst), "KIND=N");
      ("--budget", Arg.Int (fun n -> budget := Some n), "N"); ("--timeout", Arg.Set_float timeout, "SECONDS");
      ("--crash-after", Arg.Set_int crash_after, "N");
      ("--crash-after-receipts", Arg.Set_int crash_receipts, "N");
      ("--admit", Arg.Set_string admit, "FILE a candidate revision: soak it in a shadow, then cut over to it");
      ("--dump-records", Arg.Set dump, " print the store's durable records, one per line, and exit");
      ("--audit", Arg.Set audit, " audit the durable records against the graph before resuming, and refuse on a difference");
      ("--max-wait", Arg.Set_float max_wait, "SECONDS how long to wait on intents the limiter deferred (0: forever)") ]
    (fun _ -> ()) "cordis-run --graph FILE --name GRAPH --store DB --socket PATH --input NAME=JSON";
  let fail fmt = Printf.ksprintf (fun m -> prerr_endline ("cordis-run: " ^ m); exit 2) fmt in
  let classes k = if List.mem k !observational then Engine.Observational else if List.mem k !keyed then Engine.Keyed else Engine.Effectful in
  let worst k = Option.value (List.assoc_opt k !worst) ~default:0 in
  let st = Store.Sqlite.create !store in
  let durable = Store.Sqlite.load st in
  if !dump then begin
    (* the store's logical content: what a rollback must restore exactly, whatever the bytes on disk *)
    List.iter (fun r -> print_endline (Codec.record r)) durable;
    exit 0
  end;
  (* the revisions: the starting one, and the candidates admitted since, from the store *)
  let stored = Store.Sqlite.revisions st in
  let start_text =
    match List.find_opt (fun (_, role, _) -> role = "start") stored with
    | Some (_, _, text) -> text
    | None -> In_channel.with_open_text !graph In_channel.input_all
  in
  let start, start_file = match revision_of ~name:!name start_text with Ok r -> r | Error m -> fail "%s" m in
  (if stored <> [] && !graph <> "" then
     match revision_of ~name:!name (In_channel.with_open_text !graph In_channel.input_all) with
     | Ok (g, _) when g.id <> start.id ->
         prerr_endline "cordis-run: the store's own starting revision is used, not --graph's (new code enters by --admit)"
     | _ -> ());
  if not (List.exists (fun (_, role, _) -> role = "start") stored) then Store.Sqlite.put_revision st ~id:start.id ~role:"start" ~text:start_text;
  let candidates =
    List.filter_map
      (fun (_, role, text) ->
        if role <> "candidate" then None
        else match revision_of ~from:(start_file, start.checked) ~name:!name text with Ok (r, f) -> Some (r, f, text) | Error m -> fail "a stored revision: %s" m)
      stored
  in
  let revisions = List.map (fun (r, _, _) -> r) candidates in
  let checked = start.checked in
  if !audit && durable <> [] then begin
    (* resuming records on code that did not write them is a cutover, which is admission's, not recovery's *)
    match Engine.audit ~classes ?budget:!budget ~worst ~revisions checked !name durable with
    | [] -> ()
    | ds ->
        List.iter (fun d -> prerr_endline ("cordis-run: audit: " ^ d)) ds;
        prerr_endline "cordis-run: the records were not written by this graph; not resuming";
        exit 2
  end;
  let t, cmds =
    if durable = [] then (Engine.create ~classes ?budget:!budget ~worst ~revision:start.id ~cell_hashes:start.cell_hashes checked !name, [])
    else Engine.recover ~classes ?budget:!budget ~worst ~revision:start.id ~cell_hashes:start.cell_hashes ~revisions checked !name durable
  in
  (* the outbox *)
  let fd = Unix.socket Unix.PF_UNIX Unix.SOCK_STREAM 0 in
  Unix.connect fd (Unix.ADDR_UNIX !socket);
  let oc = Unix.out_channel_of_descr fd in
  (* frames are read through our own buffer, never an in_channel's: select on the socket would not see a
     frame already pulled into a channel's buffer, and would wait out the timeout with a receipt in hand *)
  let pending = Buffer.create 4096 and chunk = Bytes.create 4096 in
  let next_line () =
    let s = Buffer.contents pending in
    match String.index_opt s '\n' with
    | Some i ->
        Buffer.clear pending;
        Buffer.add_string pending (String.sub s (i + 1) (String.length s - i - 1));
        Some (String.sub s 0 i)
    | None -> None
  in
  let fill () =
    match Unix.read fd chunk 0 (Bytes.length chunk) with
    | 0 -> prerr_endline "cordis-run: the outbox closed the connection"; exit 2
    | k -> Buffer.add_subbytes pending chunk 0 k
  in
  let send j = output_string oc (J.to_string j ^ "\n"); flush oc in
  let t = ref t in
  let obey cmds =
    List.iter
      (function
        | Engine.Persist r -> Store.Sqlite.append st r
        | Engine.Release i -> send (`Assoc [ ("op", `String "intent"); ("intent", Codec.intent i) ])
        | Engine.Reconcile (k, n) -> send (`Assoc [ ("op", `String "reconcile"); ("key", `String k); ("attempt", `Int n) ]))
      cmds
  in
  let feed ev = let t', cmds = Engine.handle !t ev in t := t'; obey cmds in
  obey cmds;
  (* the sender keeps an input until it is durable: set again after a crash, it changes nothing *)
  List.iter (fun (n, j) -> feed (Engine.Set_input (n, of_json (J.from_string j)))) !inputs;
  (* admission: soak the candidate in a shadow, then cut over *)
  if !admit <> "" then begin
    let text = In_channel.with_open_text !admit In_channel.input_all in
    (* the state plan is against the revision live now *)
    let live_file, live_checked =
      match List.find_opt (fun (r, _, _) -> r.Engine.id = Engine.revision !t) candidates with
      | Some (r, f, _) -> (f, r.Engine.checked)
      | None -> (start_file, start.checked)
    in
    match revision_of ~from:(live_file, live_checked) ~name:!name text with
    | Error m -> fail "the candidate does not check:\n%s" m
    | Ok (c, _) when c.id = Engine.revision !t || Engine.cutting !t = Some c.id ->
        prerr_endline ("cordis-run: " ^ c.id ^ " is already " ^ if c.id = Engine.revision !t then "live" else "being cut over to")
    | Ok (c, _) -> (
        match Engine.soak ~classes ?budget:!budget ~worst c !name ~inputs:(Engine.inputs !t) ~cassette:(cassette_of (Store.Sqlite.load st)) with
        | Error m -> fail "the candidate failed its soak, and is not admitted: %s" m
        | Ok () -> (
            match Engine.admit !t c with
            | Error m -> fail "the candidate is not admitted: %s" m
            | Ok (t', cmds) ->
                (* its text is durable before the record that names it *)
                Store.Sqlite.put_revision st ~id:c.id ~role:"candidate" ~text;
                t := t';
                obey cmds;
                prerr_endline ("cordis-run: admitted " ^ c.id)))
  end;
  let actions = ref 0 and receipts = ref 0 in
  let deferred = Hashtbl.create 8 and waited = ref 0. in
  (* attempts the outbox has acknowledged: journaled, so its to answer (quint/intake.qnt) *)
  let accepted = Hashtbl.create 8 in
  (* a cost the world did not report is null: negative, which the engine charges at the worst case *)
  let cost_of j = match J.Util.member "cost" j with `Int c -> c | _ -> -1 in
  (* the world's word on where an answer came from (halo-flash's Halo-Answer): only a replayed or adopted
     answer covers a keyed intent's other sends; one generated afresh, or one the world says nothing of
     (a world that keeps no keys says nothing), does not; and any answer may say what was discarded under the
     key (generations the world stopped), which is then what the other sends are charged *)
  let generated j = match J.Util.member "answer" j with `String ("replayed" | "adopted") -> false | _ -> true in
  let done_ j =
    let v = Codec.of_value (J.Util.member "value" j) and c = cost_of j in
    let discarded = match J.Util.member "discarded" j with `Int d -> Some d | _ -> None in
    (* a discarded count, on any answer, is the other sends' exact charge *)
    if generated j || discarded <> None then Engine.Generated (v, c, discarded) else Engine.Done (v, c)
  in
  let sync () =
    let d = Store.Sqlite.sync st in
    feed (Engine.Durable d)
  in
  let awaited () =
    Engine.Smap.fold
      (fun _ (s : Engine.slot) acc -> match s.fiber with Engine.Awaiting { intent; _ } -> intent :: acc | _ -> acc)
      !t.slots []
  in
  let asset_names () =
    List.map (fun (a : Cordis_graph.Check.asset_info) -> a.aname) (List.find (fun (g : Cordis_graph.Check.graph_info) -> g.gname = !name) checked.graphs).assets
  in
  (* the run's outcome, on stdout as JSON, whatever it is *)
  let report outcome extra =
    let values = List.map (fun a -> (a, match Engine.value !t a with Some v -> to_json v | None -> `Null)) (asset_names ()) in
    let reserved, settled = Engine.spend !t in
    print_endline
      (J.to_string
         (`Assoc
           ([ ("outcome", `String outcome); ("assets", `Assoc values); ("refused", `Int (Engine.refused !t)); ("settled", `Int settled);
              ("reserved", `Int reserved); ("revision", `String (Engine.revision !t)) ]
           @ extra)))
  in
  let rec loop () =
    sync ();
    match Engine.ready !t with
    | a :: _ ->
        let t', cmds = Engine.perform !t a in
        t := t';
        obey cmds;
        incr actions;
        if !crash_after > 0 && !actions >= !crash_after then begin
          (* a crash: whatever was appended and not synced is lost *)
          prerr_endline (Printf.sprintf "cordis-run: crashing after %d actions" !actions);
          exit 3
        end;
        loop ()
    (* a failed computation first: a graph with one is quiet too, and must not read as at rest *)
    | [] when Engine.failures !t <> [] ->
        List.iter (fun (a, m) -> prerr_endline (Printf.sprintf "cordis-run: %s failed: %s" a m)) (Engine.failures !t);
        report "failed" [ ("failures", `Assoc (List.map (fun (a, m) -> (a, `String m)) (Engine.failures !t))) ];
        exit 2
    | [] when Engine.quiet !t ->
        let unbound = List.filter (fun a -> Engine.value !t a = None) (asset_names ()) in
        if unbound = [] then (report "rest" []; exit 0)
        else begin
          (* at rest, but a guard left an asset unbound (a loop that rested without shipping) *)
          report "rest_unbound" [ ("unbound", `List (List.map (fun a -> `String a) unbound)) ];
          exit 4
        end
    | [] when awaited () = [] && not (Engine.Smap.exists (fun _ (s : Engine.slot) -> match s.fiber with Engine.Uncertain _ -> true | _ -> false) !t.slots) ->
        prerr_endline "cordis-run: blocked (a turn's reservation does not fit the budget)";
        report "blocked" [ ("why", `String "a turn's reservation does not fit the budget") ];
        exit 2
    | [] -> (
        (* wait for the outbox. An attempt it has acknowledged is waited on, however long its call takes:
           only the outbox says it was lost. One it has not acknowledged is sent again, the same attempt
           (the outbox's journal makes that harmless): nothing ran, so nothing is lost or charged *)
        let ready_line = match next_line () with Some l -> Some l | None -> (match Unix.select [ fd ] [] [] !timeout with [], _, _ -> None | _ -> fill (); next_line ()) in
        match ready_line with
        | None when Buffer.length pending > 0 -> loop ()
        | None ->
            if Sys.getenv_opt "CORDIS_RUN_TRACE" <> None then prerr_endline "cordis-run: timed out waiting";
            let live = List.filter (fun (i : Engine.intent) -> not (Hashtbl.mem deferred (i.key, i.attempt))) (awaited ()) in
            if live = [] && awaited () <> [] then begin
              waited := !waited +. !timeout;
              if !max_wait > 0. && !waited >= !max_wait then begin
                prerr_endline "cordis-run: blocked (the limiter has deferred every intent it awaits)";
                report "blocked" [ ("why", `String "the limiter has deferred every intent it awaits") ];
                exit 2
              end
            end;
            List.iter (fun (i : Engine.intent) -> if not (Hashtbl.mem accepted (i.key, i.attempt)) then obey [ Engine.Release i ]) live;
            Engine.Smap.iter
              (fun _ (s : Engine.slot) -> match s.fiber with Engine.Uncertain { intent; _ } -> obey [ Engine.Reconcile (intent.key, intent.attempt) ] | _ -> ())
              !t.slots;
            loop ()
        | Some line ->
            if Sys.getenv_opt "CORDIS_RUN_TRACE" <> None then prerr_endline ("cordis-run: <- " ^ line);
            let open J.Util in
            let j = J.from_string line in
            let key = j |> member "key" |> to_string and attempt = j |> member "attempt" |> to_int in
            let receipts_before = !receipts in
            let op = j |> member "op" |> to_string in
            if op = "ack" then Hashtbl.replace accepted (key, attempt) ();
            if op = "deferred" then Hashtbl.replace deferred (key, attempt) () else if op <> "ack" then Hashtbl.remove deferred (key, attempt);
            if op <> "deferred" && op <> "ack" then waited := 0.;
            (match op with
             | "receipt" -> incr receipts; feed (Engine.Receipt (key, attempt, done_ j))
             | "lost" -> feed (Engine.Receipt (key, attempt, Engine.Lost))
             | "refused" -> incr receipts; feed (Engine.Receipt (key, attempt, Engine.Refused (j |> member "why" |> to_string)))
             | "reconciled" -> (
                 match j |> member "receipt" with
                 | `Null -> feed (Engine.Reconciled (key, attempt, None))
                 (* a reconcile of a keyed intent is a send again, and may be generated afresh too *)
                 | r when generated r -> feed (Engine.Receipt (key, attempt, done_ r))
                 | r -> feed (Engine.Reconciled (key, attempt, Some (Codec.of_value (r |> member "value"), cost_of r))))
             | _ -> ());
            if !crash_receipts > 0 && !receipts > receipts_before && !receipts >= !crash_receipts then begin
              prerr_endline (Printf.sprintf "cordis-run: crashing after %d receipts, before syncing" !receipts);
              exit 3
            end;
            loop ())
  in
  loop ()
