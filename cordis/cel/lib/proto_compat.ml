(* Compatibility of descriptor changes (docs/cordis/rfc/0004-descriptor-
   native-types.md, "Revisions"): what changed, between two registries, in
   the messages and enums reachable from given types, and which changes
   break.

   A change is compatible when the new descriptor reads every message the
   old one wrote, under the boundary codec's strict reading, in binary and
   JSON, to the same value: a field added (not required), an enum value
   added. Everything else that changes a type breaks: a field removed,
   renumbered, renamed (its JSON form changes), retyped, made repeated or
   singular, given or deprived of presence, moved into or out of a oneof,
   made required, or given a new default; a group made a message or back;
   an enum value removed, renamed or renumbered; the syntax changed; a type
   removed. The rule is conservative: some breaking changes (int32 to
   int64) still read every old message, but none that it calls compatible
   fails to. *)

module P = Proto

type finding = { tname : string; breaking : bool; what : string }

let find tbl name = Hashtbl.find_opt tbl name

let field_kind (f : P.field) =
  match f.map with
  | Some (k, v) -> Printf.sprintf "map<%s, %s>" k.ty (match v.type_name with Some t -> t | None -> v.ty)
  | None ->
      (if f.repeated then "repeated " else "") ^ (match f.type_name with Some t -> t | None -> f.ty) ^ if f.group then " (group)" else ""

(* The findings for the types reachable from [roots]. *)
let compare ~(old_ : P.registry) ~(new_ : P.registry) (roots : string list) : finding list =
  let out = ref [] in
  let say tname breaking fmt = Printf.ksprintf (fun what -> out := { tname; breaking; what } :: !out) fmt in
  let seen = Hashtbl.create 16 in
  let rec visit name =
    if not (Hashtbl.mem seen name) then begin
      Hashtbl.replace seen name ();
      match find old_.messages name, find old_.enums name with
      | Some m, _ -> message name m
      | None, Some vs -> enum name vs
      | None, None -> ()
    end
  and message name (m : P.message) =
    match find new_.messages name with
    | None -> say name true "message %s removed" name
    | Some m' ->
        if m.syntax <> m'.syntax then say name true "syntax changed from %s to %s" m.syntax m'.syntax;
        List.iter
          (fun (f : P.field) ->
            match List.find_opt (fun (g : P.field) -> g.number = f.number) m'.fields with
            | None -> (
                match List.find_opt (fun (g : P.field) -> g.name = f.name) m'.fields with
                | Some g -> say name true "field %s renumbered from %d to %d" f.name f.number g.number
                | None -> say name true "field %s (%d) removed" f.name f.number)
            | Some g ->
                if g.name <> f.name then say name true "field %d renamed from %s to %s" f.number f.name g.name
                else if g.json_name <> f.json_name then say name true "field %s's JSON name changed from %s to %s" f.name f.json_name g.json_name;
                if field_kind g <> field_kind f then say name true "field %s retyped from %s to %s" f.name (field_kind f) (field_kind g);
                if g.presence <> f.presence then say name true "field %s %s presence" f.name (if g.presence then "gained" else "lost");
                if g.oneof <> f.oneof then say name true "field %s moved %s" f.name (match g.oneof with Some o -> "into oneof " ^ o | None -> "out of its oneof");
                if g.required && not f.required then say name true "field %s made required" f.name;
                if g.default <> f.default then say name true "field %s's default changed" f.name;
                (* the types it holds: a change beneath it changes it *)
                (match f.map with Some (_, v) -> Option.iter visit v.type_name | None -> Option.iter visit f.type_name))
          m.fields;
        List.iter
          (fun (g : P.field) ->
            if not (List.exists (fun (f : P.field) -> f.number = g.number || f.name = g.name) m.fields) then
              if g.required then say name true "required field %s (%d) added" g.name g.number
              else say name false "field %s (%d) added" g.name g.number)
          m'.fields
  and enum name vs =
    match find new_.enums name with
    | None -> say name true "enum %s removed" name
    | Some vs' ->
        List.iter
          (fun (v, n) ->
            match List.assoc_opt v vs' with
            | Some n' when n' = n -> ()
            | Some n' -> say name true "enum value %s renumbered from %d to %d" v n n'
            | None -> (
                match List.find_opt (fun (_, k) -> k = n) vs' with
                | Some (v', _) -> say name true "enum value %d renamed from %s to %s" n v v'
                | None -> say name true "enum value %s (%d) removed" v n))
          vs;
        List.iter
          (fun (v, n) ->
            if not (List.exists (fun (w, k) -> w = v || k = n) vs) then say name false "enum value %s (%d) added" v n)
          vs'
  in
  List.iter visit roots;
  List.rev !out

(* The type a dotted name in a definition stands for: a message or enum, or
   the enum of an enum constant (pkg.Enum.VALUE); None for anything else
   (a field selection on a variable, say). *)
let type_of_name (r : P.registry) name =
  if Hashtbl.mem r.messages name || Hashtbl.mem r.enums name then Some name
  else
    match String.rindex_opt name '.' with
    | Some i ->
        let prefix = String.sub name 0 i in
        if Hashtbl.mem r.enums prefix then Some prefix else None
    | None -> None

let breaking = List.filter (fun f -> f.breaking)
let describe f = f.tname ^ ": " ^ f.what
