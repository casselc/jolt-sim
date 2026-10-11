(* Agreement with protobuf itself (docs/cordis/rfc/0003-types-importers-
   scaffolds.md, "Testing"). For each imported message, values are drawn
   from the importer's types (32-bit fields sometimes out of range), encoded
   by the codec, and handed to protobuf's own JSON parser (pbcheck.py):

   - protobuf accepts a value exactly when the residue (check_T) does;
   - what protobuf prints back for an accepted value decodes to the same
     value;
   - the codec reads back what it writes.

     agreement.exe SET.binpb PBCHECK.py *)

open Cordis_import
module V = Cordis_cel.Value

let settings =
  let cases = match Sys.getenv_opt "IMPORT_PROPS_CASES" with Some n -> int_of_string n | None -> 300 in
  Hegel.Settings.create ~test_cases:cases ~seed:17 ()

let set_path = Sys.argv.(1)
let pbcheck = Sys.argv.(2)

let imp =
  match Descriptor.set_of_string (In_channel.with_open_bin set_path In_channel.input_all) with
  | Ok files -> Protobuf.import files
  | Error m -> failwith m

let checked =
  match
    Cordis_graph.Patch.load
      ~options:{ Cordis_graph.Check.default_options with budget = max_int }
      (Cordis_graph.Print.file (Protobuf.types_file imp))
  with
  | Ok s -> s.checked
  | Error es -> failwith (String.concat "; " es)

