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
  []

let no_skip = ref false

(* The checker's environment from a test's declarations. *)
let checker_env container (decls : Yojson.Safe.t) =
  List.fold_left
    (fun env d ->
      match J.member "ident" d, J.member "function" d with
      | `String n, _ -> Checker.declare_ident env n (Checker.parse_type (J.to_string (J.member "type" d)))
      | _, `String f ->
          Checker.declare_function env f
            (List.map
               (fun o ->
                 ( J.to_bool (J.member "receiver" o),
                   List.map (fun p -> Checker.parse_type (J.to_string p)) (J.to_list (J.member "params" o)),
                   Checker.parse_type (J.to_string (J.member "result" o)) ))
               (J.to_list (J.member "overloads" d)))
      | _ -> env)
    (Checker.empty ~container ()) (J.to_list decls)

(* A test with an expected type: check it, and evaluate it when a value is
   expected too. *)
let run_typed t typed =
  let str k = J.to_string (J.member k t) in
  let container = str "container" in
  let want_type = J.to_string (J.member "type" typed) in
  match Cel.parse (str "expr") with
  | Error e -> Fail ("parse: " ^ e)
  | Ok ast -> (
      match Checker.check (checker_env container (J.member "type_env" t)) ast with
      | Error e -> Fail ("check: " ^ e)
      | Ok ty when Checker.to_string ty <> want_type ->
          Fail (Printf.sprintf "expected type %s, got %s" want_type (Checker.to_string ty))
      | Ok _ -> (
          match J.member "value" typed with
          | `Null -> Pass
          | v -> (
              match decode v, List.map (fun (k, v) -> (k, decode v)) (J.to_assoc (J.member "bindings" t)) with
              | exception Unsupported why -> Skip why
              | want, vars -> (
                  match Cel.eval ~vars ~container (str "expr") with
                  | Ok r when same want r.value -> Pass
                  | Ok r -> Fail (Printf.sprintf "expected %s, got %s" (Value.to_string want) (Value.to_string r.value))
                  | Error e -> Fail e))))

(* --check-all: type-check every test that evaluates to a value. The checker
   must accept it, and the checked type must admit the value's runtime type. *)
let check_all = ref false
let check_stats : (string, int * int * int) Hashtbl.t = Hashtbl.create 32 (* file -> ok, rejected, mismatched *)
let check_failures = ref []

let rec admits (ty : Checker.t) (v : Value.t) =
  match ty, v with
  | Checker.Dyn, _ -> true
  | _, (Value.Error _ | Value.Unknown _) -> true
  | (Checker.Wrapper _ | Checker.Message _ | Checker.Timestamp | Checker.Duration | Checker.Null), Value.Null -> true
  | Checker.Wrapper t, v -> admits t v
  | Checker.Bool, Value.Bool _ | Checker.Int, Value.Int _ | Checker.Uint, Value.Uint _
  | Checker.Double, Value.Double _ | Checker.String, Value.String _ | Checker.Bytes, Value.Bytes _
  | Checker.Timestamp, Value.Timestamp _ | Checker.Duration, Value.Duration _ | Checker.Type _, Value.Type _ -> true
  | Checker.Int, Value.Enum _ -> true
  | Checker.List t, Value.List xs -> Array.for_all (admits t) xs
  | Checker.Map (k, w), Value.Map m -> List.for_all (fun (a, b) -> admits k a && admits w b) m
  | Checker.Opt t, Value.Optional o -> (match o with None -> true | Some x -> admits t x)
  | Checker.Message n, Value.Message { tname; _ } -> n = tname
  | Checker.Opaque (n, _), Value.Opaque { tname; _ } -> n = tname
  | Checker.Opaque (n, _), Value.Enum { tname; _ } -> n = tname
  | _ -> false

let type_of_binding (v : Value.t) =
  match v with
  | Value.Int _ -> Checker.Int | Value.Uint _ -> Checker.Uint | Value.Double _ -> Checker.Double
  | Value.String _ -> Checker.String | Value.Bytes _ -> Checker.Bytes | Value.Bool _ -> Checker.Bool
  | Value.Null -> Checker.Null | Value.Timestamp _ -> Checker.Timestamp | Value.Duration _ -> Checker.Duration
  | Value.Message { tname; _ } -> Checker.message_type tname
  | _ -> Checker.Dyn

let record file outcome =
  let ok, rej, mis = Option.value (Hashtbl.find_opt check_stats file) ~default:(0, 0, 0) in
  Hashtbl.replace check_stats file
    (match outcome with `Ok -> (ok + 1, rej, mis) | `Rejected -> (ok, rej + 1, mis) | `Mismatch -> (ok, rej, mis + 1))

(* The largest size anywhere in a value: the input bound the estimator is
   given for a binding. *)
let rec max_size (v : Value.t) =
  match v with
  | Value.String s -> Eval.utf8_length s
  | Value.Bytes b -> String.length b
  | Value.List xs -> Array.fold_left (fun a x -> max a (max_size x)) (Array.length xs) xs
  | Value.Map m -> List.fold_left (fun a (k, x) -> max a (max (max_size k) (max_size x))) (List.length m) m
  | Value.Message { fields; _ } -> List.fold_left (fun a (_, x) -> max a (max_size x)) 1 fields
  | Value.Optional (Some x) -> max_size x
  | _ -> 1

let cost_failures = ref 0

let check_consistency file t (value : Value.t) cost =
  let str k = J.to_string (J.member k t) in
  match Cel.parse ~macros:(not (J.to_bool (J.member "disable_macros" t))) (str "expr") with
  | Error _ -> ()
  | Ok ast -> (
      let env = checker_env (str "container") (J.member "type_env" t) in
      let env =
        List.fold_left
          (fun env (k, v) ->
            if List.mem_assoc k env.Checker.idents then env
            else match decode v with v -> Checker.declare_ident env k (type_of_binding v) | exception _ -> env)
          env (J.to_assoc (J.member "bindings" t))
      in
      match Checker.check env ast with
      | Error e -> record file `Rejected; check_failures := (file, str "expr", "rejected: " ^ e) :: !check_failures
      | Ok ty ->
          (* cost soundness: the actual cost is within the static estimate *)
          let sizes =
            List.filter_map
              (fun (k, v) -> match decode v with v -> Some (k, max 1 (max_size v)) | exception _ -> None)
              (J.to_assoc (J.member "bindings" t))
          in
          (match Cost.estimate ~sizes env ast with
           | Ok est when cost > est.max ->
               incr cost_failures;
               check_failures :=
                 (file, str "expr", Printf.sprintf "actual cost %d exceeds the estimate [%d, %d]" cost est.min est.max)
                 :: !check_failures
           | _ -> ());
          if admits ty value then record file `Ok
          else (
            record file `Mismatch;
            check_failures := (file, str "expr", Printf.sprintf "type %s does not admit %s" (Checker.to_string ty) (Value.to_string value)) :: !check_failures))

let run_test file section (t : Yojson.Safe.t) =
  (* cel-spec's enums file has a legacy (ints) and a strong (typed) half *)
  Proto.set_strong_enums (String.length section >= 6 && String.sub section 0 6 = "strong");
  let str k = J.to_string (J.member k t) in
  let expr = str "expr" in
  match (if !no_skip then None else List.assoc_opt file file_skips), J.member "typed_result" t with
  | Some why, _ -> Skip why
  | None, (`Assoc _ as typed) -> run_typed t typed
  | None, _ -> (
      if J.to_bool (J.member "check_only" t) then Skip "check only (type checker, RFC 0001 step 3)"
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
                let got, cost =
                  match Cel.eval ~vars ~macros ~container:(str "container") expr with
                  | Ok r -> (r.value, r.cost)
                  | Error e -> (Value.Error e, 0)
                in
                (match want, got with
                 | Some w, g
                   when !check_all && same w g && (not (Value.is_error g)) && not (J.to_bool (J.member "disable_check" t)) ->
                     check_consistency file t g cost
                 | _ -> ());
                match want, got with
                | None, Value.Error _ -> Pass
                | None, v -> Fail ("expected an error, got " ^ Value.to_string v)
                | Some w, g -> if same w g then Pass else Fail (Printf.sprintf "expected %s, got %s" (Value.to_string w) (Value.to_string g)))))

