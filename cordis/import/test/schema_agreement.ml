(* The JSON Schema importer against Python's jsonschema (docs/cordis/rfc/
   0003-types-importers-scaffolds.md, "The JSON Schema importer",
   "Testing"), by three Hegel properties:

   - corpus schemas: for values drawn from the imported types (scalars
     from small pools that straddle the schemas' bounds), the codec writes
     JSON that jsonschema accepts exactly when the codec's strict read
     does, and the read gives back the value;
   - generated schemas: for schemas drawn from a grammar of the supported
     keywords and instances drawn from small pools, the validation
     predicate's verdict is jsonschema's;
   - generated schemas, imported: the types file checks, and the codec
     reads an instance only if jsonschema accepts it, and refuses a valid
     one only where the record closes an object the schema left open.

     schema_agreement.exe JSVALID.py SCHEMA.json... *)

open Cordis_import
module J = Yojson.Safe
module V = Cordis_cel.Value

let settings =
  let cases = match Sys.getenv_opt "IMPORT_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  let seed = match Sys.getenv_opt "IMPORT_PROPS_SEED" with Some s -> int_of_string s | None -> 41 in
  Hegel.Settings.create ~test_cases:cases ~seed ()

let jsvalid = Sys.argv.(1)
let corpus = Array.to_list (Array.sub Sys.argv 2 (Array.length Sys.argv - 2))
let proc = lazy (Unix.open_process_args "python3" [| "python3"; jsvalid |])

