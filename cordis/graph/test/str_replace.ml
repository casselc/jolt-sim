(* Replace every occurrence of [sub] in a string. *)
let all ~sub ~by s =
  let n = String.length sub in
  let b = Buffer.create (String.length s) in
  let i = ref 0 in
  while !i < String.length s do
    if n > 0 && !i + n <= String.length s && String.sub s !i n = sub then (Buffer.add_string b by; i := !i + n)
    else (Buffer.add_char b s.[!i]; incr i)
  done;
  Buffer.contents b
