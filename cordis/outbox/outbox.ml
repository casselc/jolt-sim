(* The outbox process's loop (docs/cordis/rfc/0006-turn-runtime.md,
   decision 3B; quint/handoff.qnt). One engine connects over a Unix socket
   and speaks JSON lines:

     engine -> outbox   {"op":"intent","intent":I}          journal, ack, run once, answer
                        {"op":"reconcile","key":K,"attempt":N}
     outbox -> engine   {"op":"ack","key":K,"attempt":N}     the intent is journaled
                        {"op":"receipt","key":K,"attempt":N,"value":V,"cost":C}
                        {"op":"lost","key":K,"attempt":N}    the outcome is unknown
                        {"op":"reconciled","key":K,"attempt":N,"receipt":null|{"value":V,"cost":C}}
                        {"op":"deferred","key":K,"attempt":N}  no token from the limiter yet:
                                                               definitely not sent; it runs once granted

   An intent is journaled before it runs and acknowledged once journaled;
   a released-again intent is answered from the journal and never runs
   twice (an observational one is journaled per attempt). An intent whose
   run failed, or was cut off by a restart, is answered as lost: the engine
   retries an observational kind and reconciles an effectful one.

   With a limiter, a journaled intent runs only once it holds a token for
   its emission (agent and journal key); until then it waits, the engine
   is told it is deferred, and the token is asked for again on every tick.
   Once journaled an intent is the outbox's: if the engine has gone, it
   still runs, and its receipt is journaled for the engine's return. *)

module E = Cordis_engine.Engine
module Codec = Cordis_engine.Codec
module J = Yojson.Safe

(* an effectful intent is journaled by its key; an observational or keyed one by key and attempt, each
   send once (a keyed one's sends share the key the server deduplicates by) *)
let jkey (i : E.intent) = match i.cls with E.Effectful -> i.key | E.Observational | E.Keyed -> Printf.sprintf "%s#%d" i.key i.attempt

let frame op kvs : J.t = `Assoc (("op", `String op) :: kvs)
let ka key attempt = [ ("key", `String key); ("attempt", `Int attempt) ]

