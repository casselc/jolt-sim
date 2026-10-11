(* The translation of JSON Schema's ECMA-262 patterns into CEL's RE2
   (Schema_pred.translate): what the two dialects spell differently, and
   what RE2 caps. Each expectation is ECMA-262's, and Python's re (which,
   like the translation, matches by code point) agrees with each but two
   where Python is not ECMA: its $ matches before a final newline, and its
   . matches \r. The Test Suite's optional ecmascript-regex tests run in
   schema_suite.ml. *)

open Cordis_import

let matches p s =
  match Schema_pred.translate p with
  | t -> (match Cordis_cel.Eval.compile_regex t with Some r -> Some (Re.execp r s) | None -> None)
  | exception Schema_pred.Unsupported _ -> None

let cases =
  [ (* a repetition over RE2's 1000, written as several *)
    ("^a{0,1600}$", String.make 1600 'a', Some true);
    ("^a{0,1600}$", String.make 1601 'a', Some false);
    ("^a{1001,1002}$", String.make 1000 'a', Some false);
    ("^a{1001,1002}$", String.make 1002 'a', Some true);
    ("^(ab){1001}$", String.concat "" (List.init 1001 (fun _ -> "ab")), Some true);
    ("^[a-z]{2,1500}?$", "abc", Some true);
    ("^x{1001,}$", String.make 1500 'x', Some true);
    (* a hyphen beside a class escape is a hyphen *)
    ("^[\\w-\\/.]+$", "a-b/c.d", Some true);
    ("^[\\w-\\/.]+$", "a b", Some false);
    ("^[.-\\d]+$", "-1.", Some true);
    (* \u, \x, \c, \0; a surrogate pair is the character it encodes; a surrogate in a class names nothing *)
    ("^\\u0041\\x42$", "AB", Some true);
    ("^\\ud83d\\ude00$", "\xf0\x9f\x98\x80", Some true);
    ("^[\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDBFF-\\uDC00\\uDFFF\\t]*$", "ab\xc3\xa9\t", Some true);
    ("^[\\u0020-\\uD7FF\\uE000-\\uFFFD\\uD800\\uDBFF-\\uDC00\\uDFFF\\t]*$", "a\n", Some false);
    ("^\\cJ$", "\n", Some true);
    ("\\ud800", "a", None);
    (* ECMA's . excludes \r too; its \s includes U+00A0 *)
    ("^.$", "\r", Some false);
    ("^\\s$", "\xc2\xa0", Some true);
    (* an identity escape is the letter; a brace that is no quantifier is a brace *)
    ("^\\_\\e$", "_e", Some true);
    ("^a{,2}$", "a{,2}", Some true);
    (* refused: lookaround, a backreference, a Unicode class *)
    ("(?!a)b", "b", None);
    ("(a)\\1", "aa", None);
    ("\\p{L}", "a", None) ]

let () =
  Alcotest.run "patterns"
    [ ( "translate",
        List.mapi
          (fun k (p, s, want) ->
            Alcotest.test_case (Printf.sprintf "%d %S" k p) `Quick (fun () ->
                Alcotest.(check (option bool)) (Printf.sprintf "%S on %S" p s) want (matches p s)))
          cases ) ]
