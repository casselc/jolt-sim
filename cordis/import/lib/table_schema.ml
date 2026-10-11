(* Frictionless Table Schema to graph-language types (docs/cordis/rfc/
   0005-xml-csv-frames.md, revision 3, "CSV"): a table's row becomes a
   record, through the JSON Schema importer, so the types file, residue,
   report, name map and strict codec are that importer's. A field's type
   and constraints become the row schema's property:

     string           string (format default; uuid as a pattern)
     integer          int
     number           double
     boolean          bool (its trueValues and falseValues in the name map)
     any              string, the cell's text
     required         required, and not missing
     enum             enum (a string enum is a variant of unit cases)
     minimum/maximum, minLength/maxLength
                      the same keywords
     pattern          the pattern, anchored (Table Schema's are XML Schema
                      regular expressions, matched whole)

   A string field's value is never one of the table's missingValues (it
   would read back as missing): the empty one is minLength 1 in the row
   schema; another is refused when the codec writes it, and reported. unique and
   primaryKey are checked across the table when it is read (the codec's,
   csv_codec.ml), and reported. Refused, naming the field: the calendar
   types (date, time, datetime, year, yearmonth, duration: RFC 0005's open
   question 2), the nested ones (object, array), geopoint and geojson,
   string formats other than default and uuid, and bareNumber,
   decimalChar and groupChar other than their defaults. *)

module J = Yojson.Safe

exception Refused of string

let refuse fmt = Printf.ksprintf (fun m -> raise (Refused m)) fmt

type kind = String | Integer | Number | Boolean | Any

type field = {
  name : string;
  kind : kind;
  true_values : string list;
  false_values : string list;
}

(* A CSV dialect (Frictionless CSV Dialect): what this codec reads and writes *)
type dialect = { delimiter : char; quote : char; header : bool }

type t = {
  imp : Jsonschema.import;
  row : string;  (** the row record's type *)
  fields : field list;  (** in column order *)
  missing : string list;  (** the tokens read as missing; the first is written *)
  unique : string list list;  (** field sets unique across the table: unique fields, and the primary key *)
  dialect : dialect;
  report : (string * string) list;
  schema : J.t;  (** the row's JSON Schema, as imported *)
}

let get (j : J.t) k = match j with `Assoc kv -> List.assoc_opt k kv | _ -> None
let str j k = match get j k with Some (`String s) -> Some s | _ -> None
let strings j k = match get j k with Some (`List l) -> Some (List.filter_map (function `String s -> Some s | _ -> None) l) | _ -> None

let default_dialect = { delimiter = ','; quote = '"'; header = true }

(* a CSV Dialect object; what this codec does not read is refused *)
let dialect_of (j : J.t) =
  let one k d = match str j k with Some s when String.length s = 1 -> s.[0] | Some s -> refuse "dialect: %s %S is not one character" k s | None -> d in
  (match get j "doubleQuote" with Some (`Bool false) -> refuse "dialect: doubleQuote false (an escape character) is not read" | _ -> ());
  List.iter
    (fun k -> match get j k with None | Some `Null -> () | Some (`Bool false) when k = "skipInitialSpace" -> () | Some _ -> refuse "dialect: %s is not read" k)
    [ "escapeChar"; "commentChar"; "skipInitialSpace" ];
  { delimiter = one "delimiter" ','; quote = one "quoteChar" '"'; header = (match get j "header" with Some (`Bool b) -> b | _ -> true) }

