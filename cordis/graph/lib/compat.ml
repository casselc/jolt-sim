(* Replacement compatibility (docs/cordis/rfc/0002-graph-language.md,
   "Subtyping only at boundaries"): what changed between two checked
   revisions, and which changes need approval.

   A cell may be replaced without approval when its new signature accepts
   at least the old input (each old parameter type fits the new one),
   produces at least the old output (every new result fits where an old one
   did), and its effect row is no larger. A change that narrows an input,
   widens the effects, changes a variant's cases or an effect's types, or
   replaces a library function's signature needs approval: it is the
   graph-level form of Cedar Analysis's "More Permissive". *)

open Syntax
module C = Cordis_cel.Checker

type change =
  | Added of string
  | Removed of string
  | Unchanged of string
  | Compatible of string
  | Needs_approval of string * string list  (** the definition, and why *)

let fits want got = C.assignable (C.empty ()) want got

let cell_reasons (o : Check.cell_info) (n : Check.cell_info) =
  let r = ref [] in
  let say fmt = Printf.ksprintf (fun m -> r := m :: !r) fmt in
  if List.length o.params <> List.length n.params then
    say "takes %d inputs instead of %d" (List.length n.params) (List.length o.params)
  else
    List.iter2
      (fun (p, ot) (_, nt) ->
        if not (fits nt ot) then say "narrows input %s: %s no longer fits %s" p (C.to_string ot) (C.to_string nt))
      o.params n.params;
  if not (fits o.result n.result) then
    say "changes its output: %s does not fit where %s did" (C.to_string n.result) (C.to_string o.result);
  (match List.filter (fun k -> not (List.mem k o.effects)) n.effects with
   | [] -> ()
   | added -> say "widens its effects: + %s" (String.concat ", " added));
  List.iter
    (fun (f, ot) ->
      match List.assoc_opt f n.state with
      | None -> say "drops state field %s" f
      | Some nt -> if not (fits nt ot && fits ot nt) then say "changes state field %s's type" f)
    o.state;
  List.rev !r

let type_reasons (o : C.t option) (n : C.t option) =
  match o, n with
  | Some (C.Variant ocs), Some (C.Variant ncs) when ocs <> ncs -> [ "changes a variant's cases" ]
  | Some o, Some n when not (fits o n && fits n o) -> [ Printf.sprintf "changes the type from %s to %s" (C.to_string o) (C.to_string n) ]
  | _ -> []

(* Compare two checked revisions, definition by definition. *)
let diff ~(old_file : file) ~(old_checked : Check.checked) ~(new_file : file) ~(new_checked : Check.checked) =
  let oh = Print.hashes old_file and nh = Print.hashes new_file in
  let item file n = List.find_opt (fun i -> item_name i = n) file in
  let changed n =
    match item old_file n, item new_file n with
    | Some (Cell _), Some (Cell _) -> (
        let find (c : Check.checked) = List.find_opt (fun (x : Check.cell_info) -> x.cname = n) c.cells in
        match find old_checked, find new_checked with
        | Some o, Some ne -> cell_reasons o ne
        | _ -> [])
    | Some (Type_def _), Some (Type_def _) -> type_reasons (List.assoc_opt n old_checked.types) (List.assoc_opt n new_checked.types)
    | Some (Effect (_, oa, ob)), Some (Effect (_, na, nb)) ->
        if Print.ty oa <> Print.ty na || Print.ty ob <> Print.ty nb then [ "changes the effect's request or receipt type" ] else []
    | Some (Uses _), Some (Uses _) -> [ "imports a different library" ]
    | Some (Graph _), Some (Graph _) -> (
        let find (c : Check.checked) = List.find_opt (fun (g : Check.graph_info) -> g.gname = n) c.graphs in
        match find old_checked, find new_checked with
        | Some o, Some ne -> (
            match List.filter (fun k -> not (List.mem k o.effect_row)) ne.effect_row with
            | [] -> []
            | added -> [ "widens the graph's effect row: + " ^ String.concat ", " added ])
        | _ -> [])
    | _ -> [ "changes what kind of definition it is" ]
  in
  let names = List.sort_uniq compare (List.map fst oh @ List.map fst nh) in
  List.map
    (fun n ->
      match List.assoc_opt n oh, List.assoc_opt n nh with
      | None, Some _ -> Added n
      | Some _, None -> Removed n
      | Some a, Some b when a = b -> Unchanged n
      | _ -> (match changed n with [] -> Compatible n | reasons -> Needs_approval (n, reasons)))
    names

let needs_approval changes = List.filter_map (function Needs_approval (n, rs) -> Some (n, rs) | _ -> None) changes
