(* The outbox process's loop (docs/cordis/rfc/0006-turn-runtime.md,
   decision 3B; quint/handoff.qnt). One engine connects over a Unix socket
   and speaks JSON lines:

     engine -> outbox   {"op":"intent","intent":I}          journal, ack, run once, answer
                        {"op":"reconcile","key":K,"attempt":N}
     outbox -> engine   {"op":"ack","key":K,"attempt":N}     the intent is journaled
                        {"op":"receipt","key":K,"attempt":N,"value":V,"cost":C}
                        {"op":"lost","key":K,"attempt":N}    the outcome is unknown
                        {"op":"reconciled","key":K,"attempt":N,"receipt":null|{"value":V,"cost":C}}
                        {"op":"deferred","key":K,"attempt":N}  no token from the limiter yet, or the
                                                               world asked to come back later (HTTP 429,
                                                               503): definitely not run; it runs later
                        {"op":"refused","key":K,"attempt":N,"why":W}  a definite failure: it did not run

   An intent is journaled before it runs and acknowledged once journaled,
   and its call runs off the loop, so the loop keeps reading frames while
   calls take minutes (quint/intake.qnt): an acknowledged intent is the
   outbox's to answer, and the engine waits on it rather than time it out;
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
module V = Cordis_cel.Value

(* an effectful intent is journaled by its key; an observational or keyed one by key and attempt, each
   send once (a keyed one's sends share the key the server deduplicates by) *)
let jkey (i : E.intent) = match i.cls with E.Effectful -> i.key | E.Observational | E.Keyed -> Printf.sprintf "%s#%d" i.key i.attempt

let frame op kvs : J.t = `Assoc (("op", `String op) :: kvs)
let ka key attempt = [ ("key", `String key); ("attempt", `Int attempt) ]

(* a cost the world did not report (negative) is sent as null: the engine charges the worst case *)
let receipt_frame key attempt (value, cost) =
  frame "receipt" (ka key attempt @ [ ("value", Codec.value value); ("cost", if cost < 0 then `Null else `Int cost) ])

