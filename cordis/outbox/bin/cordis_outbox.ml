(* cordis-outbox: the outbox process (RFC 0006 decision 3B).

     cordis-outbox --socket PATH --journal FILE [--llm URL | --ops URL] [--repo DIR]
                   [--limiter PATH --agent NAME] [--api DOC [--api-base URL]]

   --api imports an OpenAPI document (RFC 0003 revision 4) and runs its
   operations, sending to --api-base (default: the document's first
   server). A scheme's credential comes from the environment variable
   CORDIS_CREDENTIAL_<SCHEME> (the scheme's name upper-cased, other
   characters as _), never from the graph.

   --ops runs llm.complete through halo's operation API (RFC 0007) instead of
   a plain completion: a journaled submit resent as first sent, status polled,
   the result fetched, journaled, and only then acknowledged. --principal
   names the owner of the attempts (default: --agent), --ops-mode the
   inference mode the intent needs (relaxed or exact). *)

let () =
  let socket = ref "" and journal = ref "" and llm = ref "" and repo = ref "." and limiter = ref "" and agent = ref "agent" in
  let api = ref "" and api_base = ref "" and llm_idle = ref 120. in
  let ops = ref "" and principal = ref "" and ops_mode = ref "relaxed" and ops_window = ref 120. and ops_poll = ref 0.1 in
  Arg.parse
    [ ("--socket", Arg.Set_string socket, "PATH the Unix socket the engine connects to");
      ("--journal", Arg.Set_string journal, "FILE the SQLite journal, outside the agent's snapshots");
      ("--llm", Arg.Set_string llm, "URL an OpenAI-compatible server (http://host:port)");
      ("--llm-idle", Arg.Set_float llm_idle, "SECONDS how long a streamed completion may be silent before it is taken as lost (default 120)");
      ("--ops", Arg.Set_string ops, "URL halo-flash's operation API: llm.complete runs as durable operations (RFC 0007)");
      ("--principal", Arg.Set_string principal, "NAME the owner of the operation attempts (default: --agent)");
      ("--ops-mode", Arg.Set_string ops_mode, "MODE relaxed or exact: the inference the intent needs (default relaxed)");
      ("--ops-window", Arg.Set_float ops_window, "SECONDS by halo's clock a submit may still be accepted (default 120)");
      ("--ops-poll", Arg.Set_float ops_poll, "SECONDS between status polls of an operation (default 0.1)");
      ("--repo", Arg.Set_string repo, "DIR the repository repo.read reads");
      ("--limiter", Arg.Set_string limiter, "PATH the central limiter's socket (none: no shared limit)");
      ("--agent", Arg.Set_string agent, "NAME this agent, in the limiter's emission keys");
      ("--api", Arg.Set_string api, "DOC an OpenAPI document whose operations to run");
      ("--api-base", Arg.Set_string api_base, "URL where to send them (default: the document's first server)") ]
    (fun _ -> ()) "cordis-outbox --socket PATH --journal FILE [--llm URL] [--repo DIR]";
  if !socket = "" || !journal = "" then (prerr_endline "cordis-outbox: --socket and --journal are required"; exit 2);
  let executors =
    (if !ops <> "" then
       let cfg = { (Cordis_outbox.Halo_op.default_cfg ~url:!ops ~principal:(if !principal = "" then !agent else !principal)) with mode = !ops_mode; poll_s = !ops_poll; window_ms = int_of_float (!ops_window *. 1000.) } in
       [ ("llm.complete", Cordis_outbox.Halo_op.llm_complete ~store:!journal cfg) ]
     else if !llm <> "" then [ ("llm.complete", Cordis_outbox.Executors.llm_complete ~idle:!llm_idle ~url:!llm ()) ]
     else [])
    @ [ ("repo.read", Cordis_outbox.Executors.repo_read ~root:!repo) ]
    @
    if !api = "" then []
    else
      let module O = Cordis_import.Openapi in
      let doc = match O.document (In_channel.with_open_bin !api In_channel.input_all) with Ok d -> d | Error m -> prerr_endline ("cordis-outbox: " ^ m); exit 2 in
      let t = match O.import doc with t -> t | exception O.Refused m -> prerr_endline ("cordis-outbox: " ^ !api ^ ": " ^ m); exit 2 in
      let base = if !api_base <> "" then !api_base else match t.servers with s :: _ -> s | [] -> prerr_endline "cordis-outbox: no server: give --api-base"; exit 2 in
      let base = if String.length base > 0 && base.[String.length base - 1] = '/' then String.sub base 0 (String.length base - 1) else base in
      let var s = "CORDIS_CREDENTIAL_" ^ String.uppercase_ascii (String.map (fun c -> match c with 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' -> c | _ -> '_') s) in
      Cordis_outbox.Executors.openapi ~api:t ~base ~credential:(fun s -> Sys.getenv_opt (var s))
  in
  Cordis_outbox.Outbox.serve ?limiter:(if !limiter = "" then None else Some !limiter) ~agent:!agent ~socket:!socket
    ~journal:(Cordis_outbox.Journal.open_ !journal) ~executors ()
