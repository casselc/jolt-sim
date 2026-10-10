(* Earlier JSON Schema drafts, read as 2020-12 (docs/cordis/rfc/0003-
   types-importers-scaffolds.md, "The JSON Schema importer"): a schema is
   rewritten to the 2020-12 keywords that mean the same, walking schema
   positions only, so values (enum, const, default, examples) are never
   touched. Drafts 4, 6, 7 and 2019-09 are read; any other $schema is
   refused.

   - draft 4: boolean exclusiveMinimum/exclusiveMaximum become numbers;
     id becomes $id;
   - drafts 4 to 7: a $ref's sibling keywords are ignored, as those drafts
     say (except $defs/definitions, which only hold schemas);
   - drafts 4 to 2019-09: items as an array becomes prefixItems, and
     additionalItems becomes items;
   - drafts 4 to 7: dependencies becomes dependentRequired (arrays) and
     dependentSchemas (schemas). *)

module J = Yojson.Safe

type draft = D4 | D6 | D7 | D2019 | D2020

let draft_of ?(default = D2020) (doc : J.t) =
  match doc with
  | `Assoc kv -> (
      match List.assoc_opt "$schema" kv with
      | None -> Ok default
      | Some (`String s) -> (
          let s = if String.length s > 0 && s.[String.length s - 1] = '#' then String.sub s 0 (String.length s - 1) else s in
          let s = match String.index_opt s ':' with Some i -> String.sub s (i + 1) (String.length s - i - 1) | None -> s in
          match s with
          | "//json-schema.org/draft-04/schema" -> Ok D4
          | "//json-schema.org/draft-06/schema" -> Ok D6
          | "//json-schema.org/draft-07/schema" -> Ok D7
          | "//json-schema.org/draft/2019-09/schema" -> Ok D2019
          | "//json-schema.org/draft/2020-12/schema" -> Ok D2020
          | _ -> Error ("a metaschema this importer does not read: " ^ s))
      | Some j -> Error ("$schema " ^ J.to_string j))
  | `Bool _ -> Ok default
  | _ -> Error "not a schema"

(* keywords whose value is a schema; an object or array of schemas *)
let one = [ "not"; "if"; "then"; "else"; "contains"; "propertyNames"; "additionalProperties"; "unevaluatedProperties"; "unevaluatedItems"; "additionalItems"; "items" ]
let many = [ "allOf"; "anyOf"; "oneOf"; "prefixItems" ]
let named = [ "properties"; "patternProperties"; "$defs"; "definitions"; "dependentSchemas" ]

let rec walk d (s : J.t) : J.t =
  match s with
  | `Assoc kv ->
      let kv =
        (* a $ref's siblings mean nothing before 2019-09 *)
        if d <> D2019 && d <> D2020 && List.mem_assoc "$ref" kv then
          List.filter (fun (k, _) -> List.mem k [ "$ref"; "$defs"; "definitions"; "$schema"; "$id"; "id"; "title"; "description" ]) kv
        else kv
      in
      let kv =
        if d = D4 then
          List.filter_map
            (fun (k, v) ->
              match k, v with
              | "id", _ -> Some ("$id", v)
              | ("exclusiveMinimum" | "exclusiveMaximum"), `Bool false -> None
              | "exclusiveMinimum", `Bool true -> Option.map (fun m -> ("exclusiveMinimum", m)) (List.assoc_opt "minimum" kv)
              | "exclusiveMaximum", `Bool true -> Option.map (fun m -> ("exclusiveMaximum", m)) (List.assoc_opt "maximum" kv)
              | "minimum", _ when List.assoc_opt "exclusiveMinimum" kv = Some (`Bool true) -> None
              | "maximum", _ when List.assoc_opt "exclusiveMaximum" kv = Some (`Bool true) -> None
              | _ -> Some (k, v))
            kv
        else kv
      in
      let kv =
        if d <> D2020 then
          match List.assoc_opt "items" kv with
          | Some (`List _ as prefix) ->
              let rest = List.filter (fun (k, _) -> k <> "items" && k <> "additionalItems") kv in
              rest @ [ ("prefixItems", prefix) ] @ (match List.assoc_opt "additionalItems" kv with Some a -> [ ("items", a) ] | None -> [])
          | _ -> List.filter (fun (k, _) -> k <> "additionalItems") kv
        else kv
      in
      let kv =
        match d, List.assoc_opt "dependencies" kv with
        | (D4 | D6 | D7), Some (`Assoc deps) ->
            let req = List.filter (fun (_, v) -> match v with `List _ -> true | _ -> false) deps in
            let sch = List.filter (fun (_, v) -> match v with `List _ -> false | _ -> true) deps in
            List.remove_assoc "dependencies" kv
            @ (if req <> [] then [ ("dependentRequired", `Assoc req) ] else [])
            @ if sch <> [] then [ ("dependentSchemas", `Assoc sch) ] else []
        | _ -> kv
      in
      `Assoc
        (List.map
           (fun (k, v) ->
             if k = "$schema" then (k, `String "https://json-schema.org/draft/2020-12/schema")
             else if List.mem k one then (k, walk d v)
             else if List.mem k many then (k, match v with `List xs -> `List (List.map (walk d) xs) | v -> v)
             else if List.mem k named then (k, match v with `Assoc ps -> `Assoc (List.map (fun (p, x) -> (p, walk d x)) ps) | v -> v)
             else (k, v))
           kv)
  | s -> s

(* The document as 2020-12, or why not; [default] is the draft of a
   document without $schema. *)
let normalize ?default (doc : J.t) = Result.map (fun d -> walk d doc) (draft_of ?default doc)

let draft_named = function
  | "4" | "draft4" | "draft-04" -> Some D4
  | "6" | "draft6" | "draft-06" -> Some D6
  | "7" | "draft7" | "draft-07" -> Some D7
  | "2019-09" | "draft2019-09" -> Some D2019
  | "2020-12" | "draft2020-12" -> Some D2020
  | _ -> None
