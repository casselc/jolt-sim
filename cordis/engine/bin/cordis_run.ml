(* cordis-run: one graph instance on the engine, as a process (RFC 0006
   revision 2, stage 2): the run store on SQLite, the outbox over a Unix
   socket, receipts that do not come in time taken as lost. Started again
   on the same store after a crash, it recovers from the durable records
   and finishes.

     cordis-run --graph FILE --name GRAPH --store DB --socket PATH --input NAME=JSON
                [--observational KIND]... [--worst KIND=N]... [--budget N]
                [--timeout SECONDS] [--crash-after N]

   On rest it prints each asset's value as JSON and exits 0; blocked (by
   its budget, or a failed computation) it prints why and exits 2.
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

let () =
  let graph = ref "" and name = ref "" and store = ref "" and socket = ref "" and inputs = ref [] in
  let observational = ref [] and worst = ref [] and budget = ref None and timeout = ref 30. and crash_after = ref 0 and crash_receipts = ref 0 in
  let pair s = match String.index_opt s '=' with Some i -> (String.sub s 0 i, String.sub s (i + 1) (String.length s - i - 1)) | None -> failwith ("expected K=V: " ^ s) in
  Arg.parse
    [ ("--graph", Arg.Set_string graph, "FILE"); ("--name", Arg.Set_string name, "GRAPH"); ("--store", Arg.Set_string store, "DB");
      ("--socket", Arg.Set_string socket, "PATH"); ("--input", Arg.String (fun s -> inputs := !inputs @ [ pair s ]), "NAME=JSON");
      ("--observational", Arg.String (fun k -> observational := k :: !observational), "KIND");
      ("--worst", Arg.String (fun s -> let k, n = pair s in worst := (k, int_of_string n) :: !worst), "KIND=N");
      ("--budget", Arg.Int (fun n -> budget := Some n), "N"); ("--timeout", Arg.Set_float timeout, "SECONDS");
      ("--crash-after", Arg.Set_int crash_after, "N");
      ("--crash-after-receipts", Arg.Set_int crash_receipts, "N") ]
    (fun _ -> ()) "cordis-run --graph FILE --name GRAPH --store DB --socket PATH --input NAME=JSON";
  let checked =
    match Cordis_graph.Patch.load (In_channel.with_open_text !graph In_channel.input_all) with
    | Ok s -> s.checked
    | Error es -> prerr_endline (String.concat "\n" es); exit 2
  in
  let classes k = if List.mem k !observational then Engine.Observational else Engine.Effectful in
  let worst k = Option.value (List.assoc_opt k !worst) ~default:0 in
  let st = Store.Sqlite.create !store in
  let durable = Store.Sqlite.load st in
  let t, cmds =
    if durable = [] then (Engine.create ~classes ?budget:!budget ~worst checked !name, [])
    else Engine.recover ~classes ?budget:!budget ~worst checked !name durable
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
  let actions = ref 0 and receipts = ref 0 in
  let sync () =
    let d = Store.Sqlite.sync st in
    feed (Engine.Durable d)
  in
  let awaited () =
    Engine.Smap.fold
      (fun _ (s : Engine.slot) acc -> match s.fiber with Engine.Awaiting { intent; _ } -> intent :: acc | _ -> acc)
      !t.slots []
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
    | [] when Engine.quiet !t ->
        let values = List.map (fun (a : Cordis_graph.Check.asset_info) -> (a.aname, match Engine.value !t a.aname with Some v -> to_json v | None -> `Null)) (List.find (fun (g : Cordis_graph.Check.graph_info) -> g.gname = !name) checked.graphs).assets in
        let reserved, settled = Engine.spend !t in
        print_endline (J.to_string (`Assoc [ ("assets", `Assoc values); ("settled", `Int settled); ("reserved", `Int reserved) ]));
        exit 0
    | [] when Engine.failures !t <> [] ->
        List.iter (fun (a, m) -> prerr_endline (Printf.sprintf "cordis-run: %s failed: %s" a m)) (Engine.failures !t);
        exit 2
    | [] when awaited () = [] && not (Engine.Smap.exists (fun _ (s : Engine.slot) -> match s.fiber with Engine.Uncertain _ -> true | _ -> false) !t.slots) ->
        prerr_endline "cordis-run: blocked (a turn's reservation does not fit the budget)";
        exit 2
    | [] -> (
        (* wait for the outbox; what does not come in time is lost *)
        let ready_line = match next_line () with Some l -> Some l | None -> (match Unix.select [ fd ] [] [] !timeout with [], _, _ -> None | _ -> fill (); next_line ()) in
        match ready_line with
        | None when Buffer.length pending > 0 -> loop ()
        | None ->
            if Sys.getenv_opt "CORDIS_RUN_TRACE" <> None then prerr_endline "cordis-run: timed out waiting";
            List.iter (fun (i : Engine.intent) -> feed (Engine.Receipt (i.key, i.attempt, Engine.Lost))) (awaited ());
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
            (match j |> member "op" |> to_string with
             | "receipt" -> incr receipts; feed (Engine.Receipt (key, attempt, Engine.Done (Codec.of_value (j |> member "value"), j |> member "cost" |> to_int)))
             | "lost" -> feed (Engine.Receipt (key, attempt, Engine.Lost))
             | "reconciled" -> (
                 match j |> member "receipt" with
                 | `Null -> feed (Engine.Reconciled (key, attempt, None))
                 | r -> feed (Engine.Reconciled (key, attempt, Some (Codec.of_value (r |> member "value"), r |> member "cost" |> to_int))))
             | _ -> ());
            if !crash_receipts > 0 && !receipts > receipts_before && !receipts >= !crash_receipts then begin
              prerr_endline (Printf.sprintf "cordis-run: crashing after %d receipts, before syncing" !receipts);
              exit 3
            end;
            loop ())
  in
  loop ()
