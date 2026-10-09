(* Standard base64 decoding, for the corpus's bytes values. *)
let decode s =
  let v c =
    match c with
    | 'A' .. 'Z' -> Char.code c - 65
    | 'a' .. 'z' -> Char.code c - 71
    | '0' .. '9' -> Char.code c + 4
    | '+' | '-' -> 62
    | '/' | '_' -> 63
    | _ -> -1
  in
  let buf = Buffer.create (String.length s) in
  let acc = ref 0 and bits = ref 0 in
  String.iter
    (fun c ->
      let x = v c in
      if x >= 0 then begin
        acc := (!acc lsl 6) lor x;
        bits := !bits + 6;
        if !bits >= 8 then begin
          bits := !bits - 8;
          Buffer.add_char buf (Char.chr ((!acc lsr !bits) land 0xFF))
        end
      end)
    s;
  Buffer.contents buf
