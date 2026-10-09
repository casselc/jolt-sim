(* The encoders extension library (cel-go ext.Encoders): base64.encode and
   base64.decode with the standard alphabet.

   Decoding follows cel-go: try padded standard base64 and, if the input is
   not that, unpadded ("raw") base64. As in Go's decoder, '\r' and '\n' are
   ignored anywhere, and unused bits in the last character are not checked. *)

open Value

let alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

let encode s =
  let n = String.length s in
  let buf = Buffer.create (((n + 2) / 3) * 4) in
  let byte i = if i < n then Char.code s.[i] else 0 in
  let rec go i =
    if i < n then begin
      let w = (byte i lsl 16) lor (byte (i + 1) lsl 8) lor byte (i + 2) in
      (* a final group of 1 or 2 bytes gives 2 or 3 characters, then '=' *)
      let chars = min 4 (((n - i) * 4 + 2) / 3) in
      for k = 0 to 3 do
        Buffer.add_char buf (if k < chars then alphabet.[(w lsr (18 - (6 * k))) land 63] else '=')
      done;
      go (i + 3)
    end
  in
  go 0;
  Buffer.contents buf

let sextet c = String.index_opt alphabet c

exception Corrupt of int

(* The characters that carry data, each with its offset in the input. *)
let significant s =
  String.to_seq s |> Seq.mapi (fun i c -> (i, c)) |> Seq.filter (fun (_, c) -> c <> '\r' && c <> '\n') |> Array.of_seq

(* Decode unpadded characters: every group of 4 gives 3 bytes, and a final
   group of 2 or 3 gives 1 or 2; a final group of 1 is corrupt. *)
let decode_chars (cs : (int * char) array) =
  let n = Array.length cs in
  if n mod 4 = 1 then raise (Corrupt (fst cs.(n - 1)));
  let value k = match sextet (snd cs.(k)) with Some v -> v | None -> raise (Corrupt (fst cs.(k))) in
  let buf = Buffer.create (n * 3 / 4) in
  let rec go k =
    if k < n then begin
      let len = min 4 (n - k) in
      let w = ref 0 in
      for j = 0 to 3 do w := (!w lsl 6) lor (if j < len then value (k + j) else 0) done;
      for j = 0 to len - 2 do Buffer.add_char buf (Char.chr ((!w lsr (16 - (8 * j))) land 0xff)) done;
      go (k + 4)
    end
  in
  go 0;
  Buffer.contents buf

(* Padded: a multiple of 4 characters, ending in at most two '='. *)
let decode_padded cs =
  let n = Array.length cs in
  if n mod 4 <> 0 then raise (Corrupt (if n = 0 then 0 else fst cs.(n - 1)));
  let pad = if n >= 1 && snd cs.(n - 1) = '=' then if n >= 2 && snd cs.(n - 2) = '=' then 2 else 1 else 0 in
  decode_chars (Array.sub cs 0 (n - pad))

let decode s =
  let cs = significant s in
  match decode_padded cs with
  | b -> Ok b
  | exception Corrupt _ -> (
      match decode_chars cs with
      | b -> Ok b
      | exception Corrupt at -> Result.Error (Printf.sprintf "illegal base64 data at input byte %d" at))

let register () =
  Registry.declare "base64.encode" [ (false, [ "bytes" ], "string") ];
  Registry.declare "base64.decode" [ (false, [ "string" ], "bytes") ];
  Registry.register "base64.encode" (function [ Bytes b ] -> Some (String (encode b)) | _ -> None);
  Registry.register "base64.decode" (function
    | [ String s ] -> Some (match decode s with Ok b -> Bytes b | Result.Error e -> Error e)
    | _ -> None)
