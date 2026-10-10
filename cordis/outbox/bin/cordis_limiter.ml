(* cordis-limiter: the central limiter every agent's outbox asks for tokens.

     cordis-limiter --socket PATH --ledger FILE --limit N [--window SECONDS] *)

let () =
  let socket = ref "" and ledger = ref "" and limit = ref 0 and window = ref 0. in
  Arg.parse
    [ ("--socket", Arg.Set_string socket, "PATH"); ("--ledger", Arg.Set_string ledger, "FILE");
      ("--limit", Arg.Set_int limit, "N tokens per window"); ("--window", Arg.Set_float window, "SECONDS (0: one window forever)") ]
    (fun _ -> ()) "cordis-limiter --socket PATH --ledger FILE --limit N [--window SECONDS]";
  if !socket = "" || !ledger = "" then (prerr_endline "cordis-limiter: --socket and --ledger are required"; exit 2);
  Cordis_outbox.Limiter.serve (Cordis_outbox.Limiter.open_ !ledger ~limit:!limit ~window:!window) ~socket:!socket
