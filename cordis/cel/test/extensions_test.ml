(* The harness extensions (docs/cordis/rfc/0001-cel.md, "Extensions",
   step 6): fold and fold_while, match with its exhaustiveness errors,
   function libraries and their inlining, and the SHA-256 that names
   libraries. Example-based; the properties are in props.ml. *)

open Cordis_cel

let ty = Checker.parse_type

let env =
  List.fold_left
    (fun env (n, t) -> Checker.declare_ident env n (ty t))
    (Checker.empty ())
    [ ("xs", "list(int)"); ("names", "list(string)"); ("m", "map(string, int)");
      ("r", "variant(Pass(string), Fail(int), Skipped)"); ("o", "optional_type(int)"); ("d", "dyn"); ("match", "int") ]

let variant tag value =
  Value.make_map ((Value.String "tag", Value.String tag) :: (match value with Some v -> [ (Value.String "value", v) ] | None -> []))

let ints l = Value.List (Array.of_list (List.map (fun i -> Value.Int (Int64.of_int i)) l))

let vars ?(r = variant "Fail" (Some (Value.Int 3L))) ?(o = Value.Optional (Some (Value.Int 4L))) ?(d = Value.Null) () =
  [ ("xs", ints [ 1; 2; 3 ]); ("names", Value.List [| Value.String "ada"; Value.String "bo" |]);
    ("m", Value.make_map [ (Value.String "a", Value.Int 1L); (Value.String "b", Value.Int 2L) ]); ("r", r); ("o", o);
    ("d", d); ("match", Value.Int 10L) ]

let eval ?(vars = vars ()) src =
  match Cel.eval ~vars src with Ok r -> r.value | Error e -> Value.Error e

let check src = match Cel.parse src with Error e -> Error e | Ok ast -> Result.map Checker.to_string (Checker.check env ast)

let value = Alcotest.testable (fun f v -> Format.pp_print_string f (Value.to_string v)) Value.equal

let evaluates ?vars src want () = Alcotest.check value src want (eval ?vars src)
let has_type src want () = Alcotest.(check (result string string)) src (Ok want) (check src)

let rejected src fragment () =
  match check src with
  | Ok t -> Alcotest.failf "%s: accepted as %s" src t
  | Error e ->
      let mentions =
        let n = String.length fragment in
        let rec go i = i + n <= String.length e && (String.sub e i n = fragment || go (i + 1)) in
        go 0
      in
      if not mentions then Alcotest.failf "%s: error %S does not mention %S" src e fragment

let is_error src () = if not (Value.is_error (eval src)) then Alcotest.failf "%s: not an error" src

(* ---- fold ------------------------------------------------------------------------------------- *)

let fold_tests =
  [ ("sum", `Quick, evaluates "xs.fold(acc, 0, x, acc + x)" (Value.Int 6L));
    ("string accumulator", `Quick, evaluates "xs.fold(s, '', x, s + string(x))" (Value.String "123"));
    ("reverse", `Quick, evaluates "xs.fold(acc, [], x, [x] + acc)" (ints [ 3; 2; 1 ]));
    ("index and value", `Quick, evaluates "xs.fold(acc, 0, i, x, acc + i * x)" (Value.Int 8L));
    ("map keys", `Quick, evaluates "m.fold(acc, '', k, acc + k)" (Value.String "ab"));
    ("map keys and values", `Quick, evaluates "m.fold(acc, 0, k, v, acc + v)" (Value.Int 3L));
    ("empty range", `Quick, evaluates "[].fold(acc, 7, x, acc + x)" (Value.Int 7L));
    ("fold_while stops", `Quick, evaluates "xs.fold_while(acc, 0, x, acc < 3, acc + x)" (Value.Int 3L));
    ("fold_while with index", `Quick, evaluates "xs.fold_while(acc, 0, i, x, i < 2, acc + x)" (Value.Int 3L));
    ("fold type", `Quick, has_type "names.fold(acc, 0, n, acc + size(n))" "int");
    ("fold list type", `Quick, has_type "xs.fold(acc, [], x, acc + [string(x)])" "list(string)");
    ("fold step must fit the accumulator", `Quick, rejected "xs.fold(acc, 0, x, acc + 'a')" "no matching overload");
    ("fold needs identifiers", `Quick, rejected "xs.fold(1, 0, x, x)" "fold");
    ("an error in the step propagates", `Quick, is_error "xs.fold(acc, 0, x, acc / (x - 2))") ]

