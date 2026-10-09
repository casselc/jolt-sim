(* Hegel properties for the graph checker (docs/cordis/rfc/0002-graph-
   language.md, "Cell bodies"): for inputs that fit the declared types,
   bounds included,
   - every turn of a checked cell costs no more than its estimate;
   - the last turn's result is a value of the declared result type (or an
     error from arithmetic or indexing), and each state update one of its
     field's type;
   over the cells of the example and the accepted corpus, and over
   generated cells whose bodies come from the type-directed CEL generator. *)

open Cordis_graph
open Syntax
module C = Cordis_cel.Checker
module V = Cordis_cel.Value

let settings =
  let cases = match Sys.getenv_opt "GRAPH_PROPS_CASES" with Some n -> int_of_string n | None -> 200 in
  Hegel.Settings.create ~test_cases:cases ~seed:9 ()

let run name f = Hegel.run_hegel_test ~settings ~database_key:("cordis:graph:" ^ name) f
let read path = In_channel.with_open_text path In_channel.input_all

(* ---- values of a declared type ------------------------------------------------------------------ *)

let rec value (d : Typed_gen.draw) defs ?(depth = 3) (t : ty) : V.t =
  let len b = d.pick (1 + min 6 (Option.value b ~default:6)) in
  let v = value d defs ~depth:(depth - 1) in
  match t with
  | Scalar ("int", _) -> V.Int (Int64.of_int (d.pick 2001 - 1000))
  | Scalar ("uint", _) -> V.Uint (Int64.of_int (d.pick 2001))
  | Scalar ("double", _) -> V.Double (float_of_int (d.pick 2001 - 1000) /. 8.)
  | Scalar ("bool", _) -> V.Bool (d.pick 2 = 0)
  | Scalar ("string", b) -> V.String (String.init (len b) (fun _ -> Typed_gen.choose d [ 'a'; 'b'; 'L'; 'G'; 'T'; 'M'; ' ' ]))
  | Scalar ("bytes", b) -> V.Bytes (String.init (len b) (fun _ -> Char.chr (d.pick 256)))
  | Scalar ("timestamp", _) -> (match Cordis_cel.Cel.eval "timestamp(1700000000)" with Ok r -> r.value | Error e -> V.Error e)
  | Scalar ("duration", _) -> (match Cordis_cel.Cel.eval "duration('90s')" with Ok r -> r.value | Error e -> V.Error e)
  | Scalar _ -> V.Int 0L
  | List (t, b) -> V.List (Array.init (if depth <= 0 then 0 else len b) (fun _ -> v t))
  | Map (k, t, b) ->
      let keys = List.sort_uniq compare (List.init (if depth <= 0 then 0 else len b) (fun _ -> v k)) in
      (match V.make_map (List.map (fun key -> (key, v t)) keys) with V.Map _ as m -> m | _ -> V.Map [])
  | Option t -> V.Optional (if d.pick 2 = 0 then None else Some (v t))
  | Record (fs, _) -> (match V.make_map (List.map (fun (f, t) -> (V.String f, v t)) fs) with V.Map _ as m -> m | e -> e)
  | Named n -> (
      match List.assoc_opt n defs with
      | Some (Type_def (_, Alias t)) -> value d defs ~depth t
      | Some (Type_def (_, Variant cs)) ->
          let c, p = Typed_gen.choose d cs in
          (match V.make_map ((V.String "tag", V.String c) :: (match p with Some t -> [ (V.String "value", v t) ] | None -> [])) with
           | V.Map _ as m -> m
           | e -> e)
      | _ -> V.Null)
  | Var _ -> V.Int 1L

(* ---- does a value have a checked type? ------------------------------------------------------------ *)

let rec admits (t : C.t) (x : V.t) =
  match t, x with
  | C.Dyn, _ | C.Opaque _, _ -> true
  | C.Bool, V.Bool _ | C.Int, V.Int _ | C.Uint, V.Uint _ | C.Double, V.Double _ | C.String, V.String _
  | C.Bytes, V.Bytes _ | C.Timestamp, V.Timestamp _ | C.Duration, V.Duration _ | C.Null, V.Null ->
      true
  | C.List t, V.List xs -> Array.for_all (admits t) xs
  | C.Map (k, w), V.Map m -> List.for_all (fun (a, b) -> admits k a && admits w b) m
  | C.Opt t, V.Optional o -> (match o with None -> true | Some x -> admits t x)
  | C.Record (fs, open_), V.Map m ->
      (open_ || List.length fs = List.length m)
      && List.for_all (fun (f, t) -> match V.map_find m (V.String f) with Some x -> admits t x | None -> false) fs
  | C.Variant cs, V.Map m -> (
      match V.map_find m (V.String "tag") with
      | Some (V.String tag) -> (
          match List.assoc_opt tag cs, V.map_find m (V.String "value") with
          | Some None, None -> true
          | Some (Some t), Some x -> admits t x
          | _ -> false)
      | _ -> false)
  | _ -> false