type ctx = {
  journal : Journal.t;
  executors : (string * Executors.t) list;
  limiter : string option;  (** the limiter's socket *)
  agent : string;
  inflight : (string, unit) Hashtbl.t;  (** journal keys whose call is running *)
  finished : (string * string * int * string * job * outcome) Queue.t;  (** calls ended, for the loop to journal *)
  lock : Mutex.t;
  wake : Unix.file_descr;  (** written when a call ends, to wake the loop *)
  backoff : (string, float * float) Hashtbl.t;  (** journal key -> (not before, the last delay): asked to come back later *)
  keyed : (string, unit) Hashtbl.t;  (** intent keys released as keyed: an answer to one send supersedes the others *)
}

and job = Run | Reconcile

(* how a call ended *)
and outcome = Ran of V.t * int | Not_applied | No_answer of string | Refused_by of string | Busy_by of string

(* Start a call off the loop (intake.qnt's design: the loop keeps reading its intake while calls run).
   The thread runs the executor and queues how it ended; only the loop touches the journal. *)
let start ctx ~jkey ~key ~attempt ~kind ~job (run : unit -> (V.t * int) option) =
  Hashtbl.replace ctx.inflight jkey ();
  let body () =
    let outcome =
      match run () with
      | Some (v, c) -> Ran (v, c)
      | None -> Not_applied
      | exception Executors.Refused m -> Refused_by m
      | exception Executors.Busy m -> Busy_by m
      | exception e -> No_answer (Printexc.to_string e)
    in
    Mutex.protect ctx.lock (fun () -> Queue.push (jkey, key, attempt, kind, job, outcome) ctx.finished);
    try ignore (Unix.write_substring ctx.wake "x" 0 1) with Unix.Unix_error _ -> ()
  in
  ignore (Thread.create body ())

(* run a journaled, granted intent once, off the loop *)
let run_now ctx ~jkey ~key ~attempt ~kind ~request =
  Journal.set_state ctx.journal ~jkey Journal.Sent;
  match List.assoc_opt kind ctx.executors with
  | None -> start ctx ~jkey ~key ~attempt ~kind ~job:Run (fun () -> failwith ("no executor for " ^ kind))
  | Some (x : Executors.t) ->
      let ikey = Executors.idempotency_key ~agent:ctx.agent ~key ~request in
      start ctx ~jkey ~key ~attempt ~kind ~job:Run (fun () -> Some (x.run ~key:ikey request))

(* the loop journals each call that ended and answers the engine *)
let drain ctx send =
  let ended = Mutex.protect ctx.lock (fun () -> let l = List.of_seq (Queue.to_seq ctx.finished) in Queue.clear ctx.finished; l) in
  List.iter
    (fun (jkey, key, attempt, kind, job, outcome) ->
      Hashtbl.remove ctx.inflight jkey;
      let some (v, c) = `Assoc [ ("value", Codec.value v); ("cost", if c < 0 then `Null else `Int c) ] in
      match job, outcome with
      | Run, Ran (v, cost) ->
          Journal.finish ctx.journal ~jkey ~value:(J.to_string (Codec.value v)) ~cost;
          (* a keyed intent's earlier sends, lost, are answered by this one: one key, one generation *)
          if Hashtbl.mem ctx.keyed key then Journal.supersede ctx.journal ~key ~answered:jkey;
          send (receipt_frame key attempt (v, cost))
      | Run, (No_answer _ | Not_applied) ->
          prerr_endline ("outbox: " ^ kind ^ ": " ^ (match outcome with No_answer m -> m | _ -> "no answer"));
          Journal.set_state ctx.journal ~jkey Journal.Unknown;
          send (frame "lost" (ka key attempt))
      | Run, Refused_by why ->
          Journal.refuse ctx.journal ~jkey ~why;
          send (frame "refused" (ka key attempt @ [ ("why", `String why) ]))
      | Run, Busy_by why ->
          (* not run: wait, and ask again later, each time later (up to a minute) *)
          let delay = match Hashtbl.find_opt ctx.backoff jkey with Some (_, d) -> Float.min 60. (d *. 2.) | None -> 1. in
          Hashtbl.replace ctx.backoff jkey (Unix.gettimeofday () +. delay, delay);
          prerr_endline (Printf.sprintf "outbox: %s: %s; again in %.0fs" kind why delay);
          Journal.set_state ctx.journal ~jkey Journal.Waiting;
          send (frame "deferred" (ka key attempt))
      | Reconcile, Ran (v, c) ->
          Journal.finish ctx.journal ~jkey ~value:(J.to_string (Codec.value v)) ~cost:c;
          send (frame "reconciled" (ka key attempt @ [ ("receipt", some (v, c)) ]))
      | Reconcile, Not_applied ->
          Journal.set_state ctx.journal ~jkey Journal.Fenced;
          send (frame "reconciled" (ka key attempt @ [ ("receipt", `Null) ]))
      | Reconcile, (No_answer m | Refused_by m | Busy_by m) ->
          (* the world cannot be asked now: no answer, so the engine keeps the intent Uncertain and asks again *)
          prerr_endline ("outbox: reconcile " ^ key ^ ": " ^ m))
    ended

let granted ctx jkey =
  match ctx.limiter with None -> true | Some socket -> Limiter.ask ~socket ~emission:(ctx.agent ^ "/" ^ jkey)

let handle_intent ctx (i : E.intent) send =
  let k = jkey i in
  if i.cls = E.Keyed then Hashtbl.replace ctx.keyed i.key ();
  let ack () = send (frame "ack" (ka i.key i.attempt)) in
  match Journal.find ctx.journal k with
  | Some { state = Journal.Done; value = Some v; cost; _ } ->
      ack ();
      send (receipt_frame i.key i.attempt (Codec.of_value (J.from_string v), cost))
  | Some { state = Journal.Waiting; _ } -> ack (); send (frame "deferred" (ka i.key i.attempt))
  | Some { state = Journal.Refused; value; _ } ->
      ack ();
      send (frame "refused" (ka i.key i.attempt @ [ ("why", `String (Option.value value ~default:"refused")) ]))
  (* running now: its answer comes when its call ends *)
  | Some { state = Journal.Sent; _ } when Hashtbl.mem ctx.inflight k -> ack ()
  | Some _ -> ack (); send (frame "lost" (ka i.key i.attempt))
  | None ->
      Journal.begin_ ~state:Journal.Waiting ctx.journal ~jkey:k ~key:i.key ~attempt:i.attempt ~kind:i.kind
        ~request:(J.to_string (Codec.value i.request));
      ack ();
      if granted ctx k then run_now ctx ~jkey:k ~key:i.key ~attempt:i.attempt ~kind:i.kind ~request:i.request
      else send (frame "deferred" (ka i.key i.attempt))

