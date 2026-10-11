(* CEL's regular expressions (cel/lib/re2.ml): RE2 syntax, matched by code
   point. Each case's expectation is cel-go's (Go's regexp is RE2); the
   differential test (differential.ml) draws patterns at random against
   cel-go itself. These are the cases a byte-wise matcher gets wrong, and
   the syntax RE2 has and Perl's lacks. *)

open Cordis_cel

let matches p s = match Eval.compile_regex p with Some r -> Some (Re.execp r s) | None -> None

let cases =
  [ (* one character, however many bytes *)
    ("^.$", "\xc3\xa9", Some true);
    ("^.$", "\xf0\x9f\x98\x80", Some true);
    ("^.{2}$", "\xc3\xa9", Some false);
    ("^\xc3\xa9+$", "\xc3\xa9\xc3\xa9", Some true);
    ("^[\xc3\xa9]$", "\xc3\xa9", Some true);
    ("^[^a]$", "\xc3\xa9", Some true);
    ("^[^\xc3\xa9]$", "\xc3\xbc", Some true);
    ("^[\xc3\xa0-\xc3\xbf]+$", "\xc3\xa9\xc3\xbc", Some true);
    ("^[\\x{1F600}-\\x{1F64F}]$", "\xf0\x9f\x98\x80", Some true);
    ("^[\\x{80}-\\x{10FFFF}]$", "\xe2\x80\xa8", Some true);
    ("^\\W$", "\xc3\xa9", Some true);
    ("^\\S{2}$", "a\xc3\xa9", Some true);
    (* hex and octal escapes *)
    ("^\\x41\\x{e9}$", "A\xc3\xa9", Some true);
    ("^[\\x00-\\x1f]$", "\n", Some true);
    ("^\\101$", "A", Some true);
    ("^\\0$", "\x00", Some true);
    (* $ is the end of the text, not before a final newline; (?m) makes it a line's end *)
    ("^a$", "a\n", Some false);
    ("(?m)^a$", "a\nb", Some true);
    (* . stops at a newline, but (?s) *)
    ("^.$", "\n", Some false);
    ("(?s)^.$", "\n", Some true);
    (* flags, groups, classes *)
    ("(?i)^ab$", "AB", Some true);
    ("^(?i:a)b$", "AB", Some false);
    ("(?i)a|b", "B", Some true);
    ("^(?P<x>a)(?<y>b)$", "ab", Some true);
    ("^[[:alpha:]]+$", "abZ", Some true);
    ("^[[:^digit:]]$", "a", Some true);
    ("^[]a]+$", "]a", Some true);
    ("^\\Qa.b\\E$", "a.b", Some true);
    ("^\\Qa.b\\E$", "axb", Some false);
    ("^a{,2}$", "a{,2}", Some true);
    ("^a{2,}?$", "aaa", Some true);
    ("\\.", "a", Some false);
    (* any ASCII punctuation may be escaped, _ too *)
    ("^\\_\\-$", "_-", Some true);
    (* Unicode's general categories *)
    ("^\\p{L}+$", "h\xc3\xa9llo", Some true);
    ("^\\pN$", "\xd9\xa3", Some true);
    ("^\\p{Nd}$", "\xe2\x85\xab", Some false);
    ("^\\P{L}$", "1", Some true);
    ("^[\\p{Lu}\\d]+$", "AB1", Some true);
    ("^\\p{^Ll}$", "A", Some true);
    ("^[^\\p{L}]$", "\xc3\xa9", Some false);
    ("^\\p{So}$", "\xf0\x9f\x98\x80", Some true);
    ("^\\p{Any}$", "\n", Some true);
    ("\\p{Zz}", "a", None);
    (* what RE2 refuses *)
    ("(?=a)", "a", None);
    ("(a)\\1", "aa", None);
    ("a**", "a", None);
    ("[b-a]", "a", None);
    ("(", "a", None);
    ("\\", "a", None);
    ("\\q", "q", None);
    ("a{1001}", "a", None) ]

let () =
  Alcotest.run "re2"
    [ ( "cases",
        List.map
          (fun (p, s, want) ->
            Alcotest.test_case (Printf.sprintf "%S ~ %S" p s) `Quick (fun () ->
                Alcotest.(check (option bool)) (Printf.sprintf "%S matches %S" s p) want (matches p s)))
          cases ) ]
