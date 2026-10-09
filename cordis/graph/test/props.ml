(* Hegel properties for the graph language's syntax
   (docs/cordis/rfc/0002-graph-language.md, "Testing"):
   - round trip: printing a generated program and parsing it gives the
     same program, and printing is idempotent;
   - formatting does not change identity: the same program written with
     random spacing, comments and CEL in its original (non-canonical)
     spelling parses to the same program, with the same hashes;
   - hashes see changes: changing one CEL expression changes the hash of
     its definition and of everything that uses it, and nothing else. *)

open Cordis_graph
open Syntax

let settings =
  let cases = match Sys.getenv_opt "GRAPH_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:5 ()

let run name f = Hegel.run_hegel_test ~settings ~database_key:("cordis:graph:" ^ name) f
let pick tc n = (Typed_gen.of_hegel tc).pick n
let choose tc l = List.nth l (pick tc (List.length l))

(* ---- a generator of programs (syntax only: names need not resolve) ---------------------------- *)

let type_names = [ "Diff"; "Review"; "Verdict"; "Order" ]
let fields = [ "path"; "notes"; "count"; "ok" ]
let effects = [ "repo.read"; "llm.complete"; "http.post" ]
let cells = [ "write"; "critic"; "gate" ]
let assets = [ "draft"; "verdict"; "shipped"; "request" ]

let rec gen_ty tc depth =
  match pick tc (if depth <= 0 then 4 else 9) with
  | 0 -> Scalar (choose tc [ "int"; "uint"; "double"; "bool"; "timestamp"; "duration"; "dyn" ], None)
  | 1 -> Scalar (choose tc [ "string"; "bytes" ], if pick tc 2 = 0 then None else Some (1 + pick tc 1000))
  | 2 -> Named (choose tc type_names)
  | 3 -> Var (choose tc [ "a"; "b" ])
  | 4 -> List (gen_ty tc (depth - 1), if pick tc 3 = 0 then Some (pick tc 50) else None)
  | 5 -> Map (choose tc [ Scalar ("string", None); Scalar ("int", None) ], gen_ty tc (depth - 1), None)
  | 6 -> Option (gen_ty tc (depth - 1))
  | _ ->
      let fs = List.filteri (fun i _ -> i < pick tc 4) fields in
      Record (List.map (fun f -> (f, gen_ty tc (depth - 1))) fs, pick tc 3 = 0)

(* A CEL expression: its original source (single quotes, raw strings) and
   the parsed form with its canonical text. *)
let gen_cel tc =
  let d = Typed_gen.of_hegel tc in
  let src = Typed_gen.expr d [] (Typed_gen.gen_ty d 1) (pick tc 3) in
  match Cordis_cel.Cel.parse src, Celtext.canonical src with
  | Ok ast, Ok text -> (src, Cel { text; ast })
  | _ -> failwith ("generator produced bad CEL: " ^ src)

let gen_expr tc = if pick tc 8 = 0 then ("?todo", Hole "todo") else gen_cel tc

(* Items carry, beside the tree, the original CEL sources by position, so
   a noisy printing can use them. *)
type src_item = { tree : item; sources : string list }

let gen_cell tc =
  let srcs = ref [] in
  let e () = let s, x = gen_expr tc in srcs := s :: !srcs; x in
  let params = List.init (pick tc 3) (fun i -> (Printf.sprintf "p%d" i, gen_ty tc 2)) in
  let result = gen_ty tc 2 in
  let effs = List.filter (fun _ -> pick tc 2 = 0) effects in
  let state = List.init (pick tc 3) (fun i -> let t = gen_ty tc 1 in (Printf.sprintf "s%d" i, t, e ())) in
  let body =
    List.init (pick tc 4) (fun i ->
        if pick tc 2 = 0 then Let (Printf.sprintf "l%d" i, e ()) else Use (Printf.sprintf "u%d" i, choose tc effects, e ()))
  in
  let final = e () in
  let updates = List.filter_map (fun (f, _, _) -> if pick tc 2 = 0 then Some (f, e ()) else None) state in
  { tree = Cell { name = choose tc cells; params; result; effects = effs; state; body; final; updates }; sources = List.rev !srcs }