(* protobuf's parser, as a long-lived process *)
let proc = lazy (Unix.open_process_args "python3" [| "python3"; pbcheck; set_path |])

let protobuf_parse full json =
  let ic, oc = Lazy.force proc in
  output_string oc (Yojson.Safe.to_string (`Assoc [ ("type", `String full); ("json", `String json) ]) ^ "\n");
  flush oc;
  match Yojson.Safe.from_string (input_line ic) with
  | `Assoc kv -> (
      match List.assoc_opt "ok" kv, List.assoc_opt "json" kv, List.assoc_opt "error" kv with
      | Some (`Bool true), Some (`String j), _ -> Ok j
      | _, _, Some (`String e) -> Error e
      | _ -> Error "?")
  | _ -> Error "?"

(* ---- values of the importer's types ---------------------------------------------------------- *)

(* [inject]: this case may put 32-bit fields out of range. Most cases do
   not, so a large message is often entirely in range and everything else
   about it is tested; values are drawn at the boundaries, both sides. *)
let rec gen ?(inject = false) ?(deep = false) ?(path = []) pick (t : Protobuf.t) depth : V.t =
  let choose l = List.nth l (pick (List.length l)) in
  (* beneath a recursive edge (a type already on the path) the residue does not look, by design: the
     codec checks there, so values stay within range *)
  let path, inject = match t with Protobuf.Named n -> (n :: path, inject && (deep || not (List.mem n path))) | _ -> (path, inject) in
  let g t = gen ~inject ~deep ~path pick t (depth - 1) in
  let out = inject && pick 3 = 0 in
  match t with
  | Protobuf.I32 ->
      V.Int
        (if out then choose [ 2147483648L; -2147483649L; 4294967296L ]
         else if pick 4 = 0 then choose [ 2147483647L; -2147483648L; 0L ]
         else Int64.of_int (pick 2001 - 1000))
  | U32 ->
      V.Uint
        (if out then choose [ 4294967296L; 4294967297L ]
         else if pick 4 = 0 then choose [ 4294967295L; 0L ]
         else Int64.of_int (pick 2001))
  | I64 -> V.Int (choose [ 0L; 1L; -7L; 1099511627776L; -9223372036854775808L; 9223372036854775807L ])
  | U64 -> V.Uint (choose [ 0L; 3L; 1099511627776L; -1L (* 2^64 - 1 *) ])
  | F32 -> V.Double (float_of_int (pick 201 - 100) /. 8.)
  | F64 -> V.Double (float_of_int (pick 2001 - 1000) /. 16.)
  | Bool -> V.Bool (pick 2 = 0)
  | Str -> V.String (choose [ ""; "a"; "héllo"; "tab\there"; "quote\"d" ])
  | Bytes -> V.Bytes (String.init (pick 5) (fun _ -> Char.chr (pick 256)))
  | Ts -> Cordis_cel.Eval.call_function "timestamp" [ V.Int (Int64.of_int (1700000000 + pick 100000)) ]
  | Dur -> Cordis_cel.Eval.call_function "duration" [ V.String (choose [ "0s"; "1.5s"; "-3s"; "3600s" ]) ]
  | Dyn -> choose [ V.Null; V.Bool true; V.Double 2.5; V.String "x" ]
  | Struct -> V.make_map (List.init (pick 3) (fun i -> (V.String (Printf.sprintf "k%d" i), choose [ V.Null; V.Double 1.; V.String "v" ])))
  | ListV -> V.List (Array.init (pick 3) (fun _ -> choose [ V.Double 1.; V.String "v"; V.Bool false ]))
  | Empty -> V.make_map []
  (* beneath a recursive reference: a value of the target type, in the
     JSON-like form a dyn holds, within range (the residue does not reach
     here, by design) *)
  | Rec n ->
      if depth <= 0 then V.make_map []
      else
        let typed = gen pick (Named n) (depth - 1) in
        Codec.json_dyn (Codec.to_json imp (Named n) typed)
  | Wrap t | Opt t -> if depth <= 0 || pick 2 = 0 then V.Optional None else V.Optional (Some (g t))
  | Lst t -> V.List (Array.init (if depth <= 0 then 0 else pick 4) (fun _ -> g t))
  | Mp (k, v) ->
      let keys = List.sort_uniq compare (List.init (if depth <= 0 then 0 else pick 4) (fun _ -> g k)) in
      V.make_map (List.map (fun key -> (key, g v)) keys)
  | Named n -> (
      match List.assoc_opt n imp.defs with
      | Some (Protobuf.Enum { values; _ }) -> V.make_map [ (V.String "tag", V.String (fst (choose values))) ]
      | Some (Protobuf.Oneof { cases; _ }) ->
          let c, f = choose cases in
          V.make_map [ (V.String "tag", V.String c); (V.String "value", g f.ty) ]
      | Some (Protobuf.Message { fields; _ }) -> V.make_map (List.map (fun (f : Protobuf.rfield) -> (V.String f.name, g f.ty)) fields)
      | None -> V.Null)

(* the residue's verdict: true when the type has no check_ cell *)
let residue name (v : V.t) =
  match List.find_opt (fun (c : Cordis_graph.Check.cell_info) -> c.cname = "check_" ^ name) checked.cells with
  | None -> Ok true
  | Some c -> (
      match (List.hd c.turns).program with
      | None -> Error "a pending check"
      | Some p -> (
          match (Cordis_cel.Eval.run ~vars:[ ("x", v); ("state", V.make_map []) ] p).value with
          | V.Bool b -> Ok b
          | e -> Error (V.to_string e)))

(* a message with a required reference back (dyn, Rec) has no instance protobuf accepts: its importing
   is the golden report's to show *)
let messages =
  let required_back = function Protobuf.Rec _ -> true | _ -> false in
  List.filter_map
    (fun (g, d) ->
      match d with
      | Protobuf.Message { full; fields; _ } when not (List.exists (fun (f : Protobuf.rfield) -> required_back f.ty) fields) -> Some (g, full)
      | _ -> None)
    imp.defs

let test_agreement () =
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:protobuf" (fun tc ->
      let pick n = (Typed_gen.of_hegel tc).pick n in
      let name, full = List.nth messages (pick (List.length messages)) in
      let v = gen ~inject:(pick 4 = 0) pick (Protobuf.Named name) 6 in
      let json = Codec.to_json_string imp name v in
      Hegel.note tc (Printf.sprintf "%s\nvalue: %s\njson: %s" full (V.to_string v) json);
      (* the codec reads back what it writes *)
      (match Codec.of_json_string imp name json with
       | Ok w -> Hegel.require tc ~msg:"the codec does not read back what it wrote" (V.equal v w || not (Result.value (residue name v) ~default:true))
       | Error e -> Hegel.note tc ("codec: " ^ e); Hegel.require tc ~msg:"the codec refused its own output" (not (Result.value (residue name v) ~default:true)));
      let pb = protobuf_parse (String.sub full 1 (String.length full - 1)) json in
      let res = residue name v in
      Hegel.note tc (Printf.sprintf "protobuf: %s; residue: %s"
                       (match pb with Ok j -> "accepted " ^ j | Error e -> "rejected: " ^ e)
                       (match res with Ok b -> string_of_bool b | Error e -> "error " ^ e));
      match res with
      | Error e -> Hegel.require tc ~msg:("the residue did not evaluate: " ^ e) false
      | Ok accepted -> (
          Hegel.require tc ~msg:"protobuf and the residue disagree" (Result.is_ok pb = accepted);
          match pb with
          | Ok printed -> (
              match Codec.of_json_string imp name printed with
              | Ok w -> Hegel.require tc ~msg:"what protobuf printed decodes to another value" (V.equal v w)
              | Error e -> Hegel.require tc ~msg:("protobuf's output does not decode: " ^ e) false)
          | Error _ -> ()))

(* The recursive messages (in a cycle of references): out-of-range values drawn at every level, where
   the residue stops at the first recursive edge; the codec, which recurses, refuses exactly what
   protobuf's parser refuses (RFC 0002 revision 3, "Residue and boundaries") *)
let recursive =
  let rec refs = function Protobuf.Named n -> [ n ] | Opt t | Lst t | Wrap t -> refs t | Mp (k, v) -> refs k @ refs v | _ -> [] in
  let edges g =
    match List.assoc_opt g imp.defs with
    | Some (Protobuf.Message { fields; _ }) -> List.concat_map (fun (f : Protobuf.rfield) -> refs f.ty) fields
    | Some (Protobuf.Oneof { cases; _ }) -> List.concat_map (fun (_, (f : Protobuf.rfield)) -> refs f.ty) cases
    | _ -> []
  in
  let reaches a b =
    let seen = Hashtbl.create 8 in
    let rec go n = List.exists (fun m -> m = b || ((not (Hashtbl.mem seen m)) && (Hashtbl.replace seen m (); go m))) (edges n) in
    go a
  in
  List.filter (fun (g, _) -> reaches g g) messages

let test_recursive () =
  let counts = ref (0, 0) in
  Fun.protect ~finally:(fun () -> Printf.printf "%d recursive values both accept, %d both refuse\n" (fst !counts) (snd !counts)) @@ fun () ->
  Alcotest.(check bool) "recursive messages to draw" true (List.length recursive >= 3);
  Hegel.run_hegel_test ~settings ~database_key:"cordis:import:protobuf-recursive" (fun tc ->
      let pick n = (Typed_gen.of_hegel tc).pick n in
      let name, full = List.nth recursive (pick (List.length recursive)) in
      let v = gen ~inject:true ~deep:true pick (Protobuf.Named name) 8 in
      let json = Codec.to_json_string imp name v in
      Hegel.note tc (Printf.sprintf "%s\njson: %s" full json);
      let pb = protobuf_parse (String.sub full 1 (String.length full - 1)) json in
      let ours = Codec.of_json_string imp name json in
      Hegel.note tc (Printf.sprintf "protobuf: %s; codec: %s" (match pb with Ok _ -> "accepted" | Error e -> e) (match ours with Ok _ -> "accepted" | Error e -> e));
      Hegel.require tc ~msg:"the codec and protobuf's parser disagree" (Result.is_ok pb = Result.is_ok ours);
      counts := if Result.is_ok pb then (fst !counts + 1, snd !counts) else (fst !counts, snd !counts + 1))

let () =
  Alcotest.run ~argv:[| Sys.argv.(0) |] "import-agreement"
    [ ("protobuf", [ Alcotest.test_case "protobuf's parser and the residue agree; values round-trip" `Quick test_agreement;
                      Alcotest.test_case "recursive messages: the codec refuses at every level what protobuf does" `Quick test_recursive ]) ]
