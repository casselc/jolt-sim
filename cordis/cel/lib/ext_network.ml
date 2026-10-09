(* The network extension library (cel-go ext.Network, version 1): the types
   net.IP and net.CIDR and their functions.

   An IP value holds its canonical text (netip.Addr.String) and a CIDR value
   holds "address/bits" with the address as written, unmasked, so that
   Value.equal on the opaque form is cel-go's equality and printing a value
   shows the address.

   Where the cel-spec conformance file and cel-go disagree, this follows the
   conformance file:
   - an IPv4-mapped IPv6 address written with a dotted quad
     (::ffff:192.168.0.1) is rejected, but one written in hex (::ffff:c0a8:1)
     is accepted as the IPv4 address it maps, so it equals ip('192.168.0.1');
     cel-go rejects both. A CIDR rejects either form, as cel-go does.
   - error messages are the conformance file's, not cel-go's. *)

open Value
module A = Ext_network_addr

let ip_type = "net.IP"
let cidr_type = "net.CIDR"

type cidr = { addr : string; bits : int }

(* ---- values ----------------------------------------------------------------------- *)

let ip_value a = Opaque { tname = ip_type; data = A.to_string a }
let cidr_value c = Opaque { tname = cidr_type; data = Printf.sprintf "%s/%d" (A.to_string c.addr) c.bits }

(* The stored forms were produced by this module, so they always parse. *)
let ip_of_data d = (A.parse d).bytes

let cidr_of_data d =
  let i = String.rindex d '/' in
  { addr = ip_of_data (String.sub d 0 i); bits = int_of_string (String.sub d (i + 1) (String.length d - i - 1)) }

(* ---- parsing strings ---------------------------------------------------------------- *)

let parse_ip s =
  match A.parse_opt s with
  | Error _ -> Result.Error (Printf.sprintf "IP Address '%s' parse error during conversion from string" s)
  | Ok { zone = Some _; _ } -> Result.Error "IP Address with zone value is not allowed"
  | Ok { bytes; _ } when A.is4in6 bytes ->
      if String.contains s '.' then Result.Error "IPv4-mapped IPv6 address is not allowed"
      else Ok (A.unmap bytes)
  | Ok { bytes; _ } -> Ok bytes

let parse_cidr s =
  let parse_error = Result.Error "network address parse error during conversion from string" in
  match String.rindex_opt s '/' with
  | None -> parse_error
  | Some i -> (
      match A.parse_opt (String.sub s 0 i) with
      | Error _ -> parse_error
      | Ok { zone = Some _; _ } -> Result.Error "CIDR with zone value is not allowed"
      | Ok { bytes; _ } when A.is4in6 bytes -> Result.Error "IPv4-mapped IPv6 address is not allowed"
      | Ok { bytes; _ } -> (
          match A.parse_bits (String.sub s (i + 1) (String.length s - i - 1)) (A.bit_length bytes) with
          | bits -> Ok { addr = bytes; bits }
          | exception A.Bad _ -> parse_error))

let or_error f = function Ok x -> f x | Result.Error e -> Error e

(* ---- operations ---------------------------------------------------------------------- *)

let contains_ip c a = A.same_prefix c.addr a c.bits