let gen_graph tc =
  let srcs = ref [] in
  let e () = let s, x = gen_expr tc in srcs := s :: !srcs; x in
  let items =
    List.init (pick tc 7) (fun _ ->
        match pick tc 7 with
        | 0 -> Input (choose tc assets, gen_ty tc 1)
        | 1 -> Stream ("notes", gen_ty tc 1)
        | 2 ->
            Asset
              { name = choose tc assets; cell = choose tc cells;
                args = List.init (pick tc 3) (fun _ -> choose tc assets);
                fresh = (match pick tc 4 with 0 -> None | 1 -> Some On_change | 2 -> Some Always | _ -> Some (Every "5m")) }
        | 3 ->
            let case () =
              let p = match pick tc 4 with 0 -> Wild | 1 -> Case ("Pass", None) | 2 -> Case ("Fail", Some "r") | _ -> Case ("Fail", Some "_") in
              let acts = List.init (1 + pick tc 2) (fun _ -> match pick tc 3 with 0 -> Nothing | 1 -> Enable (choose tc assets) | _ -> Append ("notes", e ())) in
              (p, acts)
            in
            Route (choose tc assets, List.init (1 + pick tc 3) (fun _ -> case ()))
        | 4 -> Before (choose tc cells, choose tc cells)
        | 5 -> Always_inv (e ())
        | _ -> Sometimes_inv (e ()))
  in
  { tree = Graph (choose tc [ "review_loop"; "main" ], items); sources = List.rev !srcs }

let gen_item tc =
  match pick tc 6 with
  | 0 -> { tree = Uses (choose tc [ "text"; "util" ], "sha256:" ^ String.concat "" (List.init 64 (fun _ -> choose tc [ "0"; "a"; "f"; "7" ]))); sources = [] }
  | 1 ->
      let def =
        if pick tc 2 = 0 then Alias (gen_ty tc 2)
        else
          (* a single unit case would read back as a type name *)
          let n = 1 + pick tc 3 in
          Variant (List.init n (fun i -> (List.nth [ "Pass"; "Fail"; "Skipped" ] i, if n = 1 || pick tc 2 = 0 then Some (gen_ty tc 1) else None)))
      in
      { tree = Type_def (choose tc type_names, def); sources = [] }
  | 2 -> { tree = Effect (choose tc effects, gen_ty tc 1, gen_ty tc 1); sources = [] }
  | 3 | 4 -> gen_cell tc
  | _ -> gen_graph tc

(* ---- a noisy printer: the same program, spelled differently ------------------------------------ *)

let noisy tc (it : src_item) =
  let sources = ref it.sources in
  let ws () = choose tc [ " "; "  "; "\n"; "\t"; " // a comment\n"; "\n\n  " ] in
  let cel = function
    | Hole h -> (match !sources with _ :: rest -> sources := rest; "? " ^ h | [] -> failwith "sources")
    | Cel _ -> (match !sources with s :: rest -> sources := rest; s | [] -> failwith "sources")
  in
  let ty t = Print.ty t in
  let w = ws in
  match it.tree with
  | Cell c ->
      let b = Buffer.create 256 in
      let add = Buffer.add_string b in
      add ("cell" ^ w () ^ c.name ^ "(" ^ String.concat ("," ^ w ()) (List.map (fun (p, t) -> p ^ w () ^ ":" ^ w () ^ ty t) c.params) ^ ")" ^ w () ^ ":" ^ w () ^ ty c.result);
      if c.effects <> [] then add (w () ^ "!" ^ w () ^ "{" ^ String.concat (w () ^ "," ^ w ()) c.effects ^ w () ^ "}");
      if c.state <> [] then add (w () ^ "state {" ^ String.concat "" (List.map (fun (f, t, e) -> w () ^ f ^ " : " ^ ty t ^ " =" ^ w () ^ cel e ^ ";") c.state) ^ w () ^ "}");
      add (w () ^ "=" ^ w () ^ "{");
      List.iter
        (function
          | Let (x, e) -> add (w () ^ "let " ^ x ^ w () ^ "=" ^ w () ^ cel e ^ w () ^ ";")
          | Use (x, k, e) -> add (w () ^ "use " ^ x ^ " = " ^ k ^ "(" ^ w () ^ cel e ^ w () ^ ");"))
        c.body;
      add (w () ^ cel c.final);
      if c.updates <> [] then add (" with" ^ w () ^ String.concat ("," ^ w ()) (List.map (fun (f, e) -> "state." ^ f ^ w () ^ ":=" ^ w () ^ cel e) c.updates));
      add (w () ^ ";" ^ w () ^ "}");
      Buffer.contents b
  | Graph (n, items) ->
      let gi = function
        | Route (a, cs) ->
            "route " ^ a ^ w () ^ "{" ^ w ()
            ^ String.concat (";" ^ w ())
                (List.map
                   (fun (p, acts) ->
                     Print.pattern p ^ w () ^ "->" ^ w ()
                     ^ String.concat ("," ^ w ()) (List.map (function Append (s, e) -> s ^ w () ^ "<~" ^ w () ^ cel e | a -> Print.action a) acts))
                   cs)
            ^ w () ^ "}" ^ (if pick tc 2 = 0 then ";" else "")
        | Always_inv e -> "invariant always " ^ cel e ^ ";"
        | Sometimes_inv e -> "invariant sometimes " ^ cel e ^ ";"
        | i -> Print.gitem i
      in
      "graph " ^ n ^ w () ^ "{" ^ String.concat "" (List.map (fun i -> w () ^ gi i) items) ^ w () ^ "}"
  | t -> Print.item t

