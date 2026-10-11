(* The durable form of the engine's records (docs/cordis/rfc/0006-turn-
   runtime.md, "The commit record"): JSON, versioned, until Preserves is
   needed (RFC 0006 open question 2). Every value a record holds round-trips
   exactly: doubles by their bit pattern, bytes in hex. A value with no
   durable form (a type, an error, an unknown) is refused, since a turn
   that produced one has failed and records only its message. *)

module V = Cordis_cel.Value
module E = Engine
module J = Yojson.Safe

(* 2: an answered append names its stream *)
let version = 2

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt

let hex s = String.concat "" (List.init (String.length s) (fun i -> Printf.sprintf "%02x" (Char.code s.[i])))
let unhex h = String.init (String.length h / 2) (fun i -> Char.chr (int_of_string ("0x" ^ String.sub h (2 * i) 2)))
let i64 n = `String (Int64.to_string n)
let to_i64 = function `String s -> Int64.of_string s | j -> bad "not an int64: %s" (J.to_string j)

let rec value (v : V.t) : J.t =
  match v with
  | V.Null -> `List [ `String "null" ]
  | V.Bool b -> `List [ `String "b"; `Bool b ]
  | V.Int n -> `List [ `String "i"; i64 n ]
  | V.Uint n -> `List [ `String "u"; i64 n ]
  | V.Double d -> `List [ `String "d"; i64 (Int64.bits_of_float d) ]
  | V.String s -> `List [ `String "s"; `String s ]
  | V.Bytes s -> `List [ `String "y"; `String (hex s) ]
  | V.List xs -> `List [ `String "l"; `List (Array.to_list (Array.map value xs)) ]
  | V.Map m -> `List [ `String "m"; `List (List.map (fun (k, x) -> `List [ value k; value x ]) m) ]
  | V.Timestamp (s, n) -> `List [ `String "ts"; i64 s; `Int n ]
  | V.Duration (s, n) -> `List [ `String "du"; i64 s; `Int n ]
  | V.Optional None -> `List [ `String "o" ]
  | V.Optional (Some x) -> `List [ `String "o"; value x ]
  | V.Message { tname; fields } -> `List [ `String "msg"; `String tname; `List (List.map (fun (f, x) -> `List [ `String f; value x ]) fields) ]
  | V.Enum { tname; num } -> `List [ `String "enum"; `String tname; `Int num ]
  | V.Opaque { tname; data } -> `List [ `String "op"; `String tname; `String data ]
  | V.Type _ | V.Error _ | V.Unknown _ -> bad "no durable form for %s" (V.to_string v)

let rec of_value (j : J.t) : V.t =
  match j with
  | `List [ `String "null" ] -> V.Null
  | `List [ `String "b"; `Bool b ] -> V.Bool b
  | `List [ `String "i"; n ] -> V.Int (to_i64 n)
  | `List [ `String "u"; n ] -> V.Uint (to_i64 n)
  | `List [ `String "d"; n ] -> V.Double (Int64.float_of_bits (to_i64 n))
  | `List [ `String "s"; `String s ] -> V.String s
  | `List [ `String "y"; `String h ] -> V.Bytes (unhex h)
  | `List [ `String "l"; `List xs ] -> V.List (Array.of_list (List.map of_value xs))
  | `List [ `String "m"; `List kvs ] -> V.Map (List.map (function `List [ k; x ] -> (of_value k, of_value x) | j -> bad "a map entry: %s" (J.to_string j)) kvs)
  | `List [ `String "ts"; s; `Int n ] -> V.Timestamp (to_i64 s, n)
  | `List [ `String "du"; s; `Int n ] -> V.Duration (to_i64 s, n)
  | `List [ `String "o" ] -> V.Optional None
  | `List [ `String "o"; x ] -> V.Optional (Some (of_value x))
  | `List [ `String "msg"; `String tname; `List fs ] ->
      V.Message { tname; fields = List.map (function `List [ `String f; x ] -> (f, of_value x) | j -> bad "a field: %s" (J.to_string j)) fs }
  | `List [ `String "enum"; `String tname; `Int num ] -> V.Enum { tname; num }
  | `List [ `String "op"; `String tname; `String data ] -> V.Opaque { tname; data }
  | j -> bad "not a value: %s" (J.to_string j)

let pairs f l = `List (List.map (fun (k, x) -> `List [ `String k; f x ]) l)
let of_pairs f = function `List l -> List.map (function `List [ `String k; x ] -> (k, f x) | j -> bad "a pair: %s" (J.to_string j)) l | j -> bad "not pairs: %s" (J.to_string j)
let int_ j = match j with `Int n -> n | j -> bad "not an int: %s" (J.to_string j)

let intent (i : E.intent) =
  `Assoc [ ("key", `String i.key); ("kind", `String i.kind); ("request", value i.request);
           ("cls", `String (match i.cls with E.Observational -> "observational" | E.Keyed -> "keyed" | E.Effectful -> "effectful"));
           ("attempt", `Int i.attempt) ]

let of_intent j =
  let f k = match j with `Assoc kv -> (match List.assoc_opt k kv with Some x -> x | None -> bad "intent: no %s" k) | _ -> bad "not an intent" in
  { E.key = (match f "key" with `String s -> s | _ -> bad "key"); kind = (match f "kind" with `String s -> s | _ -> bad "kind");
    request = of_value (f "request");
    cls = (match f "cls" with `String "observational" -> E.Observational | `String "keyed" -> E.Keyed | `String "effectful" -> E.Effectful | _ -> bad "cls");
    attempt = int_ (f "attempt") }

