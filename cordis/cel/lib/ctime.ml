(* Timestamps and durations (docs/cordis/rfc/0001-cel.md, step 5), as
   cel-spec and cel-go define them.

   A timestamp is (seconds since the Unix epoch, nanoseconds) between
   0001-01-01T00:00:00Z and 9999-12-31T23:59:59.999999999Z. A duration is
   (seconds, nanoseconds) of the same sign, within protobuf's ±315,576,000,000
   seconds; the difference of two timestamps must also fit in int64
   nanoseconds, as cel-go requires. Time zones are fixed offsets ("+05:30",
   "-02:30", "02:00") or IANA names read from the host's zoneinfo (TZif files,
   including the POSIX rule that follows their last transition). *)

let min_ts = -62135596800L (* 0001-01-01T00:00:00Z *)
let max_ts = 253402300799L (* 9999-12-31T23:59:59Z *)
let max_dur = 315576000000L
let billion = 1_000_000_000

type 'a r = ('a, string) result

let range = Error "range"

(* ---- civil dates (Howard Hinnant's days_from_civil, in int64) ------------------- *)

let floor_div a b = if (a >= 0L) = (b > 0L) || Int64.rem a b = 0L then Int64.div a b else Int64.pred (Int64.div a b)
let floor_mod a b = Int64.sub a (Int64.mul (floor_div a b) b)

let days_from_civil y m d =
  let y = if m <= 2 then Int64.pred y else y in
  let era = floor_div y 400L in
  let yoe = Int64.sub y (Int64.mul era 400L) in
  let mp = Int64.of_int ((m + 9) mod 12) in
  let doy = Int64.add (Int64.div (Int64.add (Int64.mul 153L mp) 2L) 5L) (Int64.of_int (d - 1)) in
  let doe = Int64.add (Int64.add (Int64.mul yoe 365L) (Int64.div yoe 4L)) (Int64.sub doy (Int64.div yoe 100L)) in
  Int64.add (Int64.mul era 146097L) (Int64.sub doe 719468L)

let civil_from_days z =
  let z = Int64.add z 719468L in
  let era = floor_div z 146097L in
  let doe = Int64.sub z (Int64.mul era 146097L) in
  let yoe = Int64.div (Int64.sub (Int64.sub (Int64.add doe (Int64.div doe 36524L)) (Int64.div doe 1460L)) (Int64.div doe 146096L)) 365L in
  let y = Int64.add yoe (Int64.mul era 400L) in
  let doy = Int64.sub doe (Int64.sub (Int64.add (Int64.mul 365L yoe) (Int64.div yoe 4L)) (Int64.div yoe 100L)) in
  let mp = Int64.div (Int64.add (Int64.mul 5L doy) 2L) 153L in
  let d = Int64.to_int (Int64.add (Int64.sub doy (Int64.div (Int64.add (Int64.mul 153L mp) 2L) 5L)) 1L) in
  let m = Int64.to_int (if mp < 10L then Int64.add mp 3L else Int64.sub mp 9L) in
  ((if m <= 2 then Int64.succ y else y), m, d)

let is_leap y = (Int64.rem y 4L = 0L && Int64.rem y 100L <> 0L) || Int64.rem y 400L = 0L

(* ---- durations --------------------------------------------------------------------- *)

(* Normalise (seconds, nanos) so both have the same sign and |nanos| < 1e9. *)
let normalize s n =
  let s = Int64.add s (Int64.of_int (n / billion)) and n = n mod billion in
  if s > 0L && n < 0 then (Int64.pred s, n + billion)
  else if s < 0L && n > 0 then (Int64.succ s, n - billion)
  else (s, n)

let check_dur (s, n) = if Int64.abs s > max_dur then range else Ok (s, n)
let check_ts (s, n) = if s < min_ts || s > max_ts then range else Ok (s, n)

let dur_add (s1, n1) (s2, n2) = check_dur (normalize (Int64.add s1 s2) (n1 + n2))
let dur_neg (s, n) = (Int64.neg s, -n)
let dur_sub a b = dur_add a (dur_neg b)

(* Timestamps keep nanos in [0, 1e9). *)
let ts_norm s n = if n < 0 then (Int64.pred s, n + billion) else if n >= billion then (Int64.succ s, n - billion) else (s, n)
let ts_add (s, n) (ds, dn) = check_ts (ts_norm (Int64.add s ds) (n + dn))

let ts_sub (s1, n1) (s2, n2) =
  (* the difference must be representable in int64 nanoseconds *)
  let ds = Int64.sub s1 s2 in
  if Int64.abs ds > 9223372035L then range else check_dur (normalize ds (n1 - n2))

(* Go-style durations: [-]1h2m3.5s, units ns us µs ms s m h. *)
let parse_duration str =
  let n = String.length str in
  let i = ref 0 in
  let neg = n > 0 && str.[0] = '-' in
  if n > 0 && (str.[0] = '-' || str.[0] = '+') then incr i;
  if !i >= n then Error "invalid duration"
  else if String.sub str !i (n - !i) = "0" then Ok (0L, 0)
  else
    let total = ref (Ok (0L, 0)) in
    let ok = ref true in
    while !ok && !i < n do
      let start = !i in
      while !i < n && str.[!i] >= '0' && str.[!i] <= '9' do incr i done;
      let int_part = String.sub str start (!i - start) in
      let frac =
        if !i < n && str.[!i] = '.' then begin
          incr i;
          let fs = !i in
          while !i < n && str.[!i] >= '0' && str.[!i] <= '9' do incr i done;
          String.sub str fs (!i - fs)
        end
        else ""
      in
      if int_part = "" && frac = "" then ok := false
      else begin
        let us = !i in
        while !i < n && not (str.[!i] = '.' || (str.[!i] >= '0' && str.[!i] <= '9')) do incr i done;
        let unit_ns =
          match String.sub str us (!i - us) with
          | "ns" -> Some 1L | "us" | "\xc2\xb5s" | "\xce\xbcs" -> Some 1000L | "ms" -> Some 1_000_000L
          | "s" -> Some 1_000_000_000L | "m" -> Some 60_000_000_000L | "h" -> Some 3_600_000_000_000L
          | _ -> None
        in
        match unit_ns, Int64.of_string_opt (if int_part = "" then "0" else int_part) with
        | Some u, Some v ->
            (* v units = v * u ns, split into seconds and nanoseconds exactly *)
            let comp =
              if u >= 1_000_000_000L then (Int64.mul v (Int64.div u 1_000_000_000L), 0)
              else
                let per = Int64.div 1_000_000_000L u in
                (Int64.div v per, Int64.to_int (Int64.mul (Int64.rem v per) u))
            in
            (* the fraction of a unit, exactly: every unit is a * 10^j with
               a in {1, 6, 36}, and at most 17 fraction digits keep f * a in
               range *)
            let frac_ns =
              if frac = "" then 0L
              else
                let f = if String.length frac > 17 then String.sub frac 0 17 else frac in
                let k = String.length f and fv = Int64.of_string f in
                let a, j =
                  match u with
                  | 60_000_000_000L -> (6L, 10) | 3_600_000_000_000L -> (36L, 11)
                  | _ -> (1L, String.length (Int64.to_string u) - 1)
                in
                let pow10 e = Int64.of_string ("1" ^ String.make e '0') in
                if j >= k then Int64.mul (Int64.mul fv a) (pow10 (j - k))
                else Int64.div (Int64.mul fv a) (pow10 (k - j))
            in
            let frac_part = (Int64.div frac_ns 1_000_000_000L, Int64.to_int (Int64.rem frac_ns 1_000_000_000L)) in
            total := Result.bind !total (fun t -> Result.bind (dur_add t comp) (fun t -> dur_add t frac_part))
        | _ -> ok := false
      end
    done;
    if not !ok then Error "invalid duration"
    else Result.map (fun d -> if neg then dur_neg d else d) !total

let format_frac n =
  (* protobuf's JSON form: 0, 3, 6 or 9 fractional digits *)
  if n = 0 then ""
  else if n mod 1_000_000 = 0 then Printf.sprintf ".%03d" (n / 1_000_000)
  else if n mod 1000 = 0 then Printf.sprintf ".%06d" (n / 1000)
  else Printf.sprintf ".%09d" n

let format_duration (s, n) =
  let neg = s < 0L || n < 0 in
  Printf.sprintf "%s%Ld%ss" (if neg && s = 0L then "-" else "") s (format_frac (abs n))

(* ---- timestamps -------------------------------------------------------------------- *)

let digits str i k =
  if i + k > String.length str then None
  else
    let v = ref 0 in
    let ok = ref true in
    for j = i to i + k - 1 do
      let c = str.[j] in
      if c < '0' || c > '9' then ok := false else v := (!v * 10) + Char.code c - 48
    done;
    if !ok then Some !v else None

(* RFC 3339, as Go's time.Parse(time.RFC3339, s) reads it. *)
let parse_timestamp str =
  let n = String.length str in
  let ( let* ) o f = match o with Some x -> f x | None -> Error "invalid timestamp" in
  let* y = digits str 0 4 in
  let expect i c = if i < n && str.[i] = c then Some () else None in
  let* () = expect 4 '-' in
  let* mo = digits str 5 2 in
  let* () = expect 7 '-' in
  let* d = digits str 8 2 in
  let* () = expect 10 'T' in
  let* h = digits str 11 2 in
  let* () = expect 13 ':' in
  let* mi = digits str 14 2 in
  let* () = expect 16 ':' in
  let* se = digits str 17 2 in
  let i = ref 19 and nanos = ref 0 in
  if !i < n && str.[!i] = '.' then begin
    incr i;
    let fs = !i in
    while !i < n && str.[!i] >= '0' && str.[!i] <= '9' do incr i done;
    let f = String.sub str fs (!i - fs) in
    if f <> "" && String.length f <= 9 then nanos := int_of_string (f ^ String.make (9 - String.length f) '0')
  end;
  let* offset =
    if !i < n && str.[!i] = 'Z' && !i + 1 = n then Some 0
    else if !i + 6 = n && (str.[!i] = '+' || str.[!i] = '-') && str.[!i + 3] = ':' then
      match digits str (!i + 1) 2, digits str (!i + 4) 2 with
      | Some oh, Some om -> Some ((if str.[!i] = '-' then -1 else 1) * ((oh * 3600) + (om * 60)))
      | _ -> None
    else None
  in
  let dim = [| 31; (if is_leap (Int64.of_int y) then 29 else 28); 31; 30; 31; 30; 31; 31; 30; 31; 30; 31 |] in
  if mo < 1 || mo > 12 || d < 1 || d > dim.(mo - 1) || h > 23 || mi > 59 || se > 59 then Error "invalid timestamp"
  else
    let days = days_from_civil (Int64.of_int y) mo d in
    let secs = Int64.add (Int64.mul days 86400L) (Int64.of_int ((h * 3600) + (mi * 60) + se - offset)) in
    check_ts (secs, !nanos)

let format_timestamp (s, n) =
  let days = floor_div s 86400L and sod = Int64.to_int (floor_mod s 86400L) in
  let y, m, d = civil_from_days days in
  let frac =
    if n = 0 then ""
    else
      (* Go's RFC3339Nano: trailing zeros trimmed *)
      let f = Printf.sprintf "%09d" n in
      let k = ref 9 in
      while !k > 0 && f.[!k - 1] = '0' do decr k done;
      "." ^ String.sub f 0 !k
  in
  Printf.sprintf "%04Ld-%02d-%02dT%02d:%02d:%02d%sZ" y m d (sod / 3600) (sod mod 3600 / 60) (sod mod 60) frac

(* ---- time zones ---------------------------------------------------------------------- *)

(* A POSIX TZ rule (the footer of a TZif file): std offset [dst [offset] ,start[/time],end[/time]]. *)
type rule_date = Julian1 of int | Julian0 of int | Mwd of int * int * int
type posix = { std_off : int; dst : (int * rule_date * int * rule_date * int) option }

let parse_posix s =
  let n = String.length s and i = ref 0 in
  let name () =
    if !i < n && s.[!i] = '<' then (while !i < n && s.[!i] <> '>' do incr i done; incr i)
    else while !i < n && ((s.[!i] >= 'A' && s.[!i] <= 'Z') || (s.[!i] >= 'a' && s.[!i] <= 'z')) do incr i done
  in
  let num () =
    let st = !i in
    while !i < n && s.[!i] >= '0' && s.[!i] <= '9' do incr i done;
    int_of_string (String.sub s st (!i - st))
  in
  (* hh[:mm[:ss]], signed; POSIX offsets are west-positive *)
  let hms () =
    let sign = if !i < n && s.[!i] = '-' then (incr i; -1) else (if !i < n && s.[!i] = '+' then incr i; 1) in
    let h = num () in
    let m = if !i < n && s.[!i] = ':' then (incr i; num ()) else 0 in
    let sec = if !i < n && s.[!i] = ':' then (incr i; num ()) else 0 in
    sign * ((h * 3600) + (m * 60) + sec)
  in
  let date () =
    if s.[!i] = 'J' then (incr i; Julian1 (num ()))
    else if s.[!i] = 'M' then begin
      incr i;
      let m = num () in incr i;
      let w = num () in incr i;
      let d = num () in
      Mwd (m, w, d)
    end
    else Julian0 (num ())
  in
  let time () = if !i < n && s.[!i] = '/' then (incr i; hms ()) else 7200 in
  try
    name ();
    let std = - hms () in
    if !i >= n then Some { std_off = std; dst = None }
    else begin
      name ();
      let dst_off = if !i < n && s.[!i] <> ',' then - hms () else std + 3600 in
      incr i;
      let sd = date () in
      let st = time () in
      incr i;
      let ed = date () in
      let et = time () in
      Some { std_off = std; dst = Some (dst_off, sd, st, ed, et) }
    end
  with _ -> None

(* Seconds since the epoch of local midnight on a rule date in year y. *)
let rule_day y = function
  | Julian1 d ->
      let d = if is_leap y && d >= 60 then d else d - 1 in
      Int64.add (days_from_civil y 1 1) (Int64.of_int d)
  | Julian0 d -> Int64.add (days_from_civil y 1 1) (Int64.of_int d)
  | Mwd (m, w, wd) ->
      let first = days_from_civil y m 1 in
      let first_wd = Int64.to_int (floor_mod (Int64.add first 4L) 7L) in
      let day = 1 + ((wd - first_wd + 7) mod 7) + ((w - 1) * 7) in
      let dim = [| 31; (if is_leap y then 29 else 28); 31; 30; 31; 30; 31; 31; 30; 31; 30; 31 |].(m - 1) in
      let day = if day > dim then day - 7 else day in
      Int64.add first (Int64.of_int (day - 1))

let posix_offset p t =
  match p.dst with
  | None -> p.std_off
  | Some (dst_off, sd, st, ed, et) ->
      let y, _, _ = civil_from_days (floor_div (Int64.add t (Int64.of_int p.std_off)) 86400L) in
      let start = Int64.sub (Int64.add (Int64.mul (rule_day y sd) 86400L) (Int64.of_int st)) (Int64.of_int p.std_off) in
      let stop = Int64.sub (Int64.add (Int64.mul (rule_day y ed) 86400L) (Int64.of_int et)) (Int64.of_int dst_off) in
      let in_dst = if start < stop then t >= start && t < stop else not (t >= stop && t < start) in
      if in_dst then dst_off else p.std_off

type zone = { transitions : int64 array; offsets : int array; (* per transition *) initial : int; footer : posix option }

let be32 s i =
  let b k = Char.code s.[i + k] in
  let v = (b 0 lsl 24) lor (b 1 lsl 16) lor (b 2 lsl 8) lor b 3 in
  if v land 0x80000000 <> 0 then v - 0x100000000 else v

let be64 s i = Int64.logor (Int64.shift_left (Int64.of_int (be32 s i)) 32) (Int64.logand (Int64.of_int (be32 s (i + 4))) 0xFFFFFFFFL)

let read_tzif data =
  if String.length data < 44 || String.sub data 0 4 <> "TZif" then None
  else
    let counts off = Array.init 6 (fun k -> be32 data (off + 20 + (4 * k))) in
    let block off tlen =
      let c = counts off in
      let isutc, isstd, leap, timecnt, typecnt, charcnt = (c.(0), c.(1), c.(2), c.(3), c.(4), c.(5)) in
      let p = off + 44 in
      let times = Array.init timecnt (fun k -> if tlen = 8 then be64 data (p + (8 * k)) else Int64.of_int (be32 data (p + (4 * k)))) in
      let p = p + (timecnt * tlen) in
      let idx = Array.init timecnt (fun k -> Char.code data.[p + k]) in
      let p = p + timecnt in
      let types = Array.init typecnt (fun k -> (be32 data (p + (6 * k)), Char.code data.[p + (6 * k) + 4])) in
      let p = p + (typecnt * 6) + charcnt + (leap * (tlen + 4)) + isstd + isutc in
      (times, idx, types, p)
    in
    let version = data.[4] in
    let times, idx, types, after =
      if version = '\000' then block 0 4
      else
        let _, _, _, v1_end = block 0 4 in
        block v1_end 8
    in
    let footer =
      if version <> '\000' && after < String.length data && data.[after] = '\n' then
        match String.index_from_opt data (after + 1) '\n' with
        | Some e -> parse_posix (String.sub data (after + 1) (e - after - 1))
        | None -> None
      else None
    in
    let initial =
      match Array.find_opt (fun (_, dst) -> dst = 0) types with
      | Some (o, _) -> o
      | None -> if Array.length types > 0 then fst types.(0) else 0
    in
    Some { transitions = times; offsets = Array.map (fun k -> fst types.(k)) idx; initial; footer }

let zone_cache : (string, zone option) Hashtbl.t = Hashtbl.create 8
let zoneinfo () = Option.value (Sys.getenv_opt "ZONEINFO") ~default:"/usr/share/zoneinfo"

(* Backward-compatible names (US/Central) are links; some systems ship
   only the canonical files, so resolve links through tzdata.zi's
   "L target alias" lines. *)
let links =
  lazy
    (let t = Hashtbl.create 64 in
     (try
        In_channel.with_open_text (Filename.concat (zoneinfo ()) "tzdata.zi") (fun ic ->
            In_channel.fold_lines
              (fun () line ->
                match String.split_on_char ' ' line with
                | [ "L"; target; alias ] -> Hashtbl.replace t alias target
                | _ -> ())
              () ic)
      with _ -> ());
     t)

let load_zone name =
  match Hashtbl.find_opt zone_cache name with
  | Some z -> z
  | None ->
      let valid = name <> "" && not (String.contains name '\000') && not (List.mem ".." (String.split_on_char '/' name)) in
      let z =
        if not valid then None
        else
          let read n = In_channel.with_open_bin (Filename.concat (zoneinfo ()) n) In_channel.input_all in
          match read name with
          | data -> read_tzif data
          | exception _ -> (
              match Hashtbl.find_opt (Lazy.force links) name with
              | Some target -> (try read_tzif (read target) with _ -> None)
              | None -> None)
      in
      Hashtbl.add zone_cache name z;
      z

let zone_offset z t =
  let n = Array.length z.transitions in
  if n = 0 || t < z.transitions.(0) then
    (match z.footer with Some p when n = 0 -> posix_offset p t | _ -> z.initial)
  else if t >= z.transitions.(n - 1) then
    (match z.footer with Some p -> posix_offset p t | None -> z.offsets.(n - 1))
  else begin
    (* last transition <= t *)
    let lo = ref 0 and hi = ref (n - 1) in
    while !lo < !hi do
      let mid = (!lo + !hi + 1) / 2 in
      if z.transitions.(mid) <= t then lo := mid else hi := mid - 1
    done;
    z.offsets.(!lo)
  end

(* The UTC offset in seconds of a zone at instant t. *)
let offset_of tz t =
  let fixed s =
    let sign, body =
      match s.[0] with '+' -> (1, String.sub s 1 (String.length s - 1)) | '-' -> (-1, String.sub s 1 (String.length s - 1)) | _ -> (1, s)
    in
    match String.split_on_char ':' body with
    | [ hs; ms ] when String.length hs = 2 && String.length ms = 2 -> (
        match int_of_string_opt hs, int_of_string_opt ms with
        | Some h, Some m -> Some (sign * ((h * 3600) + (m * 60)))
        | _ -> None)
    | _ -> None
  in
  if tz = "UTC" then Ok 0
  else
    match if String.length tz > 0 then fixed tz else None with
    | Some o -> Ok o
    | None -> (match load_zone tz with Some z -> Ok (zone_offset z t) | None -> Error ("unknown time zone: " ^ tz))

(* ---- accessors -------------------------------------------------------------------------- *)

let timestamp_part part ?(tz = "UTC") (s, n) =
  match offset_of tz s with
  | Error e -> Error e
  | Ok off ->
      let local = Int64.add s (Int64.of_int off) in
      let days = floor_div local 86400L and sod = Int64.to_int (floor_mod local 86400L) in
      let y, m, d = civil_from_days days in
      Ok
        (match part with
         | "getFullYear" -> y
         | "getMonth" -> Int64.of_int (m - 1)
         | "getDate" -> Int64.of_int d
         | "getDayOfMonth" -> Int64.of_int (d - 1)
         | "getDayOfYear" -> Int64.sub days (days_from_civil y 1 1)
         | "getDayOfWeek" -> floor_mod (Int64.add days 4L) 7L
         | "getHours" -> Int64.of_int (sod / 3600)
         | "getMinutes" -> Int64.of_int (sod mod 3600 / 60)
         | "getSeconds" -> Int64.of_int (sod mod 60)
         | _ (* getMilliseconds *) -> Int64.of_int (n / 1_000_000))

let duration_part part (s, n) =
  match part with
  | "getHours" -> Int64.div s 3600L
  | "getMinutes" -> Int64.div s 60L
  | "getSeconds" -> s
  | _ (* getMilliseconds *) -> Int64.of_int (n / 1_000_000)
