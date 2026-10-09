type restart = Permanent | Transient | Temporary
type reason = Normal | Shutdown | Crash of string
type strategy = One_for_one | One_for_all | Rest_for_one

let restart_ok restart reason =
  match restart, reason with
  | Temporary, _ -> false
  | Transient, (Normal | Shutdown) -> false
  | Transient, Crash _ -> true
  | Permanent, _ -> true

let index_of ids id =
  let rec go i = function
    | [] -> None
    | x :: rest -> if x = id then Some i else go (i + 1) rest
  in
  go 0 ids

let rec drop n = function
  | l when n <= 0 -> l
  | [] -> []
  | _ :: rest -> drop (n - 1) rest

let affected strategy children id =
  match index_of (List.map fst children) id with
  | None -> []
  | Some i -> (
      match strategy with
      | One_for_all -> children
      | Rest_for_one -> drop i children
      | One_for_one -> [ List.nth children i ])

let restart_plan strategy children id =
  let hit = affected strategy children id in
  let stop = List.rev (List.filter_map (fun (c, _) -> if c <> id then Some c else None) hit) in
  let start =
    List.filter_map
      (fun (c, r) -> if c = id || r <> Temporary then Some c else None)
      hit
  in
  (stop, start)

let stop_order ids = List.rev ids

type verdict = Allow of int list | Exceed of int list

let intensity ~times ~now ~period ~max_r =
  let recent = List.filter (fun t -> now - t <= period) times @ [ now ] in
  if List.length recent > max_r then Exceed recent else Allow recent
