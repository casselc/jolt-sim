(* cgraph: the graph language's command line.

     cgraph fmt FILE     print the canonical form
     cgraph hash FILE    each definition's hash *)

open Cordis_graph

let read path = In_channel.with_open_text path In_channel.input_all

let () =
  match Array.to_list Sys.argv with
  | [ _; cmd; path ] -> (
      match Parse.file (read path) with
      | Error m ->
          Printf.eprintf "%s:%s\n" path m;
          exit 1
      | Ok f -> (
          match cmd with
          | "fmt" -> print_string (Print.file f)
          | "hash" -> List.iter (fun (n, h) -> Printf.printf "%s %s\n" h n) (Print.hashes f)
          | _ ->
              prerr_endline "usage: cgraph (fmt|hash) FILE";
              exit 2))
  | _ ->
      prerr_endline "usage: cgraph (fmt|hash) FILE";
      exit 2
