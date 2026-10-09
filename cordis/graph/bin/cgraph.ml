(* cgraph: the graph language's command line.

     cgraph fmt FILE     print the canonical form
     cgraph hash FILE    each definition's hash
     cgraph check FILE   check it: each cell's turns and their cost, each
                         graph's assets, guards and effect row *)

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
          | "check" -> (
              match Check.check f with
              | Error es ->
                  List.iter (fun e -> Printf.eprintf "%s: %s\n" path e) es;
                  exit 1
              | Ok r ->
                  let module C = Cordis_cel.Checker in
                  List.iter
                    (fun (c : Check.cell_info) ->
                      Printf.printf "cell %s(%s) : %s\n" c.cname
                        (String.concat ", " (List.map (fun (p, t) -> p ^ " : " ^ C.to_string t) c.params))
                        (C.to_string c.result);
                      List.iter
                        (fun (t : Check.turn) ->
                          Printf.printf "  turn %d%s: %s\n" t.index
                            (match t.emits with Some k -> " -> " ^ k | None -> "")
                            (match t.cost with
                             | Some e -> Printf.sprintf "cost [%d, %d]" e.min e.max
                             | None -> "pending (a hole)"))
                        c.turns)
                    r.cells;
                  List.iter
                    (fun (g : Check.graph_info) ->
                      Printf.printf "graph %s ! { %s }\n" g.gname (String.concat ", " g.effect_row);
                      List.iter
                        (fun (a : Check.asset_info) ->
                          Printf.printf "  %s = %s(%s) : %s%s\n" a.aname a.acell (String.concat ", " a.args) (C.to_string a.aty)
                            (String.concat "" (List.map (fun (s, c) -> Printf.sprintf " when %s is %s" s c) a.guards)))
                        g.assets)
                    r.graphs)
          | _ ->
              prerr_endline "usage: cgraph (fmt|hash|check) FILE";
              exit 2))
  | _ ->
      prerr_endline "usage: cgraph (fmt|hash|check) FILE";
      exit 2
