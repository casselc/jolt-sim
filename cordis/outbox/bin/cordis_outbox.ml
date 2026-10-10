(* cordis-outbox: the outbox process (RFC 0006 decision 3B).

     cordis-outbox --socket PATH --journal FILE [--llm URL] [--repo DIR]
                   [--limiter PATH --agent NAME] *)

let () =
  let socket = ref "" and journal = ref "" and llm = ref "" and repo = ref "." and limiter = ref "" and agent = ref "agent" in
  Arg.parse
    [ ("--socket", Arg.Set_string socket, "PATH the Unix socket the engine connects to");
      ("--journal", Arg.Set_string journal, "FILE the SQLite journal, outside the agent's snapshots");
      ("--llm", Arg.Set_string llm, "URL an OpenAI-compatible server (http://host:port)");
      ("--repo", Arg.Set_string repo, "DIR the repository repo.read reads");
      ("--limiter", Arg.Set_string limiter, "PATH the central limiter's socket (none: no shared limit)");
      ("--agent", Arg.Set_string agent, "NAME this agent, in the limiter's emission keys") ]
    (fun _ -> ()) "cordis-outbox --socket PATH --journal FILE [--llm URL] [--repo DIR]";
  if !socket = "" || !journal = "" then (prerr_endline "cordis-outbox: --socket and --journal are required"; exit 2);
  let executors =
    (if !llm <> "" then [ ("llm.complete", Cordis_outbox.Executors.llm_complete ~url:!llm) ] else [])
    @ [ ("repo.read", Cordis_outbox.Executors.repo_read ~root:!repo) ]
  in
  Cordis_outbox.Outbox.serve ?limiter:(if !limiter = "" then None else Some !limiter) ~agent:!agent ~socket:!socket
    ~journal:(Cordis_outbox.Journal.open_ !journal) ~executors ()