(* ---- match ------------------------------------------------------------------------------------ *)

let match_tests =
  let full = "match r { Pass(s) -> size(s); Fail(n) -> n; Skipped -> 0 }" in
  [ ("a case with a payload", `Quick, evaluates full (Value.Int 3L));
    ("a unit case", `Quick, evaluates ~vars:(vars ~r:(variant "Skipped" None) ()) full (Value.Int 0L));
    ("the binder has the case's type", `Quick, has_type full "int");
    ("a wildcard", `Quick, evaluates "match r { Pass(s) -> 1; _ -> 2 }" (Value.Int 2L));
    ("commas separate cases too", `Quick, evaluates "match r { Pass(s) -> 1, Fail(n) -> n, Skipped -> 0 }" (Value.Int 3L));
    ("optionals: Some", `Quick, evaluates "match o { Some(x) -> x * 2; None -> 0 }" (Value.Int 8L));
    ( "optionals: None", `Quick,
      evaluates ~vars:(vars ~o:(Value.Optional None) ()) "match o { Some(x) -> x * 2; None -> 0 }" (Value.Int 0L) );
    ("optional binder type", `Quick, has_type "match o { Some(x) -> x; None -> 0 }" "int");
    ("a variant written as a map", `Quick, evaluates "match {'tag': 'Pass', 'value': 'ok'} { Pass(s) -> s; _ -> 'x' }" (Value.String "ok"));
    ("nested matches", `Quick, evaluates "match r { Fail(n) -> match o { Some(x) -> n + x; None -> n }; _ -> 0 }" (Value.Int 7L));
    ("the scrutinee is evaluated once", `Quick, evaluates "match [r][0] { Pass(s) -> 1; Fail(n) -> n; Skipped -> 0 }" (Value.Int 3L));
    ("a binder shadows", `Quick, evaluates "xs.map(x, match r { Fail(x) -> x; _ -> 0 })" (ints [ 3; 3; 3 ]));
    ("match is still an identifier", `Quick, evaluates "match + 1" (Value.Int 11L));
    ("match(...) is still a call", `Quick, has_type "[match].map(match, match - 1)" "list(int)");
    (* exhaustiveness and the cases' names, checked against the variant type *)
    ("missing case", `Quick, rejected "match r { Pass(s) -> 1; Fail(n) -> n }" "not exhaustive: no case for Skipped");
    ("missing cases", `Quick, rejected "match r { Pass(s) -> 1 }" "no case for Fail, Skipped");
    ("missing None", `Quick, rejected "match o { Some(x) -> x }" "no case for None");
    ("unknown case", `Quick, rejected "match r { Pass(s) -> 1; Nope -> 2; _ -> 3 }" "no case 'Nope'");
    ("binding a unit case", `Quick, rejected "match r { Skipped(v) -> v; _ -> 0 }" "carries no value");
    ("branch types must agree", `Quick, rejected "match r { Pass(s) -> s; Fail(n) -> n; Skipped -> 0 }" "no matching overload");
    ("duplicate case", `Quick, rejected "match r { Pass(s) -> 1; Pass(t) -> 2; _ -> 3 }" "duplicate match case");
    ("wildcard not last", `Quick, rejected "match r { _ -> 1; Pass(s) -> 2 }" "wildcard");
    ("empty match", `Quick, rejected "match r { }" "at least one case");
    (* a dyn scrutinee is checked when it runs *)
    ("dyn: no case at runtime", `Quick, (fun () ->
         let v = eval ~vars:(vars ~d:(variant "Other" None) ()) "match d { Pass(s) -> 1 }" in
         if not (Value.is_error v) then Alcotest.fail "expected an error"));
    ("dyn: not a variant", `Quick, (fun () ->
         let v = eval ~vars:(vars ~d:(Value.Int 1L) ()) "match d { Pass(s) -> 1; _ -> 2 }" in
         if not (Value.is_error v) then Alcotest.fail "expected an error")) ]

(* ---- libraries -------------------------------------------------------------------------------- *)

let lib_text =
  {|// a test library
greet(name: string): string = 'hello, ' + name;
shout(name: string): string = greet(name).upperAscii();
total(xs: list(int)): int = xs.fold(acc, 0, x, acc + x);
first(xs: list(int), d: int): int = size(xs) > 0 ? xs[0] : d;
label(r: variant(Pass(string), Fail(int))): string = match r { Pass(s) -> s; Fail(n) -> string(n) };
shadow(x: int): list(int) = [1, 2].map(x, x * 10);    // the parameter is shadowed
pair(a: int, b: int): list(int) = [a, b];
quoted(s: string): string = s + ';' + "a;b" + r'\';
|}

let lib () = match Library.parse lib_text with Ok l -> l | Error e -> Alcotest.failf "library: %s" e

let run_lib ?(max_nodes = 10_000) src =
  match Cel.parse src with
  | Error e -> Error e
  | Ok ast -> (
      match Library.compile ~max_nodes env [ ("lib", lib ()) ] ast with
      | Error e -> Error e
      | Ok (_, inlined) -> Ok (Eval.run ~vars:(vars ()) inlined).value)

let lib_evaluates src want () =
  match run_lib src with Ok v -> Alcotest.check value src want v | Error e -> Alcotest.failf "%s: %s" src e

let lib_rejected text fragment () =
  match Library.parse text with
  | Ok _ -> Alcotest.failf "accepted: %s" text
  | Error e ->
      let n = String.length fragment in
      let rec go i = i + n <= String.length e && (String.sub e i n = fragment || go (i + 1)) in
      if not (go 0) then Alcotest.failf "error %S does not mention %S" e fragment

let call_rejected ?max_nodes src fragment () =
  match run_lib ?max_nodes src with
  | Ok v -> Alcotest.failf "%s: accepted, %s" src (Value.to_string v)
  | Error e ->
      let n = String.length fragment in
      let rec go i = i + n <= String.length e && (String.sub e i n = fragment || go (i + 1)) in
      if not (go 0) then Alcotest.failf "error %S does not mention %S" e fragment

let library_tests =
  [ ("a call", `Quick, lib_evaluates "lib.greet('ada')" (Value.String "hello, ada"));
    ("a sibling call", `Quick, lib_evaluates "lib.shout('ada')" (Value.String "HELLO, ADA"));
    ("over an input", `Quick, lib_evaluates "lib.total(xs) * 2" (Value.Int 12L));
    ("two parameters", `Quick, lib_evaluates "lib.first([], 9) + lib.first(xs, 9)" (Value.Int 10L));
    ("a variant parameter", `Quick, lib_evaluates "lib.label({'tag': 'Fail', 'value': 3})" (Value.String "3"));
    ("a variant of other cases", `Quick, call_rejected "lib.label(r)" "no matching overload");
    ("a parameter shadowed in the body", `Quick, lib_evaluates "lib.shadow(5)" (ints [ 10; 20 ]));
    ("';' inside strings", `Quick, lib_evaluates "lib.quoted('x')" (Value.String "x;a;b\\"));
    (* arguments are bound to fresh names: a caller's variable named like a
       parameter is not captured, and each argument is evaluated once *)
    ("no capture", `Quick, lib_evaluates "names.map(name, lib.greet(name))" (Value.List [| Value.String "hello, ada"; Value.String "hello, bo" |]));
    ("arguments swapped", `Quick, lib_evaluates "[1].map(a, [2].map(b, lib.pair(b, a)))[0][0]" (ints [ 2; 1 ]));
    ("no capture of a later parameter", `Quick, lib_evaluates "[1].map(d, lib.first([], d))" (ints [ 1 ]));
    ("an alias shadowed by a variable", `Quick, lib_evaluates "[[1]].map(lib, lib.size())" (ints [ 1 ]));
    ("nested library calls", `Quick, lib_evaluates "lib.greet(lib.shout('x'))" (Value.String "hello, HELLO, X"));
    ("an argument error propagates", `Quick, (fun () ->
         match run_lib "lib.first([1], 1 / 0)" with
         | Ok v when Value.is_error v -> ()
         | Ok v -> Alcotest.failf "expected an error, got %s" (Value.to_string v)
         | Error e -> Alcotest.fail e));
    (* checked against the declared signature, not the body *)
    ("argument type", `Quick, call_rejected "lib.greet(1)" "no matching overload");
    ("arity", `Quick, call_rejected "lib.greet('a', 'b')" "no matching overload");
    ("unknown function", `Quick, call_rejected "lib.nope(1)" "has no function 'nope'");
    ("result type", `Quick, call_rejected "lib.greet('a') + 1" "no matching overload");
    ("inlined size bound", `Quick, call_rejected ~max_nodes:20 "lib.shout(lib.shout('a'))" "over the bound");
    (* admission of a library *)
    ("recursion", `Quick, lib_rejected "f(x: int): int = f(x);" "recursion: f -> f");
    ("mutual recursion", `Quick, lib_rejected "f(x: int): int = g(x);\ng(x: int): int = f(x);" "recursion: f -> g -> f");
    ("body type", `Quick, lib_rejected "f(x: int): string = x;" "not the declared string");
    ("confinement", `Quick, lib_rejected "f(x: int): int = y;" "undeclared reference to 'y'");
    ("duplicate parameter", `Quick, lib_rejected "f(x: int, x: int): int = x;" "duplicate parameter");
    ("duplicate function", `Quick, lib_rejected "f(x: int): int = x; f(y: int): int = y;" "duplicate function");
    ("untyped parameter", `Quick, lib_rejected "f(x): int = x;" "needs a type");
    ("unterminated", `Quick, lib_rejected "f(x: int): int = x" "not ended by ';'");
    ("bad body", `Quick, lib_rejected "f(x: int): int = x +;" "f: ");
    ("hash", `Quick, (fun () ->
         let l = lib () in
         Alcotest.(check string) "hash" ("sha256:" ^ Sha256.digest lib_text) l.hash;
         (match Library.load ~expect:l.hash lib_text with Ok _ -> () | Error e -> Alcotest.fail e);
         match Library.load ~expect:l.hash (lib_text ^ " ") with
         | Ok _ -> Alcotest.fail "a changed text loaded under the old hash"
         | Error _ -> ())) ]

(* ---- sha256 ----------------------------------------------------------------------------------- *)

let sha_tests =
  List.map
    (fun (input, want) -> (Printf.sprintf "%d bytes" (String.length input), `Quick, fun () ->
         Alcotest.(check string) "digest" want (Sha256.digest input)))
    [ ("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
      ("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
      ( "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1" );
      (String.make 55 'x', "d5e285683cd4efc02d021a5c62014694958901005d6f71e89e0989fac77e4072");
      (String.make 56 'x', "04c26261370ee7541549d16dee320c723e3fd14671e66a099afe0a377c16888e");
      (String.make 64 'x', "7ce100971f64e7001e8fe5a51973ecdfe1ced42befe7ee8d5fd6219506b5393c");
      (String.make 1_000_000 'a', "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0") ]

let () =
  Extensions.load ();
  Alcotest.run "cel-extensions"
    [ ("fold", fold_tests); ("match", match_tests); ("libraries", library_tests); ("sha256", sha_tests) ]