let progress tag ~turn ~args ~receipts ~basis extra =
  `Assoc ([ ("fiber", `String tag); ("turn", `Int turn); ("args", pairs value args); ("receipts", pairs value receipts);
            ("basis", pairs (fun n -> `Int n) basis) ] @ extra)

let fiber (f : E.fiber) =
  match f with
  | E.Ready -> `Assoc [ ("fiber", `String "ready") ]
  | E.Failed m -> `Assoc [ ("fiber", `String "failed"); ("why", `String m) ]
  | E.Running { turn; args; receipts; basis } -> progress "running" ~turn ~args ~receipts ~basis []
  | E.Awaiting { turn; args; receipts; basis; intent = i } -> progress "awaiting" ~turn ~args ~receipts ~basis [ ("intent", intent i) ]
  | E.Uncertain { turn; args; receipts; basis; intent = i } -> progress "uncertain" ~turn ~args ~receipts ~basis [ ("intent", intent i) ]

let of_fiber j =
  let kv = match j with `Assoc kv -> kv | _ -> bad "not a fiber" in
  let f k = match List.assoc_opt k kv with Some x -> x | None -> bad "fiber: no %s" k in
  match f "fiber" with
  | `String "ready" -> E.Ready
  | `String "failed" -> E.Failed (match f "why" with `String m -> m | _ -> "?")
  | `String tag -> (
      let turn = int_ (f "turn") and args = of_pairs of_value (f "args") and receipts = of_pairs of_value (f "receipts")
      and basis = of_pairs int_ (f "basis") in
      match tag with
      | "running" -> E.Running { turn; args; receipts; basis }
      | "awaiting" -> E.Awaiting { turn; args; receipts; basis; intent = of_intent (f "intent") }
      | "uncertain" -> E.Uncertain { turn; args; receipts; basis; intent = of_intent (f "intent") }
      | t -> bad "fiber %s" t)
  | _ -> bad "fiber tag"

let slot (s : E.slot) =
  `Assoc [ ("value", (match s.value with Some v -> value v | None -> `Null)); ("rev", `Int s.rev); ("seen", pairs (fun n -> `Int n) s.seen);
           ("state", value s.state); ("computes", `Int s.computes); ("fiber", fiber s.fiber) ]

let of_slot j =
  let f k = match j with `Assoc kv -> (match List.assoc_opt k kv with Some x -> x | None -> bad "slot: no %s" k) | _ -> bad "not a slot" in
  { E.value = (match f "value" with `Null -> None | v -> Some (of_value v)); rev = int_ (f "rev"); seen = of_pairs int_ (f "seen");
    state = of_value (f "state"); computes = int_ (f "computes"); fiber = of_fiber (f "fiber") }

let fact (x : E.fact) =
  match x with
  | E.Slot (a, s) -> `List [ `String "slot"; `String a; slot s ]
  | E.Input (n, { svalue; srev }) -> `List [ `String "input"; `String n; (match svalue with Some v -> value v | None -> `Null); `Int srev ]
  | E.Stream (n, vs, r) -> `List [ `String "stream"; `String n; `List (List.map value vs); `Int r ]
  | E.Answered (s, st, e, c) -> `List [ `String "answered"; `String s; `String st; `Int e; `Int c ]
  | E.Episode e -> `List [ `String "episode"; `Int e ]
  | E.Budget (r, st) -> `List [ `String "budget"; `Int r; `Int st ]
  | E.Cutover_begin id -> `List [ `String "cutover-begin"; `String id ]
  | E.Asset_revision (a, id) -> `List [ `String "asset-revision"; `String a; `String id ]
  | E.Cutover_end id -> `List [ `String "cutover-end"; `String id ]

let of_fact (j : J.t) : E.fact =
  match j with
  | `List [ `String "slot"; `String a; s ] -> E.Slot (a, of_slot s)
  | `List [ `String "input"; `String n; v; r ] -> E.Input (n, { svalue = (match v with `Null -> None | v -> Some (of_value v)); srev = int_ r })
  | `List [ `String "stream"; `String n; `List vs; r ] -> E.Stream (n, List.map of_value vs, int_ r)
  | `List [ `String "answered"; `String s; `String st; e; c ] -> E.Answered (s, st, int_ e, int_ c)
  | `List [ `String "episode"; e ] -> E.Episode (int_ e)
  | `List [ `String "budget"; r; st ] -> E.Budget (int_ r, int_ st)
  | `List [ `String "cutover-begin"; `String id ] -> E.Cutover_begin id
  | `List [ `String "asset-revision"; `String a; `String id ] -> E.Asset_revision (a, id)
  | `List [ `String "cutover-end"; `String id ] -> E.Cutover_end id
  | j -> bad "not a fact: %s" (J.to_string j)

let record (r : E.record) = J.to_string (`Assoc [ ("v", `Int version); ("seq", `Int r.seq); ("facts", `List (List.map fact r.facts)) ])

let of_record s =
  match J.from_string s with
  | `Assoc kv -> (
      (match List.assoc_opt "v" kv with Some (`Int v) when v = version -> () | _ -> bad "record format version");
      match List.assoc_opt "seq" kv, List.assoc_opt "facts" kv with
      | Some (`Int seq), Some (`List fs) -> { E.seq; facts = List.map of_fact fs }
      | _ -> bad "not a record")
  | _ -> bad "not a record"
