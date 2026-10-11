(* The protobuf importer (docs/cordis/rfc/0003-types-importers-scaffolds.md,
   "The protobuf importer"): a FileDescriptorSet to graph-language type
   definitions, a check_T cell per type with residue, a report of what was
   approximated, and the name map the boundary codec uses. *)

open Descriptor
module S = Cordis_graph.Syntax

(* The importer's own view of a field's type: finer than the graph
   language's (it remembers 32-bit integers and single-precision floats,
   which need residue or a report, and protobuf's well-known types). *)
type t =
  | I32 | I64 | U32 | U64 | F32 | F64 | Bool | Str | Bytes
  | Ts | Dur | Dyn | Struct | ListV | Empty
  | Wrap of t  (** a wrapper: present or absent *)
  | Named of string  (** an imported message, enum or oneof variant, by graph name *)
  | Rec of string  (** a recursive reference to a named type: dyn in the graph language *)
  | Opt of t | Lst of t | Mp of t * t

type rfield = { name : string; json : string; ty : t; required : bool  (** proto2 required: reading refuses its absence *) }

type def =
  | Message of { full : string; fields : rfield list; oneofs : (string * string) list (** oneof name, variant's graph name *) }
  | Enum of { full : string; values : (string * int) list }
  | Oneof of { owner : string; cases : (string * rfield) list (** case name, member field *) }

type import = {
  defs : (string * def) list;  (** graph name -> definition, in emission order *)
  by_proto : (string * string) list;  (** ".pkg.Name" -> graph name *)
  report : (string * string) list;  (** location, what was approximated *)
}

(* ---- names ------------------------------------------------------------------------------------- *)

let camel s =
  String.split_on_char '_' s
  |> List.filter (( <> ) "")
  |> List.map String.capitalize_ascii
  |> String.concat ""

(* CEL's reserved words cannot be selected bare: x.`in` *)
let cel_reserved =
  [ "true"; "false"; "null"; "in"; "as"; "break"; "const"; "continue"; "else"; "for"; "function"; "if"; "import";
    "let"; "loop"; "package"; "namespace"; "return"; "var"; "void"; "while" ]

let sel f = if List.mem f cel_reserved then "`" ^ f ^ "`" else f

let wkt =
  [ (".google.protobuf.Timestamp", Ts); (".google.protobuf.Duration", Dur);
    (".google.protobuf.Int64Value", Wrap I64); (".google.protobuf.UInt64Value", Wrap U64);
    (".google.protobuf.Int32Value", Wrap I32); (".google.protobuf.UInt32Value", Wrap U32);
    (".google.protobuf.DoubleValue", Wrap F64); (".google.protobuf.FloatValue", Wrap F32);
    (".google.protobuf.BoolValue", Wrap Bool); (".google.protobuf.StringValue", Wrap Str);
    (".google.protobuf.BytesValue", Wrap Bytes); (".google.protobuf.Struct", Struct);
    (".google.protobuf.Value", Dyn); (".google.protobuf.ListValue", ListV); (".google.protobuf.Empty", Empty);
    (".google.protobuf.FieldMask", Str); (".google.protobuf.Any", Dyn) ]

(* ---- the import ---------------------------------------------------------------------------------- *)

let import (files : file list) =
  let report = ref [] in
  let say loc fmt = Printf.ksprintf (fun m -> report := (loc, m) :: !report) fmt in
  (* every message and enum, with its full name, its path in its package,
     and its file; google.protobuf's types map directly *)
  let entries = ref [] in
  List.iter
    (fun f ->
      if f.package <> "google.protobuf" then begin
        let pre = if f.package = "" then "." else "." ^ f.package ^ "." in
        let rec msg path (m : message) =
          let path = path @ [ m.mname ] in
          if not m.map_entry then entries := (pre ^ String.concat "." path, path, f, `M m) :: !entries;
          List.iter (msg path) m.nested;
          List.iter (fun e -> entries := (pre ^ String.concat "." (path @ [ e.ename ]), path @ [ e.ename ], f, `E e) :: !entries) m.enums
        in
        List.iter (msg []) f.messages;
        List.iter (fun e -> entries := (pre ^ e.ename, [ e.ename ], f, `E e) :: !entries) f.fenums;
        if f.syntax = "editions" then say f.fname "editions: field presence is read as explicit (proto2-style)"
      end)
    files;
  let entries = List.rev !entries in
  (* graph names: the path in the package, '_'-joined; prefixed with the
     package where two packages collide *)
  let short (_, path, _, _) = String.concat "_" path in
  let counts = Hashtbl.create 16 in
  List.iter (fun e -> Hashtbl.replace counts (short e) (1 + Option.value (Hashtbl.find_opt counts (short e)) ~default:0)) entries;
  let gname ((_, _, f, _) as e) =
    if Hashtbl.find counts (short e) > 1 && f.package <> "" then
      String.map (fun c -> if c = '.' then '_' else c) f.package ^ "_" ^ short e
    else short e
  in
  let by_proto = List.map (fun ((full, _, _, _) as e) -> (full, gname e)) entries in
  (* map entries, to find a map field's key and value *)
  let map_entries = Hashtbl.create 8 in
  List.iter
    (fun f ->
      let pre = if f.package = "" then "." else "." ^ f.package ^ "." in
      let rec msg path (m : message) =
        let path = path @ [ m.mname ] in
        if m.map_entry then Hashtbl.replace map_entries (pre ^ String.concat "." path) m;
        List.iter (msg path) m.nested
      in
      List.iter (msg []) f.messages)
    files;
  let rec scalar loc (fd : field) =
    match fd.ty with
    | 1 -> F64 | 2 -> say loc "float: single precision is widened to double"; F32
    | 3 | 16 | 18 -> I64 | 4 | 6 -> U64 | 5 | 15 | 17 -> I32 | 7 | 13 -> U32
    | 8 -> Bool | 9 -> Str | 12 -> Bytes
    | 10 -> say loc "a group is not imported (dyn)"; Dyn
    | 11 | 14 -> (
        match List.assoc_opt fd.type_name wkt with
        | Some t -> if fd.type_name = ".google.protobuf.Any" then say loc "Any: the type URL is not checked (dyn)"; t
        | None -> (
            match Hashtbl.find_opt map_entries fd.type_name with
            | Some entry when fd.label = Repeated ->
                let k = List.find (fun (x : field) -> x.number = 1) entry.fields
                and v = List.find (fun (x : field) -> x.number = 2) entry.fields in
                Mp (scalar loc k, scalar loc v)
            | _ -> (
                match List.assoc_opt fd.type_name by_proto with
                | Some g -> Named g
                | None -> say loc "%s is not in the descriptor set (dyn)" fd.type_name; Dyn)))
    | n -> say loc "unknown field type %d (dyn)" n; Dyn
  in
  (* the definitions *)
  let defs = ref [] in
  List.iter
    (fun ((full, _, f, kind) as e) ->
      let g = gname e in
      let loc = f.fname ^ ": " ^ String.sub full 1 (String.length full - 1) in
      match kind with
      | `E en ->
          let values =
            List.fold_left (fun acc (n, num) -> if List.exists (fun (_, m) -> m = num) acc then acc else acc @ [ (n, num) ]) [] en.values
          in
          if List.length values < List.length en.values then say loc "aliases: each number keeps its first name";
          if f.syntax <> "proto2" then say loc "open enum: unknown numbers are refused at the boundary";
          List.iter
            (fun (n, _) -> if not (n.[0] >= 'A' && n.[0] <= 'Z') then say loc "value %s: a lower-case case name" n)
            values;
          defs := (g, Enum { full; values }) :: !defs
      | `M m ->
          let proto2 = f.syntax = "proto2" || f.syntax = "editions" in
          let real_oneof i = not (List.exists (fun (fd : field) -> fd.oneof_index = Some i && fd.proto3_optional) m.fields) in
          let field_ty (fd : field) =
            let floc = loc ^ "." ^ fd.name in
            if fd.default_value <> "" then
              say floc "default %S is not applied: an absent field stays absent (option)" fd.default_value;
            let base = scalar floc fd in
            match fd.label, base with
            | Repeated, Mp _ -> base
            | Repeated, t -> Lst t
            | Required, t -> t
            | Optional, (Wrap _ as t) -> t
            (* explicit presence: proto3 optional, proto2, any message field *)
            | Optional, t when fd.proto3_optional || proto2 || fd.ty = 11 -> Opt t
            | Optional, t -> t
          in
          let plain = List.filter (fun (fd : field) -> match fd.oneof_index with Some i -> not (real_oneof i) | None -> true) m.fields in
          let fields = List.map (fun (fd : field) -> { name = fd.name; json = fd.json_name; ty = field_ty fd; required = fd.label = Required }) plain in
          let oneofs =
            List.filteri (fun i _ -> real_oneof i) m.oneofs
            |> List.map (fun o ->
                   let i = let rec find k = function [] -> -1 | x :: r -> if x = o then k else find (k + 1) r in find 0 m.oneofs in
                   let members = List.filter (fun (fd : field) -> fd.oneof_index = Some i) m.fields in
                   let vname = g ^ "_" ^ o in
                   let cases =
                     List.map (fun (fd : field) -> (camel fd.name, { name = fd.name; json = fd.json_name; ty = scalar (loc ^ "." ^ fd.name) fd; required = false })) members
                   in
                   defs := (vname, Oneof { owner = g; cases }) :: !defs;
                   (o, vname))
          in
          let fields = fields @ List.map (fun (o, vname) -> { name = o; json = camel o |> String.uncapitalize_ascii; ty = Opt (Named vname); required = false }) oneofs in
          defs := (g, Message { full; fields; oneofs }) :: !defs)
    entries;
  (* oneof variants come before their owner, so the owner can name them *)
  let defs = List.rev !defs in
  (* recursion: drop the edges a depth-first search finds going back *)
  let refs t = let rec go = function Named n -> [ n ] | Opt t | Lst t | Wrap t -> go t | Mp (k, v) -> go k @ go v | _ -> [] in go t in
  let state = Hashtbl.create 16 and back = Hashtbl.create 8 in
  (* back: (type, field) -> the names that field reaches back to *)
  let fields_of g =
    match List.assoc_opt g defs with
    | Some (Message { fields; _ }) -> List.map (fun f -> (f.name, f.ty)) fields
    | Some (Oneof { cases; _ }) -> List.map (fun (c, f) -> (c, f.ty)) cases
    | _ -> []
  in
  let rec visit g =
    Hashtbl.replace state g `Active;
    List.iter
      (fun (fname, ty) ->
        List.iter
          (fun n ->
            match Hashtbl.find_opt state n with
            | Some `Active -> Hashtbl.replace back (g, fname) (n :: Option.value (Hashtbl.find_opt back (g, fname)) ~default:[])
            | Some `Done -> ()
            | None -> visit n)
          (refs ty))
      (fields_of g);
    Hashtbl.replace state g `Done
  in
  List.iter (fun (g, _) -> if not (Hashtbl.mem state g) then visit g) defs;
  (* only the recursive reference becomes dyn: repeated Category stays a
     list, of dyn *)
  let cut g fname ty =
    match Hashtbl.find_opt back (g, fname) with
    | None -> ty
    | Some targets ->
        say g "%s.%s refers back to %s: recursion is not representable yet (dyn), and the residue does not check beneath it"
          g fname (String.concat ", " targets);
        let rec go = function
          | Named n when List.mem n targets -> Rec n
          | Opt t -> Opt (go t) | Lst t -> Lst (go t) | Wrap t -> Wrap (go t) | Mp (k, v) -> Mp (go k, go v)
          | t -> t
        in
        go ty
  in
  let defs =
    List.map
      (fun (g, d) ->
        match d with
        | Message m -> (g, Message { m with fields = List.map (fun f -> { f with ty = cut g f.name f.ty }) m.fields })
        | Oneof o -> (g, Oneof { o with cases = List.map (fun (c, f) -> (c, { f with ty = cut g c f.ty })) o.cases })
        | e -> (g, e))
      defs
  in
  if defs <> [] then say "(all)" "protobuf has no size bounds: the configured default ceiling applies";
  { defs; by_proto; report = List.rev !report }

(* ---- the types file ------------------------------------------------------------------------------ *)

let rec graph_ty = function
  | I32 | I64 -> S.Scalar ("int", None)
  | U32 | U64 -> S.Scalar ("uint", None)
  | F32 | F64 -> S.Scalar ("double", None)
  | Bool -> S.Scalar ("bool", None)
  | Str -> S.Scalar ("string", None)
  | Bytes -> S.Scalar ("bytes", None)
  | Ts -> S.Scalar ("timestamp", None)
  | Dur -> S.Scalar ("duration", None)
  | Dyn | Rec _ -> S.Scalar ("dyn", None)
  | Struct -> S.Map (S.Scalar ("string", None), S.Scalar ("dyn", None), None)
  | ListV -> S.List (S.Scalar ("dyn", None), None)
  | Empty -> S.Record ([], false)
  | Wrap t -> S.Option (graph_ty t)
  | Named n -> S.Named n
  | Opt t -> S.Option (graph_ty t)
  | Lst t -> S.List (graph_ty t, None)
  | Mp (k, v) -> S.Map (graph_ty k, graph_ty v, None)

(* The residue reachable from a value of type [t] at CEL path [p]. *)
let rec residue (imp : import) t p depth =
  let conj = String.concat " && " in
  match t with
  | I32 -> [ Printf.sprintf "(-2147483648 <= %s && %s <= 2147483647)" p p ]
  | U32 -> [ Printf.sprintf "%s <= 4294967295u" p ]
  | Wrap t | Opt t -> (
      match residue imp t (p ^ ".value()") depth with
      | [] -> []
      | inner -> [ Printf.sprintf "(!%s.hasValue() || (%s))" p (conj inner) ])
  | Lst t -> (
      let v = Printf.sprintf "e%d" depth in
      match residue imp t v (depth + 1) with [] -> [] | inner -> [ Printf.sprintf "%s.all(%s, %s)" p v (conj inner) ])
  | Mp (k, vt) -> (
      let v = Printf.sprintf "k%d" depth in
      match residue imp k v (depth + 1) @ residue imp vt (Printf.sprintf "%s[%s]" p v) (depth + 1) with
      | [] -> []
      | inner -> [ Printf.sprintf "%s.all(%s, %s)" p v (conj inner) ])
  | Named n -> (
      match List.assoc_opt n imp.defs with
      | Some (Message { fields; _ }) -> List.concat_map (fun f -> residue imp f.ty (p ^ "." ^ sel f.name) depth) fields
      | Some (Oneof { cases; _ }) -> (
          let b = Printf.sprintf "c%d" depth in
          let arms =
            List.filter_map
              (fun (c, f) -> match residue imp f.ty b (depth + 1) with [] -> None | inner -> Some (Printf.sprintf "%s(%s) -> %s" c b (conj inner)))
              cases
          in
          match arms with [] -> [] | arms -> [ Printf.sprintf "match %s { %s; _ -> true }" p (String.concat "; " arms) ])
      | _ -> [])
  | _ -> []

let types_file (imp : import) : S.file =
  let types =
    List.map
      (fun (g, d) ->
        match d with
        | Message { fields; _ } -> S.Type_def (g, S.Alias (S.Record (List.map (fun f -> (f.name, graph_ty f.ty)) fields, false)), None)
        | Enum { values; _ } -> S.Type_def (g, S.Variant (List.map (fun (n, _) -> (n, None)) values), None)
        | Oneof { cases; _ } -> S.Type_def (g, S.Variant (List.map (fun (c, f) -> (c, Some (graph_ty f.ty))) cases), None))
      imp.defs
  in
  let checks =
    List.filter_map
      (fun (g, d) ->
        match d with
        | Message _ | Oneof _ -> (
            match residue imp (Named g) "x" 0 with
            | [] -> None
            | preds -> (
                let src = String.concat " && " preds in
                match Cordis_graph.Parse.file (Printf.sprintf "cell check_%s(x : %s) : bool = { %s; }" g g src) with
                | Ok [ c ] -> Some c
                | Ok _ -> None
                | Error m -> failwith ("residue for " ^ g ^ ": " ^ m)))
        | Enum _ -> None)
      imp.defs
  in
  types @ checks

(* The name map, for the record: graph name -> the protobuf names. *)
let name_map (imp : import) : Yojson.Safe.t =
  `Assoc
    (List.map
       (fun (g, d) ->
         let field f = (f.name, `Assoc [ ("json", `String f.json) ]) in
         ( g,
           match d with
           | Message { full; fields; _ } -> `Assoc [ ("message", `String full); ("fields", `Assoc (List.map field fields)) ]
           | Enum { full; values } -> `Assoc [ ("enum", `String full); ("values", `Assoc (List.map (fun (n, v) -> (n, `Int v)) values)) ]
           | Oneof { owner; cases } ->
               `Assoc [ ("oneof_of", `String owner); ("cases", `Assoc (List.map (fun (c, f) -> (c, `Assoc [ ("field", `String f.name); ("json", `String f.json) ])) cases)) ] ))
       imp.defs)
