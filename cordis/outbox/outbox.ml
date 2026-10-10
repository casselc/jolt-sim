(* The outbox process's loop (docs/cordis/rfc/0006-turn-runtime.md,
   decision 3B; quint/handoff.qnt). One engine connects over a Unix socket
   and speaks JSON lines:

     engine -> outbox   {"op":"intent","intent":I}          journal, ack, run once, answer
                        {"op":"reconcile","key":K,"attempt":N}
     outbox -> engine   {"op":"ack","key":K,"attempt":N}     the intent is journaled
                        {"op":"receipt","key":K,"attempt":N,"value":V,"cost":C}
                        {"op":"lost","key":K,"attempt":N}    the outcome is unknown
                        {"op":"reconciled","key":K,"attempt":N,"receipt":null|{"value":V,"cost":C}}

   An intent is journaled before it runs and acknowledged once journaled;
   a released-again intent is answered from the journal and never runs
   twice (an observational one is journaled per attempt). An intent whose
   run failed, or was cut off by a restart, is answered as lost: the engine
   retries an observational kind and reconciles an effectful one. *)

module E = Cordis_engine.Engine
module Codec = Cordis_engine.Codec
module J = Yojson.Safe

let jkey (i : E.intent) = match i.cls with E.Effectful -> i.key | E.Observational -> Printf.sprintf "%s#%d" i.key i.attempt

let frame op kvs : J.t = `Assoc (("op", `String op) :: kvs)
let ka key attempt = [ ("key", `String key); ("attempt", `Int attempt) ]

let receipt_frame key attempt (value, cost) = frame "receipt" (ka key attempt @ [ ("value", Codec.value value); ("cost", `Int cost) ])

let handle_intent journal executors (i : E.intent) send =
  let k = jkey i in
  match Journal.find journal k with
  | Some { state = Journal.Done; value = Some v; cost; _ } ->
      send (frame "ack" (ka i.key i.attempt));
      send (receipt_frame i.key i.attempt (Codec.of_value (J.from_string v), cost))
  | Some _ ->
      send (frame "ack" (ka i.key i.attempt));
      send (frame "lost" (ka i.key i.attempt))
  | None -> (
      Journal.begin_ journal ~jkey:k ~key:i.key ~attempt:i.attempt ~kind:i.kind ~request:(J.to_string (Codec.value i.request));
      send (frame "ack" (ka i.key i.attempt));
      match List.assoc_opt i.kind executors with
      | None ->
          Journal.set_state journal ~jkey:k Journal.Unknown;
          send (frame "lost" (ka i.key i.attempt))
      | Some (x : Executors.t) -> (
          match x.run i.request with
          | (v, cost) ->
              Journal.finish journal ~jkey:k ~value:(J.to_string (Codec.value v)) ~cost;
              send (receipt_frame i.key i.attempt (v, cost))
          | exception e ->
              prerr_endline ("outbox: " ^ i.kind ^ ": " ^ Printexc.to_string e);
              Journal.set_state journal ~jkey:k Journal.Unknown;
              send (frame "lost" (ka i.key i.attempt))))

let handle_reconcile journal executors key attempt send =
  let reply r = send (frame "reconciled" (ka key attempt @ [ ("receipt", r) ])) in
  let some (v, c) = `Assoc [ ("value", Codec.value v); ("cost", `Int c) ] in
  match Journal.find journal key with
  | Some { state = Journal.Done; value = Some v; cost; _ } -> reply (some (Codec.of_value (J.from_string v), cost))
  | Some { state = Journal.Unknown | Journal.Sent; _ } -> (
      let req = Option.map (fun s -> Codec.of_value (J.from_string s)) (Journal.request journal key) in
      (* the executor of the journaled kind reads the world *)
      let kind =
        Journal.with_stmt journal "SELECT kind FROM journal WHERE jkey = ?" [ `T key ] (fun st ->
            match Sqlite3.step st with Sqlite3.Rc.ROW -> Some (Sqlite3.column_text st 0) | _ -> None)
      in
      match req, Option.bind kind (fun k -> List.assoc_opt k executors) with
      | Some req, Some (x : Executors.t) -> (
          match x.reconcile req with
          | Some (v, c) ->
              Journal.finish journal ~jkey:key ~value:(J.to_string (Codec.value v)) ~cost:c;
              reply (some (v, c))
          | None ->
              Journal.set_state journal ~jkey:key Journal.Fenced;
              reply `Null)
      | _ -> reply `Null)
  | _ -> reply `Null

(* serve one engine connection after another, forever *)
let serve ~socket ~journal ~executors =
  (* an engine that dies mid-answer must not take the outbox with it: a write to its closed socket
     is an error on that connection, not a signal that ends the process *)
  Sys.set_signal Sys.sigpipe Sys.Signal_ignore;
  (try Sys.remove socket with Sys_error _ -> ());
  let fd = Unix.socket Unix.PF_UNIX Unix.SOCK_STREAM 0 in
  Unix.bind fd (Unix.ADDR_UNIX socket);
  Unix.listen fd 4;
  print_endline "ready";
  flush stdout;
  let rec loop () =
    let c, _ = Unix.accept fd in
    let ic = Unix.in_channel_of_descr c and oc = Unix.out_channel_of_descr c in
    (* once journaled, an intent is the outbox's: if the engine has gone, it still runs and its receipt is
       journaled, to answer the engine when it comes back; only the connection is given up *)
    let alive = ref true in
    let send j = if !alive then try output_string oc (J.to_string j ^ "\n"); flush oc with Sys_error _ | Unix.Unix_error _ -> alive := false in
    (try
       while !alive do
         let line = input_line ic in
         let open J.Util in
         let j = J.from_string line in
         match j |> member "op" |> to_string with
         | "intent" -> handle_intent journal executors (Codec.of_intent (j |> member "intent")) send
         | "reconcile" -> handle_reconcile journal executors (j |> member "key" |> to_string) (j |> member "attempt" |> to_int) send
         | op -> prerr_endline ("outbox: unknown op " ^ op)
       done
     with End_of_file | Sys_error _ | Unix.Unix_error _ -> ());
    (try Unix.close c with Unix.Unix_error _ -> ());
    loop ()
  in
  loop ()