(* netip's Overlaps plus "the parent is no longer than the child". *)
let contains_cidr parent child = parent.bits <= child.bits && A.same_prefix parent.addr child.addr parent.bits

let masked c = { c with addr = A.mask c.addr c.bits }

(* ---- registration ---------------------------------------------------------------------- *)

(* Overloads on one receiver type; the argument patterns pick the overload. *)
let on_ip name f =
  Registry.register name (function
    | [ Opaque { tname; data } ] when tname = ip_type -> Some (f (ip_of_data data))
    | _ -> None)

let on_cidr name f =
  Registry.register name (function
    | Opaque { tname; data } :: rest when tname = cidr_type -> f (cidr_of_data data) rest
    | _ -> None)

let on_string name f = Registry.register name (function [ String s ] -> Some (f s) | _ -> None)

let is_ip_arg = function Opaque { tname; _ } -> tname = ip_type | _ -> false
let is_cidr_arg = function Opaque { tname; _ } -> tname = cidr_type | _ -> false

let register_ip () =
  on_string "ip" (fun s -> or_error ip_value (parse_ip s));
  on_string "isIP" (fun s -> Bool (Result.is_ok (parse_ip s)));
  on_string "ip.isCanonical" (fun s -> or_error (fun a -> Bool (A.to_string a = s)) (parse_ip s));
  on_ip "string" (fun a -> String (A.to_string a));
  on_ip "family" (fun a -> Int (if A.is4 a then 4L else 6L));
  on_ip "isUnspecified" (fun a -> Bool (A.is_unspecified a));
  on_ip "isLoopback" (fun a -> Bool (A.is_loopback a));
  on_ip "isGlobalUnicast" (fun a -> Bool (A.is_global_unicast a));
  on_ip "isLinkLocalMulticast" (fun a -> Bool (A.is_link_local_multicast a));
  on_ip "isLinkLocalUnicast" (fun a -> Bool (A.is_link_local_unicast a))

let register_cidr () =
  on_string "cidr" (fun s -> or_error cidr_value (parse_cidr s));
  on_string "isCIDR" (fun s -> Bool (Result.is_ok (parse_cidr s)));
  let unary f c = function [] -> Some (f c) | _ -> None in
  on_cidr "string" (unary (fun c -> String (Printf.sprintf "%s/%d" (A.to_string c.addr) c.bits)));
  on_cidr "ip" (unary (fun c -> ip_value c.addr));
  on_cidr "prefixLength" (unary (fun c -> Int (Int64.of_int c.bits)));
  on_cidr "masked" (unary (fun c -> cidr_value (masked c)));
  on_cidr "isMask" (unary (fun c -> Bool (c.addr = (masked c).addr)));
  on_cidr "containsIP" (fun c -> function
    | [ (Opaque { data; _ } as v) ] when is_ip_arg v -> Some (Bool (contains_ip c (ip_of_data data)))
    | [ String s ] -> Some (or_error (fun a -> Bool (contains_ip c a)) (parse_ip s))
    | _ -> None);
  on_cidr "containsCIDR" (fun c -> function
    | [ (Opaque { data; _ } as v) ] when is_cidr_arg v -> Some (Bool (contains_cidr c (cidr_of_data data)))
    | [ String s ] -> Some (or_error (fun child -> Bool (contains_cidr c child)) (parse_cidr s))
    | _ -> None)

(* Signatures, as cel-go's ext.Network declares them. *)
let declare () =
  let d = Registry.declare in
  let f params result = (false, params, result) and m params result = (true, params, result) in
  d "ip" [ f [ "string" ] ip_type; m [ cidr_type ] ip_type ];
  d "cidr" [ f [ "string" ] cidr_type ];
  d "string" [ f [ ip_type ] "string"; f [ cidr_type ] "string" ];
  List.iter (fun n -> d n [ f [ "string" ] "bool" ]) [ "isIP"; "isCIDR"; "ip.isCanonical" ];
  d "family" [ m [ ip_type ] "int" ];
  List.iter
    (fun n -> d n [ m [ ip_type ] "bool" ])
    [ "isUnspecified"; "isLoopback"; "isGlobalUnicast"; "isLinkLocalMulticast"; "isLinkLocalUnicast" ];
  d "prefixLength" [ m [ cidr_type ] "int" ];
  d "masked" [ m [ cidr_type ] cidr_type ];
  d "isMask" [ m [ cidr_type ] "bool" ];
  d "containsIP" [ m [ cidr_type; ip_type ] "bool"; m [ cidr_type; "string" ] "bool" ];
  d "containsCIDR" [ m [ cidr_type; cidr_type ] "bool"; m [ cidr_type; "string" ] "bool" ]

let register () =
  declare ();
  Registry.register_type ip_type (T_named ip_type);
  Registry.register_type cidr_type (T_named cidr_type);
  register_ip ();
  register_cidr ()