(* every tick: the waiting rows whose time has come ask again for their tokens, and run if granted *)
let retry_waiting ctx =
  let now = Unix.gettimeofday () in
  List.iter
    (fun (jkey, key, attempt, kind, request) ->
      let due = match Hashtbl.find_opt ctx.backoff jkey with Some (t, _) -> now >= t | None -> true in
      if due && (not (Hashtbl.mem ctx.inflight jkey)) && granted ctx jkey then
        run_now ctx ~jkey ~key ~attempt ~kind ~request:(Codec.of_value (J.from_string request)))
    (Journal.waiting ctx.journal)

let handle_reconcile ctx key attempt send =
  let journal = ctx.journal and executors = ctx.executors in
  let reply r = send (frame "reconciled" (ka key attempt @ [ ("receipt", r) ])) in
  let some (v, c) = `Assoc [ ("value", Codec.value v); ("cost", if c < 0 then `Null else `Int c) ] in
  (* the journal row that answers: the key's own (effectful), or for a keyed intent, journaled per send,
     whichever send is done, else the latest; the reconcile itself is asked by the intent's key *)
  let jk = Option.value (Journal.answering journal key) ~default:key in
  match Journal.find journal jk with
  | Some { state = Journal.Done; value = Some v; cost; _ } -> reply (some (Codec.of_value (J.from_string v), cost))
  | Some { state = Journal.Unknown | Journal.Sent; _ } when not (Hashtbl.mem ctx.inflight jk) -> (
      let req = Option.map (fun s -> Codec.of_value (J.from_string s)) (Journal.request journal jk) in
      let kind =
        Journal.with_stmt journal "SELECT kind FROM journal WHERE jkey = ?" [ `T jk ] (fun st ->
            match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)
      in
      match req, Option.bind kind (fun k -> List.assoc_opt k executors) with
      | Some req, Some (x : Executors.t) ->
          let ikey = Executors.idempotency_key ~agent:ctx.agent ~key ~request:req in
          start ctx ~jkey:jk ~key ~attempt ~kind:(Option.get kind) ~job:Reconcile (fun () -> x.reconcile ~key:ikey req)
      | _ -> reply `Null)
  (* a call of it is running: its end answers *)
  | Some { state = Journal.Unknown | Journal.Sent; _ } -> ()
  | _ -> reply `Null

(* serve one engine at a time (a new connection replaces the old), and tick for waiting tokens *)
let serve ?limiter ?(agent = "agent") ~socket ~journal ~executors () =
  let wake_r, wake_w = Unix.pipe ~cloexec:true () in
  let ctx =
    { journal; executors; limiter; agent; inflight = Hashtbl.create 8; finished = Queue.create (); lock = Mutex.create (); wake = wake_w;
      backoff = Hashtbl.create 8; keyed = Hashtbl.create 8 }
  in
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
    let fds = lfd :: wake_r :: (match !client with Some (fd, _) -> [ fd ] | None -> []) in
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
    if List.mem wake_r readable then (try ignore (Unix.read wake_r chunk 0 (Bytes.length chunk)) with Unix.Unix_error _ -> ());
    drain ctx send;
    retry_waiting ctx
  done