let jsonschema schema instance =
  let ic, oc = Lazy.force proc in
  output_string oc (J.to_string (`Assoc [ ("schema", schema); ("instance", instance) ]) ^ "\n");
  flush oc;
  match J.from_string (input_line ic) with
  | `Assoc [ ("valid", `Bool b) ] -> Ok b
  | `Assoc [ ("error", `String e) ] -> Error e
  | j -> Error (J.to_string j)

open Typed_values

(* the swarm for one test case: which keyword families, instance kinds and
   perturbations it may draw (Typed_gen.swarm) *)
let keyword_swarm = ref (List.init 31 Fun.id)
let instance_swarm = ref (List.init 7 Fun.id)
let perturb_swarm = ref (List.init 7 Fun.id)

(* Measured on this suite (seven mutants, five seeds, 1,000 cases, the
   example database off): swarm caught as often as without it, so it is off
   unless SWARM=1. Targeting typed locations and valid instances (on unless
   TARGET=0) also caught about as often, but found one input shape, a key
   given twice, that no other mode reached. *)
let new_swarm d =
  if Sys.getenv_opt "SWARM" = Some "1" then begin
    keyword_swarm := Typed_gen.swarm d 31;
    instance_swarm := Typed_gen.swarm d 7;
    perturb_swarm := Typed_gen.swarm d 7
  end

(* One node of a JSON document changed: what a writer that is almost right
   sends. Nodes are numbered in pre-order; [k] picks one. *)
let perturb d (j : J.t) : J.t =
  let count = ref 0 in
  let rec size (j : J.t) = 1 + (match j with `List xs -> List.fold_left (fun a x -> a + size x) 0 xs | `Assoc kv -> List.fold_left (fun a (_, x) -> a + size x) 0 kv | _ -> 0) in
  let target = d.Typed_gen.pick (size j) in
  let change (j : J.t) : J.t =
    match Typed_gen.pick_from d !perturb_swarm, j with
    | 0, _ -> `Null
    | 1, _ -> `Float 2.5
    | 2, _ -> `String "x"
    | 3, `Assoc ((_ :: _) as kv) -> `Assoc (List.filteri (fun i _ -> i <> d.pick (List.length kv)) kv)
    | 4, `Assoc kv -> `Assoc (kv @ [ ("zz", `Int 1) ])
    | 5, _ -> `List []
    | _, _ -> `Int (-1)
  in
  let rec go (j : J.t) : J.t =
    let here = !count in
    incr count;
    if here = target then change j
    else
      match j with
      | `List xs -> `List (List.map go xs)
      | `Assoc kv -> `Assoc (List.map (fun (k, x) -> (k, go x)) kv)
      | j -> j
  in
  go j

(* the refusals RFC 0003 documents: a property a closed record does not
   name, a non-object where an untyped schema has properties, an integer
   beyond int64, and a key given twice in one object *)
let refused_as_closed e =
  let has sub = let n = String.length sub in let rec go k = k + n <= String.length e && (String.sub e k n = sub || go (k + 1)) in go 0 in
  has " has no property " || has " is a record: " || has " is beyond int64" || has " given twice"

let test_corpus () =
  let docs = List.map (fun p -> (p, J.from_file p)) corpus in
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:schema-corpus" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      new_swarm d;
      let path, doc = choose d docs in
      pools_for doc;
      let imp = Jsonschema.import doc in
      let v = value imp d 7 (Named imp.root) in
      match Jsonschema_codec.write imp imp.root v with
      | Error e -> Hegel.require tc ~msg:("the codec could not write a value of its type: " ^ e ^ ": " ^ V.to_string v) false
      | Ok j -> (
          let read = Jsonschema_codec.read imp imp.root j in
          Hegel.note tc (Printf.sprintf "%s\n%s\nread: %s" path (J.to_string j) (match read with Ok _ -> "ok" | Error e -> e));
          match jsonschema doc j with
          | Error e -> Hegel.require tc ~msg:("jsonschema: " ^ e) false
          | Ok valid ->
              Hegel.require tc ~msg:(Printf.sprintf "jsonschema says %b, the codec %s" valid (match read with Ok _ -> "reads it" | Error e -> "refuses it: " ^ e)) (valid = Result.is_ok read);
              (match read with Ok w -> Hegel.require tc ~msg:("read back differently: " ^ V.to_string w) (same w v) | Error _ -> ());
              (* and the same document, changed in one place *)
              let p = perturb d j in
              let read = Jsonschema_codec.read imp imp.root p in
              match jsonschema doc p with
              | Error e -> Hegel.require tc ~msg:("jsonschema: " ^ e) false
              | Ok valid -> (
                  Hegel.note tc ("changed: " ^ J.to_string p);
                  match read with
                  | Ok _ -> Hegel.require tc ~msg:(Printf.sprintf "the codec read %s, which jsonschema refuses" (J.to_string p)) valid
                  | Error e ->
                      Hegel.require tc ~msg:(Printf.sprintf "the codec refused %s (%s), which jsonschema accepts" (J.to_string p) e)
                        ((not valid) || refused_as_closed e))))

(* ---- generated schemas ----------------------------------------------------------------------------- *)

let props = [ "a"; "b"; "c"; "x-y" ]
let () = ignore props
let patterns = [ "^a"; "b$"; "^[0-9]+$"; "x"; "^$" ]

(* A schema: one to three keywords, each drawn uniformly from every
   supported keyword (so each is exercised about as often as any other),
   their arguments from small pools; applicators nest. *)
let rec schema ?(top = true) d depth : J.t =
  let sub () = if depth <= 0 then `Assoc [ ("type", `String (choose d [ "string"; "integer"; "number"; "boolean"; "null" ])) ] else schema ~top:false d (depth - 1) in
  let small () = `Int (d.Typed_gen.pick 4) in
  let num () = choose d [ `Int 0; `Int 1; `Int (-1); `Int 2; `Float 0.5; `Float 2.5 ] in
  let keyword () : (string * J.t) list =
    match Typed_gen.pick_from d !keyword_swarm with
    | 0 -> [ ("type", `String (choose d [ "string"; "integer"; "number"; "boolean"; "null"; "object"; "array" ])) ]
    | 1 -> [ ("type", `List [ `String (choose d [ "string"; "integer"; "object"; "array" ]); `String "null" ]) ]
    | 2 -> [ ("minLength", small ()) ]
    | 3 -> [ ("maxLength", small ()) ]
    | 4 -> [ ("pattern", `String (choose d patterns)) ]
    | 5 -> [ ("minimum", num ()) ]
    | 6 -> [ ("maximum", num ()) ]
    | 7 -> [ ("exclusiveMinimum", num ()) ]
    | 8 -> [ ("exclusiveMaximum", num ()) ]
    | 9 -> [ ("multipleOf", choose d [ `Int 2; `Float 0.5; `Int 3 ]) ]
    | 10 when d.pick 2 = 0 -> [ ("enum", `List (List.init (1 + d.pick 3) (fun _ -> `String (choose d [ "a"; "b"; "ab"; "x-y" ])))) ]
    | 10 -> [ ("enum", `List (List.init (1 + d.pick 3) (fun _ -> instance d 0))) ]
    | 11 -> [ ("const", instance d 1) ]
    | 12 -> [ ("properties", `Assoc (List.map (fun p -> (p, sub ())) (List.filter (fun _ -> d.pick 2 = 0) props))) ]
    | 13 -> [ ("required", `List (List.map (fun p -> `String p) (List.filter (fun _ -> d.pick 2 = 0) props))) ]
    | 14 -> [ ("properties", `Assoc [ ("a", sub ()) ]); ("additionalProperties", if d.pick 2 = 0 then `Bool false else sub ()) ]
    | 15 -> [ ("patternProperties", `Assoc [ (choose d patterns, sub ()) ]) ]
    | 16 -> [ ("propertyNames", `Assoc [ ("pattern", `String (choose d patterns)) ]) ]
    | 17 -> [ (choose d [ "minProperties"; "maxProperties" ], small ()) ]
    | 18 -> [ ("dependentRequired", `Assoc [ (choose d props, `List [ `String (choose d props) ]) ]) ]
    | 19 -> [ ("dependentSchemas", `Assoc [ (choose d props, sub ()) ]) ]
    | 20 -> [ ("items", sub ()) ]
    | 21 -> [ ("prefixItems", `List [ sub () ]); ("items", sub ()) ]
    | 22 -> [ ("contains", sub ()) ] @ (if d.pick 2 = 0 then [ ("minContains", small ()) ] else []) @ (if d.pick 2 = 0 then [ ("maxContains", small ()) ] else [])
    | 23 -> [ (choose d [ "minItems"; "maxItems" ], small ()) ]
    | 24 -> [ ("uniqueItems", `Bool true) ]
    | 25 when d.pick 3 = 0 ->
        let other = if d.pick 2 = 0 then `Assoc [ ("type", `List [ `String (choose d [ "string"; "integer" ]); `String "null" ]) ] else sub () in
        [ (choose d [ "anyOf"; "oneOf" ], `List [ `Assoc [ ("type", `String "null") ]; other ]) ]
    | 25 -> [ (choose d [ "allOf"; "anyOf"; "oneOf" ], `List [ sub (); sub () ]) ]
    | 26 -> [ ("not", sub ()) ]
    | 27 -> [ ("if", sub ()); ("then", sub ()) ] @ (if d.pick 2 = 0 then [ ("else", sub ()) ] else [])
    | 28 ->
        (* a plain record: what the importer makes a typed record *)
        let ps = List.filter (fun _ -> d.pick 3 > 0) props in
        [ ("type", `String "object"); ("properties", `Assoc (List.map (fun p -> (p, sub ())) ps));
          ("required",
            `List
              (List.filter_map (fun p -> if d.pick 2 = 0 then Some (`String p) else None) ps
              (* sometimes a name the properties do not list *)
              @ if d.pick 4 = 0 then [ `String "zz" ] else [])) ]
        @ if d.pick 2 = 0 then [ ("additionalProperties", `Bool false) ] else []
    (* the interpreter only: what the sibling and nested keywords evaluated decides *)
    | 30 when d.pick 2 = 0 ->
        (* properties evaluated beside it, in an applicator, or by a branch of if *)
        let ps () = `Assoc [ ("properties", `Assoc (List.map (fun p -> (p, sub ())) (List.filter (fun _ -> d.pick 2 = 0) props))) ] in
        let around =
          match d.pick 4 with
          | 0 -> [ ("properties", `Assoc [ (choose d props, sub ()) ]) ]
          | 1 -> [ (choose d [ "allOf"; "anyOf"; "oneOf" ], `List [ ps (); ps () ]) ]
          | 2 when d.pick 2 = 0 -> [ ("if", ps ()); ("then", ps ()); ("else", ps ()) ]
          | 2 -> [ ("if", `Assoc [ ("properties", `Assoc (List.map (fun p -> (p, `Bool true)) props)) ]) ]
          | _ -> [ ("patternProperties", `Assoc [ (choose d patterns, sub ()) ]) ]
        in
        around @ [ ("unevaluatedProperties", if d.pick 2 = 0 then `Bool false else sub ()) ]
    | 30 ->
        let around =
          match d.pick 4 with
          | 0 -> [ ("prefixItems", `List [ sub () ]) ]
          | 1 -> [ ("contains", sub ()) ]
          | 2 -> [ (choose d [ "allOf"; "anyOf"; "oneOf" ], `List [ `Assoc [ ("prefixItems", `List [ sub (); sub () ]) ]; `Assoc [ ("contains", sub ()) ] ]) ]
          | _ -> if d.pick 2 = 0 then [ ("items", sub ()) ] else []
        in
        around @ [ ("unevaluatedItems", if d.pick 2 = 0 then `Bool false else sub ()) ]
    | 29 when top && d.pick 2 = 0 ->
        (* recursive: a tree, or nested lists, of a leaf type *)
        let leaf = sub () in
        let node =
          if d.pick 2 = 0 then
            `Assoc [ ("type", `String "object"); ("properties", `Assoc [ ("v", leaf); ("kids", `Assoc [ ("type", `String "array"); ("items", `Assoc [ ("$ref", `String "#/$defs/d") ]) ]) ]) ]
          else `Assoc [ ("anyOf", `List [ leaf; `Assoc [ ("type", `String "array"); ("items", `Assoc [ ("$ref", `String "#/$defs/d") ]) ] ]) ]
        in
        [ ("$defs", `Assoc [ ("d", node) ]); ("$ref", `String "#/$defs/d") ]
    | _ when top -> [ ("$defs", `Assoc [ ("d", sub ()) ]); ("$ref", `String "#/$defs/d") ]
    | _ -> [ ("type", `String "string") ]
  in
  (* one keyword half the time: several combined make most locations dyn *)
  let kw = List.concat (List.init (if d.pick 2 = 0 then 1 else 1 + d.pick 3) (fun _ -> keyword ())) in
  (* a keyword given twice: the last *)
  `Assoc (List.rev (List.fold_left (fun acc (k, v) -> (k, v) :: List.remove_assoc k acc) [] kw))

(* instances from the pools (set from the schema by [pools_for]), arrays
   sometimes with a repeated element *)
and instance d depth : J.t =
  let kinds = List.filter (fun k -> depth > 0 || k < 5) !instance_swarm in
  match if kinds = [] then d.Typed_gen.pick 5 else Typed_gen.pick_from d kinds with
  | 0 -> `Null
  | 1 -> `Bool (d.pick 2 = 0)
  | 2 -> `Int (Int64.to_int (choose d !ints))
  | 3 -> `Float (choose d !nums)
  | 4 -> `String (choose d !strings)
  | 5 ->
      let xs = List.init (d.pick 4) (fun _ -> instance d (depth - 1)) in
      `List (match xs with x :: _ when d.pick 3 = 0 -> x :: xs | xs -> xs)
  | _ -> `Assoc (List.sort_uniq (fun (a, _) (b, _) -> compare a b) (List.init (d.pick 4) (fun _ -> (choose d (props @ [ "a1"; "1" ]), instance d (depth - 1)))))

let test_predicate () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:schema-predicate" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      new_swarm d;
      let s = schema d (1 + d.pick 3) in
      pools_for s;
      let interpreted = Schema_eval.supported s = Ok () in
      let compiled = match Schema_pred.compile s "x" with src -> Some src | exception Schema_pred.Unsupported _ -> None in
      (* the interpreter reads whatever the predicate compiles *)
      if compiled <> None then Hegel.require tc ~msg:("the interpreter refuses a schema the predicate compiles: " ^ J.to_string s) interpreted;
      (* an unevaluated* keyword decides only on objects and arrays whose
         members other keywords evaluated: draw more of those *)
      let annotating =
        let t = J.to_string s and sub = "\"unevaluated" in
        let n = String.length sub in
        let rec go k = k + n <= String.length t && (String.sub t k n = sub || go (k + 1)) in
        go 0
      in
      for k = 1 to if annotating then 12 else 6 do
        let i =
          if k > 6 then begin
            let saved = !instance_swarm in
            instance_swarm := [ 5; 6 ];
            let i = instance d 3 in
            instance_swarm := saved;
            i
          end
          else instance d 3
        in
        (* the interpreter against jsonschema, recursion included *)
        if interpreted then
          (match jsonschema s i, Schema_eval.check s i with
           | Ok valid, Ok b ->
               Hegel.require tc ~msg:(Printf.sprintf "schema %s\ninstance %s\njsonschema %b, the interpreter %b" (J.to_string s) (J.to_string i) valid b) (valid = b)
           | Error e, _ -> Hegel.require tc ~msg:("jsonschema: " ^ e ^ "\n" ^ J.to_string s) false
           | _, Error e -> Hegel.require tc ~msg:("the interpreter: " ^ e ^ "\n" ^ J.to_string s) false)
      done;
      match compiled with
      | None -> ()
      | Some src ->
      for _ = 1 to 6 do
        let i = instance d 2 in
        match jsonschema s i, Cordis_cel.Cel.eval ~vars:[ ("x", Schema_pred.value i) ] src with
        | Ok valid, Ok { value = V.Bool b; _ } ->
            Hegel.require tc
              ~msg:(Printf.sprintf "schema %s\ninstance %s\njsonschema %b, the predicate %b\n%s" (J.to_string s) (J.to_string i) valid b src)
              (valid = b)
        | Error e, _ -> Hegel.require tc ~msg:("jsonschema: " ^ e ^ "\n" ^ J.to_string s) false
        | _, Ok { value; _ } -> Hegel.require tc ~msg:(Printf.sprintf "the predicate gave %s on %s\n%s" (V.to_string value) (J.to_string i) src) false
        | _, Error e -> Hegel.require tc ~msg:("the predicate: " ^ e ^ "\n" ^ src) false
      done)

let test_import () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:schema-import" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      new_swarm d;
      let s = schema d (1 + d.pick 3) in
      match Jsonschema.import s with
      | exception Jsonschema.Refused _ -> ()
      | imp ->
          let types = Cordis_graph.Print.file (Jsonschema.types_file imp) in
          Hegel.note tc (J.to_string s ^ "\n" ^ types);
          (* guide the search toward schemas the importer types rather than
             leaves dyn: that is where its own rules live *)
          if Sys.getenv_opt "TARGET" <> Some "0" then begin
            let rec typed (t : Jsonschema.t) =
              match t with
              | Dyn | Checked _ -> 0
              | Rec _ -> 1
              | Opt t | Lst (t, _) | Mp (t, _) -> 1 + typed t
              | _ -> 1
            in
            let score =
              List.fold_left
                (fun acc (_, d) ->
                  acc + match d with
                    | Jsonschema.Record fs -> 1 + List.fold_left (fun a (f : Jsonschema.field) -> a + typed f.ty) 0 fs
                    | Enum _ | Variant _ -> 2
                    | Alias t -> typed t)
                0 imp.defs
            in
            Hegel.target tc ~label:"typed locations" ~value:(float_of_int score)
          end;
          (match Cordis_graph.Patch.load ~options:{ Cordis_graph.Check.default_options with budget = max_int } types with
           | Ok _ -> ()
           | Error es -> Hegel.require tc ~msg:("the types file does not check: " ^ String.concat "; " es) false);
          pools_for s;
          let valid_count = ref 0 in
          for _ = 1 to 6 do
            let i =
              if d.pick 2 = 0 then instance d 2
              else
                match Jsonschema_codec.write imp imp.root (value imp d 4 (Named imp.root)) with
                | Ok j -> if d.pick 2 = 0 then j else perturb d j
                | Error _ -> instance d 2
            in
            match jsonschema s i with
            | Error e -> Hegel.require tc ~msg:("jsonschema: " ^ e) false
            | Ok valid -> (
                if valid then incr valid_count;
                match Jsonschema_codec.read imp imp.root i with
                | Ok _ -> Hegel.require tc ~msg:(Printf.sprintf "the codec read %s, which jsonschema refuses" (J.to_string i)) valid
                | Error e ->
                    Hegel.require tc ~msg:(Printf.sprintf "the codec refused %s (%s), which jsonschema accepts" (J.to_string i) e) ((not valid) || refused_as_closed e))
          done;
          if Sys.getenv_opt "TARGET" <> Some "0" then Hegel.target tc ~label:"valid instances" ~value:(float_of_int !valid_count))

(* ---- by example ------------------------------------------------------------------------------------- *)

(* Schemas where the importer has a rule of its own, each with instances
   on both sides of it. The verdict is jsonschema's, not written here:
   the codec reads an instance exactly when jsonschema accepts it, but for
   RFC 0003's documented refusals (closed records, untyped objects, integers beyond int64). *)
let examples : (string * string list) list =
  [ ({|{"oneOf": [{"type": "null"}, {"type": ["string", "null"]}]}|}, [ "null"; {|"a"|}; "1" ]);
    ({|{"anyOf": [{"type": "null"}, {"type": ["string", "null"]}]}|}, [ "null"; {|"a"|}; "1" ]);
    ({|{"type": ["string", "null"], "const": "a"}|}, [ "null"; {|"a"|}; {|"b"|} ]);
    ({|{"type": ["integer", "null"], "not": {"type": "null"}}|}, [ "null"; "1" ]);
    ({|{"type": "object", "properties": {"a": {"type": "string"}}, "required": ["a", "b"]}|}, [ {|{"a": "x"}|}; {|{"a": "x", "b": 1}|}; {|{"a": "x", "b": null}|} ]);
    ({|{"type": "object", "properties": {"a": {"type": "string"}}, "required": ["a", "b"], "additionalProperties": false}|}, [ {|{"a": "x"}|}; {|{"a": "x", "b": 1}|} ]);
    ({|{"properties": {"a": {"type": "integer"}}}|}, [ {|{"a": 1}|}; "null"; "3"; {|{"a": 1.0}|}; {|{"a": 1.5}|} ]);
    ({|{"type": "object", "properties": {"p": {"if": {"type": "object"}, "then": {"required": ["id"]}, "else": {"type": "null"}}}, "additionalProperties": false}|},
     [ {|{"p": null}|}; {|{"p": {}}|}; {|{"p": {"id": 1}}|}; {|{"p": 3}|}; {|{}|} ]);
    ({|{"type": "object", "properties": {"x": {"const": null}}, "required": ["x"], "additionalProperties": false}|}, [ {|{"x": null}|}; {|{"x": 0}|}; {|{}|} ]);
    ({|{"type": "integer"}|}, [ "1"; "1.0"; "1.5"; "9223372036854775807"; "9223372036854775808"; "1e3"; {|"1"|} ]);
    ({|{"type": "string", "minLength": 2, "maxLength": 3}|}, [ {|"a"|}; {|"hé"|}; {|"héé"|}; {|"hééé"|}; {|"😀😀"|} ]);
    ({|{"type": "array", "items": {"type": "number"}, "uniqueItems": true}|}, [ "[1, 1.0]"; "[1, 2]"; "[]"; {|[1, "1"]|} ]);
    ({|{"type": "object", "additionalProperties": {"type": "integer"}, "propertyNames": {"pattern": "^[a-z]+$"}, "maxProperties": 2}|},
     [ {|{"a": 1}|}; {|{"A": 1}|}; {|{"a": 1, "b": 2, "c": 3}|}; {|{"a": "x"}|} ]);
    ({|{"enum": ["on-hold", "on hold", "OnHold"]}|}, [ {|"on-hold"|}; {|"on hold"|}; {|"OnHold"|}; {|"onhold"|} ]);
    ({|{"oneOf": [{"type": "object", "properties": {"k": {"const": "a"}, "v": {"type": "integer"}}, "required": ["k"], "additionalProperties": false},
                 {"type": "object", "properties": {"k": {"const": "b"}, "w": {"type": "string"}}, "required": ["k", "w"], "additionalProperties": false}]}|},
     [ {|{"k": "a"}|}; {|{"k": "a", "v": 1}|}; {|{"k": "b"}|}; {|{"k": "b", "w": "x"}|}; {|{"k": "c"}|}; {|{"k": "a", "w": "x"}|} ]);
    ({|{"allOf": [{"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"]}, {"properties": {"a": {"minimum": 2}, "b": {"type": "string"}}}]}|},
     [ {|{"a": 1}|}; {|{"a": 2}|}; {|{"a": 2, "b": "x"}|}; {|{"b": "x"}|} ]);
    ({|{"$ref": "#/$defs/n", "$defs": {"n": {"type": "object", "properties": {"v": {"type": "integer", "maximum": 3}, "c": {"type": "array", "items": {"$ref": "#/$defs/n"}}}, "required": ["v"], "additionalProperties": false}}}|},
     [ {|{"v": 1}|}; {|{"v": 1, "c": [{"v": 2}]}|}; {|{"v": 1, "c": [{"v": 9}]}|}; {|{"v": 1, "c": [{"x": 1}]}|}; {|{"v": 1, "c": [3]}|} ]);
    ({|{"type": "number", "multipleOf": 0.1}|}, [ "0.3"; "0.2"; "1"; "0.35" ]);
    ({|{"type": "array", "prefixItems": [{"type": "integer"}], "items": false}|}, [ "[1]"; "[1, 2]"; "[]"; {|["a"]|} ]);
    (* unevaluatedProperties: a closed record assembled by allOf, read by the interpreter *)
    ({|{"type": "object", "allOf": [{"properties": {"a": {"type": "string"}}}, {"properties": {"b": {"type": "integer"}}}], "unevaluatedProperties": false}|},
      [ {|{"a": "x", "b": 1}|}; {|{"a": "x", "c": 1}|}; {|{}|}; {|{"b": "x"}|} ]);
    (* unevaluatedItems sees what contains matched *)
    ({|{"type": "array", "contains": {"type": "string"}, "unevaluatedItems": {"type": "integer"}}|}, [ {|["a", 1]|}; {|["a", true]|}; "[1]" ]);
    (* items evaluates every item, so unevaluatedItems sees none *)
    ({|{"items": {"type": "string"}, "unevaluatedItems": false}|}, [ {|["a", "b"]|}; "[]"; "[1]" ]);
    (* a passing if annotates; then adds to it *)
    ({|{"if": {"properties": {"a": true}}, "then": {"properties": {"b": true}}, "unevaluatedProperties": false}|},
      [ {|{"a": 1, "b": 2}|}; {|{"a": 1, "c": 2}|}; {|{"b": 2}|} ]);
    (* every passing branch of anyOf annotates, not only the first *)
    ({|{"anyOf": [{"properties": {"a": true}}, {"properties": {"b": true}}], "unevaluatedProperties": false}|},
      [ {|{"a": 1, "b": 2}|}; {|{"a": 1, "c": 2}|} ]) ]

let example_tests =
  List.mapi
    (fun i (schema, instances) ->
      ( Printf.sprintf "example %d: %s" i (String.sub schema 0 (min 60 (String.length schema))),
        `Quick,
        fun () ->
          let s = J.from_string schema in
          match Jsonschema.import s with
          | exception Jsonschema.Refused m -> Alcotest.failf "refused: %s" m
          | imp ->
              List.iter
                (fun text ->
                  let inst = J.from_string text in
                  let valid = match jsonschema s inst with Ok v -> v | Error e -> Alcotest.failf "jsonschema: %s" e in
                  match Jsonschema_codec.read imp imp.root inst with
                  | Ok _ -> if not valid then Alcotest.failf "the codec read %s, which jsonschema refuses" text
                  | Error e -> if valid && not (refused_as_closed e) then Alcotest.failf "the codec refused %s (%s), which jsonschema accepts" text e)
                instances ))
    examples

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "import-schema-agreement"
    [ ( "jsonschema",
        [ Alcotest.test_case "corpus schemas: the codec agrees with jsonschema" `Quick test_corpus;
          Alcotest.test_case "generated schemas: the predicate agrees with jsonschema" `Quick test_predicate;
          Alcotest.test_case "generated schemas, imported: types check, the codec reads only valid instances" `Quick test_import ] );
      ("examples", example_tests) ]