(* ---- the property, for one checked file ------------------------------------------------------------ *)

let check_cells tc (file : file) (r : Check.checked) =
  let defs = List.map (fun i -> (item_name i, i)) file in
  let d = Typed_gen.of_hegel tc in
  List.iter
    (fun (ci : Check.cell_info) ->
      let c = List.find_map (function Cell c when c.name = ci.cname -> Some c | _ -> None) file |> Option.get in
      let params = List.map (fun (p, t) -> (p, value d defs t)) c.params in
      let state = V.make_map (List.map (fun (f, t, _) -> (V.String f, value d defs t)) c.state) in
      let receipts =
        List.filter_map
          (function
            | Use (x, k, _) -> (match List.assoc_opt k defs with Some (Effect (_, _, r)) -> Some (x, value d defs r) | _ -> None)
            | Let _ -> None)
          c.body
      in
      let vars = params @ [ ("state", state) ] @ receipts in
      List.iter
        (fun (t : Check.turn) ->
          match t.program, t.cost with
          | Some p, Some est ->
              let r = Cordis_cel.Eval.run ~vars p in
              Hegel.note tc
                (Printf.sprintf "cell %s turn %d\ninputs: %s\nvalue %s, cost %d, estimate [%d, %d]" ci.cname t.index
                   (String.concat ", " (List.map (fun (n, v) -> n ^ " = " ^ V.to_string v) vars))
                   (V.to_string r.value) r.cost est.min est.max);
              Hegel.require tc ~msg:"a turn cost more than its estimate" (r.cost <= est.max);
              if t.emits = None && not (V.is_error r.value) then begin
                (* the result, then the state updates *)
                let results =
                  match c.updates, r.value with
                  | [], v -> [ (ci.result, v) ]
                  | us, V.List vs when Array.length vs = 1 + List.length us ->
                      (ci.result, vs.(0)) :: List.mapi (fun i (f, _) -> (List.assoc f ci.state, vs.(i + 1))) us
                  | _, v -> [ (C.Dyn, v) ]
                in
                List.iter
                  (fun (ty, v) ->
                    if not (V.is_error v) then
                      Hegel.require tc ~msg:(Printf.sprintf "a result of type %s is %s" (C.to_string ty) (V.to_string v)) (admits ty v))
                  results
              end
          | _ -> ())
        ci.turns)
    r.cells

(* Files: the example and the accepted corpus, with the corpus library. *)
let files =
  let corpus = Sys.argv.(1) and example = Sys.argv.(2) in
  let lib = read (Filename.concat corpus "libs/text.cel") in
  let lib_hash = "sha256:" ^ Cordis_cel.Sha256.digest lib in
  let options = { Check.default_options with libraries = (fun h -> if h = lib_hash then Some lib else None) } in
  let oks =
    Array.to_list (Sys.readdir corpus)
    |> List.filter (fun f -> String.length f > 3 && String.sub f 0 3 = "ok_")
    |> List.map (fun f -> Filename.concat corpus f)
  in
  List.map
    (fun path ->
      let text = Str_replace.all ~sub:"LIBHASH" ~by:lib_hash (read path) in
      match Parse.file text with
      | Error m -> failwith (path ^ ": " ^ m)
      | Ok f -> (
          match Check.check ~options f with
          | Ok r -> (path, f, r)
          | Error es -> failwith (path ^ ": " ^ String.concat "; " es)))
    (example :: oks)

let test_corpus_cells () =
  run "corpus-cells" (fun tc ->
      let path, f, r = List.nth files ((Typed_gen.of_hegel tc).pick (List.length files)) in
      Hegel.note tc path;
      check_cells tc f r)

(* ---- generated cells ------------------------------------------------------------------------------- *)

