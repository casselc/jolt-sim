(* Structural patches and admission (docs/cordis/rfc/0002-graph-language.md,
   "Storage and edits"; the model is cordis/quint/admission.qnt).

   A patch names each definition it replaces or removes together with the
   hash it expects that definition to have. If any expectation is not the
   live definition's hash, the whole patch is refused: a stale patch is
   never merged, so two agents editing the same definition cannot lose each
   other's updates. A patch that applies is checked as a whole revision;
   one that does not check is refused; one that checks and needs approval
   (Compat) waits for it; anything else goes live.

     replace critic@sha256:<hex>
     cell critic(diff : Diff) : Verdict ... = { ... }

     add
     type Note = { text : string };

     remove gate@sha256:<hex> *)

open Syntax

type op =
  | Replace of { name : string; expect : string; def : item }
  | Add of item list
  | Remove of { name : string; expect : string }

type patch = op list

type store = { file : file; checked : Check.checked }

type outcome =
  | Live of store * Compat.change list * (string * Compat.state_move) list
      (** the new revision, what changed, and what happens to each cell's state *)
  | Awaiting_approval of store * (string * string list) list  (** the candidate, and what needs approval *)
  | Refused of string list

(* The revision's identity: every definition's hash, by name. *)
let revision (f : file) =
  "sha256:" ^ Cordis_cel.Sha256.digest (String.concat "\n" (List.map (fun (n, h) -> n ^ "=" ^ h) (List.sort compare (Print.hashes f))))

(* ---- the patch text ------------------------------------------------------------------------------ *)

let parse text =
  let lines = String.split_on_char '\n' text in
  let starts l p = String.length l >= String.length p && String.sub l 0 (String.length p) = p in
  let header l = starts l "replace " || starts l "remove " || String.trim l = "add" in
  (* chunks: a header line and the lines up to the next header *)
  let rec chunks acc cur = function
    | [] -> List.rev (match cur with Some c -> (fst c, List.rev (snd c)) :: acc | None -> acc)
    | l :: rest when header l ->
        let acc = match cur with Some c -> (fst c, List.rev (snd c)) :: acc | None -> acc in
        chunks acc (Some (l, [])) rest
    | l :: rest -> (
        match cur with
        | Some (h, body) -> chunks acc (Some (h, l :: body)) rest
        | None -> if String.trim l = "" || starts (String.trim l) "//" then chunks acc None rest else raise (Failure ("text before the first operation: " ^ l)))
  in
  let target h =
    let rest = String.trim (String.sub h (String.index h ' ') (String.length h - String.index h ' ')) in
    match String.index_opt rest '@' with
    | Some i -> (String.sub rest 0 i, String.sub rest (i + 1) (String.length rest - i - 1))
    | None -> raise (Failure ("expected NAME@sha256:... in: " ^ h))
  in
  match
    List.map
      (fun (h, body) ->
        let defs () = match Parse.file (String.concat "\n" body) with Ok f -> f | Error m -> raise (Failure m) in
        if starts h "replace " then begin
          let name, expect = target h in
          match defs () with
          | [ def ] when item_name def = name -> Replace { name; expect; def }
          | [ def ] -> raise (Failure (Printf.sprintf "replace %s: the definition is named %s" name (item_name def)))
          | _ -> raise (Failure (Printf.sprintf "replace %s: give exactly one definition" name))
        end
        else if starts h "remove " then begin
          let name, expect = target h in
          if String.trim (String.concat "" body) <> "" then raise (Failure ("remove " ^ name ^ ": nothing follows a remove"));
          Remove { name; expect }
        end
        else Add (defs ()))
      (chunks [] None lines)
  with
  | ops -> Ok ops
  | exception Failure m -> Error m

(* ---- applying and admitting ---------------------------------------------------------------------- *)

let apply (f : file) (p : patch) =
  let hashes = Print.hashes f in
  let stale name expect =
    match List.assoc_opt name hashes with
    | None -> Some (Printf.sprintf "%s does not exist" name)
    | Some h when h <> expect -> Some (Printf.sprintf "stale: %s is at %s, the patch expected %s" name h expect)
    | Some _ -> None
  in
  let problems =
    List.filter_map
      (function
        | Replace { name; expect; _ } | Remove { name; expect } -> stale name expect
        | Add defs ->
            List.find_map (fun d -> if List.mem_assoc (item_name d) hashes then Some (item_name d ^ " already exists") else None) defs)
      p
  in
  if problems <> [] then Error problems
  else
    Ok
      (List.fold_left
         (fun f -> function
           | Replace { name; def; _ } -> List.map (fun i -> if item_name i = name then def else i) f
           | Remove { name; _ } -> List.filter (fun i -> item_name i <> name) f
           | Add defs -> f @ defs)
         f p)

(* Admit a patch against the live store. [approved] stands for the human
   (or policy) approval of what Compat flags. *)
let admit ?(options = Check.default_options) ?(approved = false) (live : store) (p : patch) =
  match apply live.file p with
  | Error problems -> Refused problems
  | Ok file -> (
      match Check.check ~options file with
      | Error es -> Refused es
      | Ok checked -> (
          let candidate = { file; checked } in
          let changes = Compat.diff ~old_file:live.file ~old_checked:live.checked ~new_file:file ~new_checked:checked in
          let plan = Compat.state_plan ~old_file:live.file ~old_checked:live.checked ~new_file:file ~new_checked:checked in
          match Compat.needs_approval changes with
          | [] -> Live (candidate, changes, plan)
          | needs -> if approved then Live (candidate, changes, plan) else Awaiting_approval (candidate, needs)))

let load ?(options = Check.default_options) text =
  match Parse.file text with
  | Error m -> Error [ m ]
  | Ok file -> Result.map (fun checked -> { file; checked }) (Check.check ~options file)