let receipt_frame key attempt (value, cost) = frame "receipt" (ka key attempt @ [ ("value", Codec.value value); ("cost", `Int cost) ])

type ctx = {
  journal : Journal.t;
  executors : (string * Executors.t) list;
  limiter : string option;  (** the limiter's socket *)
  agent : string;
}

(* run a journaled, granted intent once, and journal its receipt *)
let run_now ctx ~jkey ~key ~attempt ~kind ~request send =
  Journal.set_state ctx.journal ~jkey Journal.Sent;
  match List.assoc_opt kind ctx.executors with
  | None ->
      Journal.set_state ctx.journal ~jkey Journal.Unknown;
      send (frame "lost" (ka key attempt))
  | Some (x : Executors.t) -> (
      match x.run ~key request with
      | (v, cost) ->
          Journal.finish ctx.journal ~jkey ~value:(J.to_string (Codec.value v)) ~cost;
          send (receipt_frame key attempt (v, cost))
      | exception e ->
          prerr_endline ("outbox: " ^ kind ^ ": " ^ Printexc.to_string e);
          Journal.set_state ctx.journal ~jkey Journal.Unknown;
          send (frame "lost" (ka key attempt)))

let granted ctx jkey =
  match ctx.limiter with None -> true | Some socket -> Limiter.ask ~socket ~emission:(ctx.agent ^ "/" ^ jkey)

let handle_intent ctx (i : E.intent) send =
  let k = jkey i in
  match Journal.find ctx.journal k with
  | Some { state = Journal.Done; value = Some v; cost; _ } ->
      send (frame "ack" (ka i.key i.attempt));
      send (receipt_frame i.key i.attempt (Codec.of_value (J.from_string v), cost))
  | Some { state = Journal.Waiting; _ } ->
      send (frame "ack" (ka i.key i.attempt));
      send (frame "deferred" (ka i.key i.attempt))
  | Some _ ->
      send (frame "ack" (ka i.key i.attempt));
      send (frame "lost" (ka i.key i.attempt))
  | None ->
      Journal.begin_ ~state:Journal.Waiting ctx.journal ~jkey:k ~key:i.key ~attempt:i.attempt ~kind:i.kind
        ~request:(J.to_string (Codec.value i.request));
      send (frame "ack" (ka i.key i.attempt));
      if granted ctx k then run_now ctx ~jkey:k ~key:i.key ~attempt:i.attempt ~kind:i.kind ~request:i.request send
      else send (frame "deferred" (ka i.key i.attempt))

(* every tick: ask again for the waiting rows' tokens, and run what is granted *)
let retry_waiting ctx send =
  List.iter
    (fun (jkey, key, attempt, kind, request) ->
      if granted ctx jkey then run_now ctx ~jkey ~key ~attempt ~kind ~request:(Codec.of_value (J.from_string request)) send)
    (Journal.waiting ctx.journal)

let handle_reconcile ctx key attempt send =
  let journal = ctx.journal and executors = ctx.executors in
  let reply r = send (frame "reconciled" (ka key attempt @ [ ("receipt", r) ])) in
  let some (v, c) = `Assoc [ ("value", Codec.value v); ("cost", `Int c) ] in
  (* the journal row that answers: the key's own (effectful), or for a keyed intent, journaled per send,
     whichever send is done, else the latest; the reconcile itself is asked by the intent's key *)
  let jk = Option.value (Journal.answering journal key) ~default:key in
  match Journal.find journal jk with
  | Some { state = Journal.Done; value = Some v; cost; _ } -> reply (some (Codec.of_value (J.from_string v), cost))
  | Some { state = Journal.Unknown | Journal.Sent; _ } -> (
      let req = Option.map (fun s -> Codec.of_value (J.from_string s)) (Journal.request journal jk) in
      let kind =
        Journal.with_stmt journal "SELECT kind FROM journal WHERE jkey = ?" [ `T jk ] (fun st ->
            match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)
      in
      match req, Option.bind kind (fun k -> List.assoc_opt k executors) with
      | Some req, Some (x : Executors.t) -> (
          match x.reconcile ~key req with
          | Some (v, c) ->
              Journal.finish journal ~jkey:jk ~value:(J.to_string (Codec.value v)) ~cost:c;
              reply (some (v, c))
          | None ->
              Journal.set_state journal ~jkey:jk Journal.Fenced;
              reply `Null
          | exception e ->
              (* the world cannot be asked: no answer, so the engine keeps the intent Uncertain *)
              prerr_endline ("outbox: reconcile " ^ key ^ ": " ^ Printexc.to_string e))
      | _ -> reply `Null)
  | _ -> reply `Null

(* serve one engine at a time (a new connection replaces the old), and tick for waiting tokens *)
let serve ?limiter ?(agent = "agent") ~socket ~journal ~executors () =
  let ctx = { journal; executors; limiter; agent } in
  (* an engine that dies mid-answer must not take the outbox with it *)
  Sys.set_signal Sys.sigpipe Sys.Signal_ignore;
  (try Sys.remove socket with Sys_error _ -> ());
  let lfd = Unix.socket Unix.PF_UNIX Unix.SOCK_STREAM 0 in
  Unix.bind lfd (Unix.ADDR_UNIX socket);
  Unix.listen lfd 4;
  print_endline "ready";
  flush stdout;
  let client = ref None in
  let buf = Buffer.create 4096 and chunk = Bytes.create 4096 in
  let drop () = (match !client with Some (fd, _) -> (try Unix.close fd with Unix.Unix_error _ -> ()) | None -> ()); client := None; Buffer.clear buf in
  (* a send to an engine that has gone gives up the connection, not the intent *)
  let send j =
    match !client with
    | Some (_, oc) -> (try output_string oc (J.to_string j ^ "\n"); flush oc with Sys_error _ | Unix.Unix_error _ -> drop ())
    | None -> ()
  in
  let handle_line line =
    match J.from_string line with
    | j -> (
        let open J.Util in
        match j |> member "op" |> to_string with
        | "intent" -> handle_intent ctx (Codec.of_intent (j |> member "intent")) send
        | "reconcile" -> handle_reconcile ctx (j |> member "key" |> to_string) (j |> member "attempt" |> to_int) send
        | op -> prerr_endline ("outbox: unknown op " ^ op))
    | exception _ -> prerr_endline "outbox: a frame that is not JSON"
  in
  let rec lines () =
    let s = Buffer.contents buf in
    match String.index_opt s '\n' with
    | Some i ->
        Buffer.clear buf;
        Buffer.add_string buf (String.sub s (i + 1) (String.length s - i - 1));
        handle_line (String.sub s 0 i);
        lines ()
    | None -> ()
  in
  while true do
    let fds = lfd :: (match !client with Some (fd, _) -> [ fd ] | None -> []) in
    let readable, _, _ = try Unix.select fds [] [] 0.5 with Unix.Unix_error (Unix.EINTR, _, _) -> ([], [], []) in
    if List.mem lfd readable then begin
      drop ();
      let c, _ = Unix.accept lfd in
      client := Some (c, Unix.out_channel_of_descr c)
    end;
    (match !client with
     | Some (fd, _) when List.mem fd readable -> (
         match Unix.read fd chunk 0 (Bytes.length chunk) with
         | 0 -> drop ()
         | k -> Buffer.add_subbytes buf chunk 0 k; lines ()
         | exception Unix.Unix_error _ -> drop ())
     | _ -> ());
    retry_waiting ctx send
  done
