(* Run cel-spec's simple conformance tests (cordis/cel/conformance/simple)
   against the evaluator. Every test is pass, fail or skip; a skip carries
   its reason and is never counted as a pass (docs/cordis/rfc/0001-cel.md,
   "Testing").

     conformance.exe DIR [--verbose] [--file NAME] [--fail-on-fail] *)

open Cordis_cel
module J = Yojson.Safe.Util

open Conformance_values

(* ---- running ------------------------------------------------------------------- *)

type outcome = Pass | Fail of string | Skip of string

(* Whole files that exercise libraries this implementation does not have yet. *)
let file_skips =
  [ ("timestamps", "timestamps and durations (RFC 0001 step 5)");
    ("type_deduction", "type checker (RFC 0001 step 3)");
    ("optionals", "optional types");
    ("proto2", "protobuf messages (out of scope)");
    ("proto2_ext", "protobuf messages (out of scope)");
    ("proto3", "protobuf messages (out of scope)");
    ("enums", "protobuf enums (out of scope)");
    ("wrappers", "protobuf wrapper types (out of scope)");
    ("namespace", "containers") ]

let no_skip = ref false

let run_test file (t : Yojson.Safe.t) =
  let str k = J.to_string (J.member k t) in
  let expr = str "expr" in
  match (if !no_skip then None else List.assoc_opt file file_skips) with
  | Some why -> Skip why
  | None -> (
      if J.to_bool (J.member "check_only" t) then Skip "check only (type checker, RFC 0001 step 3)"
      else if str "container" <> "" then Skip "containers"
      else if mentions expr "timestamp(" || mentions expr "duration(" then
        Skip "timestamps and durations (RFC 0001 step 5)"
      else
        let expect = J.member "expect" t in
        match expect with
        | `Assoc [ ("unknown", _) ] -> Skip "unknowns"
        | `Assoc [ ("any_value", _) ] -> Skip "any value (type checker)"
        | _ -> (
            match
              ( List.map (fun (k, v) -> (k, decode v)) (J.to_assoc (J.member "bindings" t)),
                match expect with `Assoc [ ("error", _) ] -> None | v -> Some (decode v) )
            with
            | exception Unsupported why -> Skip why
            | vars, want -> (
                let macros = not (J.to_bool (J.member "disable_macros" t)) in
                let got =
                  match Cel.eval ~vars ~macros expr with
                  | Ok r -> r.value
                  | Error e -> Value.Error e
                in
                match want, got with
                | _, Value.Error e when mentions e "message construction is not supported" ->
                    Skip "protobuf messages (out of scope)"
                | _, Value.Error e when mentions e "optional" && mentions e "not supported" ->
                    Skip "optional types"
                | None, Value.Error _ -> Pass
                | None, v -> Fail ("expected an error, got " ^ Value.to_string v)
                | Some w, g -> if same w g then Pass else Fail (Printf.sprintf "expected %s, got %s" (Value.to_string w) (Value.to_string g)))))

let () =
  let dir = ref "" and verbose = ref false and only = ref "" and fail_on_fail = ref false in
  Arg.parse
    [ ("--verbose", Arg.Set verbose, "print every failure");
      ("--file", Arg.Set_string only, "run one file");
      ("--fail-on-fail", Arg.Set fail_on_fail, "exit 1 on any failure");
      ("--no-skip", Arg.Set no_skip, "run the files skipped as a whole, to see how they fail") ]
    (fun d -> dir := d)
    "conformance.exe DIR";
  let files =
    Sys.readdir !dir |> Array.to_list |> List.filter (fun f -> Filename.check_suffix f ".json") |> List.sort compare
  in
  let total_p = ref 0 and total_f = ref 0 and total_s = ref 0 in
  let reasons = Hashtbl.create 16 in
  Printf.printf "%-16s %6s %6s %6s\n" "file" "pass" "fail" "skip";
  List.iter
    (fun f ->
      let name = Filename.chop_suffix f ".json" in
      if !only = "" || !only = name then begin
        let j = Yojson.Safe.from_file (Filename.concat !dir f) in
        let p = ref 0 and fl = ref 0 and s = ref 0 in
        List.iter
          (fun sec ->
            List.iter
              (fun t ->
                match run_test name t with
                | Pass -> incr p
                | Skip why ->
                    incr s;
                    Hashtbl.replace reasons why (1 + Option.value (Hashtbl.find_opt reasons why) ~default:0)
                | Fail why ->
                    incr fl;
                    if !verbose then
                      Printf.printf "  FAIL %s/%s/%s: %s\n    %s\n" name (J.to_string (J.member "name" sec))
                        (J.to_string (J.member "name" t)) (J.to_string (J.member "expr" t)) why)
              (J.to_list (J.member "tests" sec)))
          (J.to_list (J.member "sections" j));
        Printf.printf "%-16s %6d %6d %6d\n" name !p !fl !s;
        total_p := !total_p + !p; total_f := !total_f + !fl; total_s := !total_s + !s
      end)
    files;
  Printf.printf "%-16s %6d %6d %6d\n" "total" !total_p !total_f !total_s;
  Printf.printf "\nskipped, by reason:\n";
  Hashtbl.fold (fun k v acc -> (k, v) :: acc) reasons []
  |> List.sort (fun (_, a) (_, b) -> compare b a)
  |> List.iter (fun (k, v) -> Printf.printf "  %5d  %s\n" v k);
  if !fail_on_fail && !total_f > 0 then exit 1