let import ?(name = "Row") ?(dialect = default_dialect) (schema : J.t) : t =
  let report = ref [] in
  let say loc fmt = Printf.ksprintf (fun m -> report := (loc, m) :: !report) fmt in
  let fields_json = match get schema "fields" with Some (`List l) -> l | _ -> refuse "a Table Schema has no fields" in
  let missing = Option.value (strings schema "missingValues") ~default:[ "" ] in
  let fields, props, required =
    List.fold_left
      (fun (fs, props, req) f ->
        let fname = match str f "name" with Some n -> n | None -> refuse "a field has no name" in
        let loc = "field " ^ fname in
        if List.exists (fun g -> g.name = fname) fs then refuse "%s is given twice" loc;
        let typ = Option.value (str f "type") ~default:"string" in
        let format = Option.value (str f "format") ~default:"default" in
        let c = match get f "constraints" with Some (`Assoc _ as c) -> c | _ -> `Assoc [] in
        let cget k = get c k in
        if get f "bareNumber" = Some (`Bool false) then refuse "%s: bareNumber false is not read" loc;
        (match str f "decimalChar" with Some "." | None -> () | Some _ -> refuse "%s: a decimalChar other than . is not read" loc);
        (match str f "groupChar" with Some "" | None -> () | Some _ -> refuse "%s: a groupChar is not read" loc);
        let kind, base =
          match typ with
          | "string" -> (
              match format with
              | "default" -> (String, [ ("type", `String "string") ])
              | "uuid" ->
                  ( String,
                    [ ("type", `String "string");
                      ("pattern", `String "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") ] )
              | f -> refuse "%s: the string format %s is not checked" loc f)
          | "integer" -> (Integer, [ ("type", `String "integer") ])
          | "number" -> (Number, [ ("type", `String "number") ])
          | "boolean" -> (Boolean, [ ("type", `String "boolean") ])
          (* a cell is text, whatever the field says of it *)
          | "any" -> (Any, [ ("type", `String "string") ])
          | ("date" | "time" | "datetime" | "year" | "yearmonth" | "duration") as t ->
              refuse "%s: the calendar type %s has no CEL form yet (RFC 0005, open question 2)" loc t
          | ("object" | "array" | "geopoint" | "geojson") as t -> refuse "%s: the type %s has no CSV form here" loc t
          | t -> refuse "%s: unknown type %s" loc t
        in
        let field =
          { name = fname; kind;
            true_values = Option.value (strings f "trueValues") ~default:[ "true"; "True"; "TRUE"; "1" ];
            false_values = Option.value (strings f "falseValues") ~default:[ "false"; "False"; "FALSE"; "0" ] }
        in
        let kw k = match cget k with Some v -> [ (k, v) ] | None -> [] in
        let pattern =
          match cget "pattern" with
          | Some (`String p) -> [ ("pattern", `String ("^(?:" ^ p ^ ")$")) ]
          | Some _ -> refuse "%s: pattern is not a string" loc
          | None -> []
        in
        (* a string is never a missing token: it would read back as missing. The empty one is minLength 1;
           another (NA) the types cannot say without dyn, so the codec refuses to write it, as reported *)
        let not_missing =
          if kind = String && List.mem "" missing && (match cget "minLength" with Some (`Int n) -> n < 1 | _ -> true) then [ ("minLength", `Int 1) ]
          else []
        in
        if kind = String && List.exists (fun m -> m <> "") missing then
          say loc "a value equal to a missing value (%s) is not written: it would read back as missing"
            (String.concat ", " (List.filter (fun m -> m <> "") missing));
        (* a missing cell is an absent property: a field not required is optional *)
        let prop =
          `Assoc
            (base @ kw "enum" @ kw "minimum" @ kw "maximum" @ kw "exclusiveMinimum" @ kw "exclusiveMaximum" @ (if not_missing <> [] then not_missing else kw "minLength")
            @ kw "maxLength" @ pattern)
        in
        let required = cget "required" = Some (`Bool true) in
        if cget "unique" = Some (`Bool true) then say loc "unique: checked across the table when it is read";
        (field :: fs, props @ [ (fname, prop) ], if required then fname :: req else req))
      ([], [], []) fields_json
  in
  let fields = List.rev fields in
  let unique =
    List.filter_map
      (fun f -> match get f "constraints" with Some c when get c "unique" = Some (`Bool true) -> Option.map (fun n -> [ n ]) (str f "name") | _ -> None)
      fields_json
    @
    match get schema "primaryKey" with
    | Some (`String k) -> [ [ k ] ]
    | Some (`List ks) -> [ List.filter_map (function `String s -> Some s | _ -> None) ks ]
    | _ -> []
  in
  List.iter (fun ks -> List.iter (fun k -> if not (List.exists (fun f -> f.name = k) fields) then refuse "the key names no field %s" k) ks) unique;
  if get schema "primaryKey" <> None then say "primaryKey" "unique, and every key field required: checked when the table is read";
  (* a primary key's fields are required *)
  let required =
    match get schema "primaryKey" with
    | Some (`String k) -> if List.mem k required then required else k :: required
    | Some (`List ks) -> List.fold_left (fun r k -> match k with `String k when not (List.mem k r) -> k :: r | _ -> r) required ks
    | _ -> required
  in
  if get schema "foreignKeys" <> None then say "foreignKeys" "not checked: they name other tables";
  let row_schema =
    `Assoc
      [ ("type", `String "object"); ("properties", `Assoc props); ("required", `List (List.map (fun r -> `String r) (List.rev required)));
        ("additionalProperties", `Bool false) ]
  in
  let imp = try Jsonschema.import ~name row_schema with Jsonschema.Refused m | Schema_pred.Unsupported m -> refuse "%s" m in
  { imp; row = imp.root; fields; missing; unique; dialect; report = List.rev !report @ imp.report; schema = row_schema }

(* the name map: the row's, as the JSON Schema importer writes it, and the CSV side: the dialect, the
   columns in order with their kinds and boolean values, the missing values and the unique keys *)
let name_map (t : t) : J.t =
  let kind = function String -> "string" | Integer -> "integer" | Number -> "number" | Boolean -> "boolean" | Any -> "any" in
  `Assoc
    [ ("row", `String t.row);
      ( "dialect",
        `Assoc
          [ ("delimiter", `String (String.make 1 t.dialect.delimiter)); ("quoteChar", `String (String.make 1 t.dialect.quote));
            ("header", `Bool t.dialect.header) ] );
      ( "columns",
        `List
          (List.map
             (fun f ->
               `Assoc
                 ([ ("name", `String f.name); ("kind", `String (kind f.kind)) ]
                 @
                 if f.kind = Boolean then
                   [ ("trueValues", `List (List.map (fun s -> `String s) f.true_values));
                     ("falseValues", `List (List.map (fun s -> `String s) f.false_values)) ]
                 else []))
             t.fields) );
      ("missingValues", `List (List.map (fun s -> `String s) t.missing));
      ("unique", `List (List.map (fun ks -> `List (List.map (fun k -> `String k) ks)) t.unique));
      ("types", Jsonschema.name_map t.imp) ]
