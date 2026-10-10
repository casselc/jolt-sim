(* cgraph: the graph language's command line.

     cgraph fmt FILE              print the canonical form
     cgraph hash FILE             each definition's hash
     cgraph check FILE            check it: each cell's turns and their cost,
                                  each graph's assets, guards and effect row
     cgraph run FILE              compile each graph to kernel components and
                                  run them to quiet; report what went wrong
     cgraph diff OLD NEW          what changed, and what needs approval
     cgraph admit LIVE PATCH [--approved]
                                  admit a patch against a live file; prints
                                  the new revision when it goes live

   --store DIR (before the command) is the content store: what `uses`
   names by hash (library texts, descriptor sets) is looked up among DIR's
   files by the SHA-256 of their bytes. *)

open Cordis_graph
module C = Cordis_cel.Checker

let read path = In_channel.with_open_text path In_channel.input_all

let die fmt = Printf.ksprintf (fun m -> prerr_endline m; exit 1) fmt

(* --store DIR, taken off the front of the arguments *)
let store, argv =
  match Array.to_list Sys.argv with
  | prog :: "--store" :: dir :: rest ->
      let files =
        Array.to_list (Sys.readdir dir)
        |> List.map (Filename.concat dir)
        |> List.filter (fun f -> not (Sys.is_directory f))
        |> List.map (fun f ->
               let s = In_channel.with_open_bin f In_channel.input_all in
               ("sha256:" ^ Cordis_cel.Sha256.digest s, s))
      in
      ((fun h -> List.assoc_opt h files), prog :: rest)
  | args -> ((fun _ -> None), args)

let options = { Check.default_options with store }

let load path =
  match Patch.load ~options (read path) with Ok s -> s | Error es -> die "%s" (String.concat "\n" (List.map (fun e -> path ^ ": " ^ e) es))

let show_checked (r : Check.checked) =
  List.iter
    (fun (c : Check.cell_info) ->
      Printf.printf "cell %s(%s) : %s\n" c.cname
        (String.concat ", " (List.map (fun (p, t) -> p ^ " : " ^ C.to_string t) c.params))
        (C.to_string c.result);
      List.iter
        (fun (t : Check.turn) ->
          Printf.printf "  turn %d%s: %s\n" t.index
            (match t.emits with Some k -> " -> " ^ k | None -> "")
            (match t.cost with Some e -> Printf.sprintf "cost [%d, %d]" e.min e.max | None -> "pending (a hole)"))
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
    r.graphs

let show_change = function
  | Compat.Added n -> Printf.printf "added       %s\n" n
  | Compat.Removed n -> Printf.printf "removed     %s\n" n
  | Compat.Unchanged _ -> ()
  | Compat.Compatible n -> Printf.printf "compatible  %s\n" n
  | Compat.Needs_approval (n, rs) -> Printf.printf "APPROVAL    %s: %s\n" n (String.concat "; " rs)

let usage () = die "usage: cgraph [--store DIR] (fmt|hash|check|run) FILE | cgraph diff OLD NEW | cgraph admit LIVE PATCH [--approved]"

let () =
  match argv with
  | [ _; "fmt"; path ] -> print_string (Print.file (match Parse.file (read path) with Ok f -> f | Error m -> die "%s:%s" path m))
  | [ _; "hash"; path ] -> (
      match Parse.file (read path) with
      | Ok f -> List.iter (fun (n, h) -> Printf.printf "%s %s\n" h n) (Print.hashes f)
      | Error m -> die "%s:%s" path m)
  | [ _; "check"; path ] -> show_checked (load path).checked
  | [ _; "run"; path ] ->
      let s = load path in
      let bad = ref false in
      List.iter
        (fun (g : Check.graph_info) ->
          let c = Compile.compile s.checked.cells g in
          let r = Compile.simulate ~pick:(fun _ -> 0) c in
          match Compile.verify s.checked.cells g c r with
          | [] -> Printf.printf "graph %s: %d components, quiet after %d steps\n" g.gname (List.length c.components) r.steps
          | ps -> bad := true; List.iter (Printf.printf "graph %s: %s\n" g.gname) ps)
        s.checked.graphs;
      if !bad then exit 1
  | [ _; "diff"; old_path; new_path ] ->
      let o = load old_path and n = load new_path in
      List.iter show_change (Compat.diff ~old_file:o.file ~old_checked:o.checked ~new_file:n.file ~new_checked:n.checked)
  | _ :: "admit" :: live_path :: patch_path :: rest -> (
      let approved = rest = [ "--approved" ] in
      if rest <> [] && not approved then usage ();
      let live = load live_path in
      match Patch.parse (read patch_path) with
      | Error m -> die "%s: %s" patch_path m
      | Ok p -> (
          match Patch.admit ~options ~approved live p with
          | Patch.Live (s, changes, plan) ->
              List.iter show_change changes;
              List.iter
                (function
                  | c, Compat.Carry -> Printf.printf "state       %s: carried\n" c
                  | c, Compat.Migrate rs -> Printf.printf "state       %s: migrated (reset): %s\n" c (String.concat "; " rs))
                plan;
              Printf.printf "live: %s\n" (Patch.revision s.file);
              print_string (Print.file s.file)
          | Patch.Awaiting_approval (_, needs) ->
              List.iter (fun (n, rs) -> Printf.printf "APPROVAL    %s: %s\n" n (String.concat "; " rs)) needs;
              exit 3
          | Patch.Refused es -> die "refused:\n  %s" (String.concat "\n  " es)))
  | _ -> usage ()