(* Trees compare by their printed form: CEL by canonical text. *)
let same a b = Print.file a = Print.file b

let test_round_trip () =
  run "round-trip" (fun tc ->
      let items = List.init (1 + pick tc 4) (fun _ -> (gen_item tc).tree) in
      let text = Print.file items in
      Hegel.note tc text;
      match Parse.file text with
      | Error e -> Hegel.require tc ~msg:("the printed program does not parse: " ^ e) false
      | Ok parsed ->
          Hegel.require tc ~msg:"parse(print(p)) differs from p" (same parsed items);
          Hegel.require tc ~msg:"printing is not idempotent" (Print.file parsed = text))

let test_formatting () =
  run "formatting" (fun tc ->
      let items = List.init (1 + pick tc 4) (fun _ -> gen_item tc) in
      let trees = List.map (fun i -> i.tree) items in
      let text = String.concat (choose tc [ "\n"; "\n\n// between\n"; " " ]) (List.map (noisy tc) items) in
      Hegel.note tc text;
      match Parse.file text with
      | Error e -> Hegel.require tc ~msg:("the noisy program does not parse: " ^ e) false
      | Ok parsed ->
          Hegel.require tc ~msg:"formatting changed the program" (same parsed trees);
          Hegel.require tc ~msg:"formatting changed a hash" (Print.hashes parsed = Print.hashes trees))

(* Change the final expression of one cell: its hash and the hashes of the
   graphs that use it change; the types' and effects' do not. *)
let test_hash_sensitivity () =
  run "hash-sensitivity" (fun tc ->
      let cell = (gen_cell tc).tree in
      let name = item_name cell in
      let graph = Graph ("g", [ Asset { name = "a"; cell = name; args = []; fresh = None } ]) in
      let ty = Type_def ("Diff", Alias (Scalar ("int", None))) in
      let before = Print.hashes [ ty; cell; graph ] in
      let _, e = gen_cel tc in
      let changed = match cell with Cell c -> Cell { c with final = e } | i -> i in
      let after = Print.hashes [ ty; changed; graph ] in
      let differs = Print.item cell <> Print.item changed in
      Hegel.note tc (Printf.sprintf "%s\n->\n%s" (Print.item cell) (Print.item changed));
      Hegel.require tc ~msg:"a type's hash moved" (List.assoc "Diff" before = List.assoc "Diff" after);
      Hegel.require tc ~msg:"the cell's hash does not track its text" ((List.assoc name before <> List.assoc name after) = differs);
      Hegel.require tc ~msg:"the graph's hash does not track its cell" ((List.assoc "g" before <> List.assoc "g" after) = differs))

let () =
  Alcotest.run "graph-syntax"
    [ ( "syntax",
        [ Alcotest.test_case "print then parse is the identity" `Quick test_round_trip;
          Alcotest.test_case "formatting changes nothing, not even a hash" `Quick test_formatting;
          Alcotest.test_case "hashes follow definitions and their users" `Quick test_hash_sensitivity ] ) ]
