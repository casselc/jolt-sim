(* IP addresses and prefixes for the network extension, following Go's
   net/netip, which cel-go's network library is built on.

   An address is its raw bytes: 4 for IPv4, 16 for IPv6. Parsing accepts
   exactly what netip.ParseAddr accepts (no leading zeros in IPv4 octets, at
   most one "::", an embedded IPv4 only in the last 32 bits, an optional
   "%zone" on IPv6), and printing produces what netip.Addr.String produces
   (RFC 5952: lowercase, longest run of two or more zero groups as "::"). *)

type addr = { bytes : string; zone : string option }

exception Bad of string

let fail msg = raise (Bad msg)

(* ---- parsing ------------------------------------------------------------------- *)

(* A dotted quad, as netip's parseIPv4Fields reads it. *)
let ipv4_octets s =
  let out = Bytes.make 4 '\000' in
  let n = String.length s and pos = ref 0 and v = ref 0 and digits = ref 0 in
  String.iteri
    (fun i c ->
      match c with
      | '0' .. '9' ->
          if !digits = 1 && !v = 0 then fail "IPv4 field has octet with leading zero";
          v := (!v * 10) + Char.code c - Char.code '0';
          incr digits;
          if !v > 255 then fail "IPv4 field has value >255"
      | '.' ->
          if i = 0 || i = n - 1 || s.[i - 1] = '.' then fail "IPv4 field must have at least one digit";
          if !pos = 3 then fail "IPv4 address too long";
          Bytes.set out !pos (Char.chr !v);
          incr pos;
          v := 0;
          digits := 0
      | _ -> fail "unexpected character")
    s;
  if !pos < 3 then fail "IPv4 address too short";
  Bytes.set out 3 (Char.chr !v);
  Bytes.to_string out

let hex_value c =
  match c with
  | '0' .. '9' -> Some (Char.code c - Char.code '0')
  | 'a' .. 'f' -> Some (Char.code c - Char.code 'a' + 10)
  | 'A' .. 'F' -> Some (Char.code c - Char.code 'A' + 10)
  | _ -> None

(* Split "addr%zone"; an explicit zone must be non-empty. *)
let split_zone s =
  match String.index_opt s '%' with
  | None -> (s, None)
  | Some i ->
      let zone = String.sub s (i + 1) (String.length s - i - 1) in
      if zone = "" then fail "zone must be a non-empty string";
      (String.sub s 0 i, Some zone)

(* Move the groups after the "::" to the end, zero-filling the gap. *)
let expand_ellipsis ip filled ellipsis =
  if filled < 16 then begin
    if ellipsis < 0 then fail "address string too short";
    let out = Bytes.make 16 '\000' and gap = 16 - filled in
    Bytes.blit ip 0 out 0 ellipsis;
    Bytes.blit ip ellipsis out (ellipsis + gap) (filled - ellipsis);
    out
  end
  else if ellipsis >= 0 then fail "the :: must expand to at least one field of zeros"
  else ip

(* netip's parseIPv6: hex groups separated by ':', one optional "::", and an
   optional trailing dotted quad. *)
let ipv6_bytes s =
  let ip = Bytes.make 16 '\000' and n = String.length s in
  let p = ref 0 and i = ref 0 and ellipsis = ref (-1) and fin = ref false in
  if n >= 2 && s.[0] = ':' && s.[1] = ':' then begin
    ellipsis := 0;
    p := 2;
    if n = 2 then fin := true
  end;
  while (not !fin) && !i < 16 do
    let start = !p and acc = ref 0 in
    let rec digits () =
      match if !p < n then hex_value s.[!p] else None with
      | Some d ->
          if !p - start > 3 then fail "each group must have 4 or less digits";
          acc := (!acc * 16) + d;
          incr p;
          digits ()
      | None -> ()
    in
    digits ();
    if !p = start then fail "each colon-separated field must have at least one digit";
    if !p < n && s.[!p] = '.' then begin
      (* the group was the start of an embedded IPv4 address *)
      if !ellipsis < 0 && !i <> 12 then
        fail "embedded IPv4 address must replace the final 2 fields of the address";
      if !i + 4 > 16 then fail "too many hex fields to fit an embedded IPv4 at the end of the address";
      Bytes.blit_string (ipv4_octets (String.sub s start (n - start))) 0 ip !i 4;
      i := !i + 4;
      p := n;
      fin := true
    end
    else begin
      Bytes.set ip !i (Char.chr (!acc lsr 8));
      Bytes.set ip (!i + 1) (Char.chr (!acc land 0xff));
      i := !i + 2;
      if !p = n then fin := true
      else if s.[!p] <> ':' then fail "unexpected character, want colon"
      else if !p + 1 = n then fail "colon must be followed by more characters"
      else begin
        incr p;
        if s.[!p] = ':' then begin
          if !ellipsis >= 0 then fail "multiple :: in address";
          ellipsis := !i;
          incr p;
          if !p = n then fin := true
        end
      end
    end
  done;
  if !p <> n then fail "trailing garbage after address";
  Bytes.to_string (expand_ellipsis ip !i !ellipsis)

(* netip.ParseAddr: the first '.', ':' or '%' decides the family. *)
let parse s =
  let first = String.to_seq s |> Seq.find (fun c -> c = '.' || c = ':' || c = '%') in
  match first with
  | Some '.' -> { bytes = ipv4_octets s; zone = None }
  | Some ':' ->
      let s, zone = split_zone s in
      { bytes = ipv6_bytes s; zone }
  | Some _ -> fail "missing IPv6 address"
  | None -> fail "unable to parse IP"

let parse_opt s = try Ok (parse s) with Bad msg -> Error msg

(* ---- properties ----------------------------------------------------------------- *)

let byte a i = Char.code a.[i]
let is4 a = String.length a = 4

(* An IPv4-mapped IPv6 address, ::ffff:a.b.c.d *)
let is4in6 a =
  String.length a = 16
  && String.for_all (fun c -> c = '\000') (String.sub a 0 10)
  && byte a 10 = 0xff && byte a 11 = 0xff

let unmap a = if is4in6 a then String.sub a 12 4 else a
let all_zero a = String.for_all (fun c -> c = '\000') a
let group a k = (byte a (2 * k) lsl 8) lor byte a ((2 * k) + 1)

(* The netip predicates. All but isUnspecified look through an
   IPv4-mapped address to the IPv4 address it maps. *)
let is_unspecified a = all_zero a

let is_loopback a =
  let a = unmap a in
  if is4 a then byte a 0 = 127 else all_zero (String.sub a 0 15) && byte a 15 = 1

let is_multicast a =
  let a = unmap a in
  if is4 a then byte a 0 land 0xf0 = 0xe0 else byte a 0 = 0xff

let is_link_local_multicast a =
  let a = unmap a in
  if is4 a then byte a 0 = 224 && byte a 1 = 0 && byte a 2 = 0 else group a 0 land 0xff0f = 0xff02

let is_link_local_unicast a =
  let a = unmap a in
  if is4 a then byte a 0 = 169 && byte a 1 = 254 else group a 0 land 0xffc0 = 0xfe80

(* Private and unique-local addresses still count, as in package net. *)
let is_global_unicast a =
  let a = unmap a in
  let broadcast = is4 a && String.for_all (fun c -> c = '\xff') a in
  not (all_zero a || broadcast || is_loopback a || is_multicast a || is_link_local_unicast a)

(* ---- printing -------------------------------------------------------------------- *)

let ipv4_string a = Printf.sprintf "%d.%d.%d.%d" (byte a 0) (byte a 1) (byte a 2) (byte a 3)

(* The first longest run of two or more zero groups, as (start, end). *)
let longest_zero_run a =
  let best = ref (0, 0) in
  for i = 0 to 7 do
    let j = ref i in
    while !j < 8 && group a !j = 0 do incr j done;
    let s, e = !best in
    if !j - i >= 2 && !j - i > e - s then best := (i, !j)
  done;
  !best

let ipv6_string a =
  let zs, ze = longest_zero_run a in
  let buf = Buffer.create 40 in
  let i = ref 0 in
  while !i < 8 do
    if !i = zs && ze > zs then begin
      Buffer.add_string buf "::";
      i := ze
    end
    else begin
      if !i > 0 && !i <> ze then Buffer.add_char buf ':';
      Buffer.add_string buf (Printf.sprintf "%x" (group a !i));
      incr i
    end
  done;
  Buffer.contents buf

let to_string a =
  if is4 a then ipv4_string a
  else if is4in6 a then "::ffff:" ^ ipv4_string (unmap a)
  else ipv6_string a

(* ---- prefixes --------------------------------------------------------------------- *)

let bit_length a = 8 * String.length a

(* netip.ParsePrefix's prefix length: decimal, no sign or leading zero. *)
let parse_bits s max =
  let digits = s <> "" && String.for_all (fun c -> c >= '0' && c <= '9') s in
  if (not digits) || (String.length s > 1 && s.[0] = '0') || String.length s > 3 then
    fail "bad bits after slash";
  let bits = int_of_string s in
  if bits > max then fail "prefix length out of range";
  bits

(* The address with every bit past the first [bits] cleared. *)
let mask a bits =
  String.mapi
    (fun i c ->
      let keep = max 0 (min 8 (bits - (8 * i))) in
      Char.chr (Char.code c land ((0xff lsl (8 - keep)) land 0xff)))
    a

(* Same family and the same first [bits] bits. *)
let same_prefix a b bits = String.length a = String.length b && mask a bits = mask b bits