let rec graph_ty (t : Typed_gen.ty) : ty =
  match t with
  | Int -> Scalar ("int", None) | Uint -> Scalar ("uint", None) | Double -> Scalar ("double", None)
  | Bool -> Scalar ("bool", None) | String -> Scalar ("string", Some 12) | Bytes -> Scalar ("bytes", Some 12)
  | List t -> List (graph_ty t, Some 5)
  | Map (k, v) -> Map (graph_ty k, graph_ty v, Some 5)

let test_generated_cells () =
  run "generated-cells" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      Typed_gen.extensions := true;
      let c = Typed_gen.case d in
      let params = String.concat ", " (List.map (fun (n, t, _) -> n ^ " : " ^ Print.ty (graph_ty t)) c.vars) in
      let text = Printf.sprintf "cell f(%s) : %s = {\n  %s;\n}\n" params (Print.ty (graph_ty c.ty)) c.src in
      Hegel.note tc text;
      match Parse.file text with
      | Error m -> Hegel.require tc ~msg:("a generated cell does not parse: " ^ m) false
      | Ok f -> (
          match Check.check ~options:{ Check.default_options with budget = max_int } f with
          | Error es -> Hegel.require tc ~msg:("a well-typed cell was rejected: " ^ String.concat "; " es) false
          | Ok r -> check_cells tc f r))

(* ---- records whose fields differ in size ------------------------------------------------------ *)

(* Each field's own bound must drive the estimate: values filled to their
   bounds, in expressions whose cost follows each field's size. *)
let test_record_shapes () =
  run "record-shapes" (fun tc ->
      let d = Typed_gen.of_hegel tc in
      let a = d.pick 41 and b = d.pick 41 and c = d.pick 9 and e = d.pick 41 in
      let text =
        Printf.sprintf "type R = { a : string<max %d>; b : list(string<max %d>)<max %d>; c : string<max %d> };\n" a b c e
      in
      let body =
        Typed_gen.choose d
          [ "x.b.fold(s, x.a, y, s + y + x.c).size()"; "size(x.a + x.c) + size(x.b)"; "x.b.filter(y, y.contains(x.a)).size()";
            "x.b.map(y, y + x.c).fold(n, 0, y, n + size(y))"; "x.b.exists(y, y.startsWith(x.c)) ? 1 : 0";
            "x.b.fold(n, 0, y, n + (y == x.a ? 1 : 0))"; "size(x.c + x.a + x.c)" ]
      in
      let text = text ^ Printf.sprintf "cell probe(x : R) : int = { %s; }\n" body in
      Hegel.note tc text;
      match Parse.file text with
      | Error m -> Hegel.require tc ~msg:("does not parse: " ^ m) false
      | Ok f -> (
          match Check.check ~options:{ Check.default_options with budget = max_int } f with
          | Error es -> Hegel.require tc ~msg:("rejected: " ^ String.concat "; " es) false
          | Ok r ->
              (* fill each field to its bound, with characters the predicates may match *)
              let fill n = V.String (String.init n (fun _ -> Typed_gen.choose d [ 'q'; 'q'; 'r' ])) in
              let x =
                V.make_map
                  [ (V.String "a", fill (if d.pick 2 = 0 then a else d.pick (a + 1)));
                    (V.String "b", V.List (Array.init c (fun _ -> fill b)));
                    (V.String "c", fill e) ]
              in
              let ci = List.hd r.cells in
              List.iter
                (fun (t : Check.turn) ->
                  match t.program, t.cost with
                  | Some p, Some est ->
                      let res = Cordis_cel.Eval.run ~vars:[ ("x", x); ("state", V.make_map []) ] p in
                      Hegel.note tc (Printf.sprintf "actual %d, estimate [%d, %d]" res.cost est.min est.max);
                      Hegel.require tc ~msg:"a turn cost more than its estimate" (res.cost <= est.max)
                  | _ -> ())
                ci.turns))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "graph-soundness"
    [ ( "cells",
        [ Alcotest.test_case "corpus cells: cost within the estimate, results of their type" `Quick test_corpus_cells;
          Alcotest.test_case "generated cells: accepted, cost within the estimate, results of their type" `Quick test_generated_cells;
          Alcotest.test_case "record fields of different sizes: each bound drives the estimate" `Quick test_record_shapes ] ) ]