let () =
  let dir = ref "" and verbose = ref false and only = ref "" and fail_on_fail = ref false in
  let descriptor_set = ref "" in
  Arg.parse
    [ ("--verbose", Arg.Set verbose, "print every failure");
      ("--file", Arg.Set_string only, "run one file");
      ("--fail-on-fail", Arg.Set fail_on_fail, "exit 1 on any failure");
      ("--no-skip", Arg.Set no_skip, "run the files skipped as a whole, to see how they fail");
      ("--check-all", Arg.Set check_all, "type-check every test that evaluates to a value");
      ( "--descriptor-set",
        Arg.Set_string descriptor_set,
        "read the protos from a binary FileDescriptorSet (a registry, RFC 0004) instead of descriptors.json" ) ]
    (fun d -> dir := d)
    "conformance.exe DIR";
  if !descriptor_set = "" then Proto.load (Filename.concat (Filename.dirname !dir) "descriptors.json")
  else begin
    match Proto.of_descriptor_sets [ In_channel.with_open_bin !descriptor_set In_channel.input_all ] with
    | Ok r -> Domain.DLS.set Proto.key r (* for the rest of the run *)
    | Error m -> prerr_endline m; exit 2
  end;
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
                match run_test name (J.to_string (J.member "name" sec)) t with
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
  if !check_all then begin
    Printf.printf "\ntype-checking tests that evaluate to a value (accepted and consistent / rejected / inconsistent):\n";
    Hashtbl.fold (fun k v acc -> (k, v) :: acc) check_stats [] |> List.sort compare
    |> List.iter (fun (f, (ok, rej, mis)) -> Printf.printf "  %-16s %5d %5d %5d\n" f ok rej mis);
    Printf.printf "  actual cost above the static estimate: %d\n" !cost_failures;
    if !verbose then
      List.iter (fun (f, e, why) -> Printf.printf "  CHECK %s: %s\n    %s\n" f e why) (List.rev !check_failures)
  end;
  if !fail_on_fail && !total_f > 0 then exit 1
