type t = { int : int -> int; label : string }

(* SplitMix64 (Steele, Lea and Flood, 2014), on OCaml's boxed Int64. *)
let splitmix seed =
  let state = ref (Int64.of_int seed) in
  let next () =
    state := Int64.add !state 0x9E3779B97F4A7C15L;
    let z = !state in
    let z = Int64.mul (Int64.logxor z (Int64.shift_right_logical z 30)) 0xBF58476D1CE4E5B9L in
    let z = Int64.mul (Int64.logxor z (Int64.shift_right_logical z 27)) 0x94D049BB133111EBL in
    Int64.logxor z (Int64.shift_right_logical z 31)
  in
  let int n =
    if n <= 0 then invalid_arg "Oracle.int";
    Int64.to_int (Int64.unsigned_rem (next ()) (Int64.of_int n))
  in
  { int; label = Printf.sprintf "splitmix:%d" seed }

let choose o = function
  | [] -> invalid_arg "Oracle.choose: empty"
  | l -> List.nth l (o.int (List.length l))

let chance o pct = o.int 100 < pct

let recording o =
  let log = ref [] in
  ( { o with int = (fun n -> let x = o.int n in log := x :: !log; x) },
    fun () -> List.rev !log )

let replay answers =
  let rest = ref answers in
  let int n =
    match !rest with
    | x :: tl -> rest := tl; if x < n then x else x mod n
    | [] -> 0
  in
  { int; label = "replay" }
