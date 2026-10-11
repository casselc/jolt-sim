(* Values of imported types, for the importers' agreement tests: scalar
   pools that straddle a document's bounds, plain JSON, a value of any
   imported type, and structural equality. Shared by schema_agreement and
   openapi_agreement. *)

open Cordis_import
module J = Yojson.Safe
module V = Cordis_cel.Value

let choose (d : Typed_gen.draw) l = List.nth l (d.pick (List.length l))

(* ---- values of imported types ------------------------------------------------------------------------ *)

(* Scalar pools: fixed values, and both sides of every number in the
   schema (bounds, lengths, enum values), so constraints are exercised at
   their boundaries; set by [pools_for] per schema. *)
let base_strings = [ ""; "a"; "x"; "ab"; "USD"; "usd"; "12345"; "1234"; "ord-12"; "ord-x"; "héllo"; "on-hold"; "item.created"; "a-b" ]
let strings = ref base_strings
let ints = ref [ 0L; 1L; -1L ]
let nums = ref [ 0.; 0.5; -0.5 ]

let pools_for (doc : J.t) =
  let numbers = ref [] and strs = ref [] in
  let rec walk (j : J.t) =
    match j with
    | `Int i -> numbers := float_of_int i :: !numbers
    | `Float f -> numbers := f :: !numbers
    | `String s -> strs := s :: !strs
    | `List xs -> List.iter walk xs
    | `Assoc kv -> List.iter (fun (_, v) -> walk v) kv
    | _ -> ()
  in
  walk doc;
  let around = List.concat_map (fun n -> [ n -. 1.; n; n +. 1.; n -. 0.05; n +. 0.05; n /. 2. ]) !numbers in
  ints := List.sort_uniq compare (0L :: 1L :: -1L :: List.filter_map (fun f -> if Float.is_integer f && Float.abs f < 1e15 then Some (Int64.of_float f) else None) around);
  nums := List.sort_uniq compare (0. :: 0.5 :: around);
  (* strings of every length the schema names, and the schema's strings *)
  let lengths = List.filter_map (fun f -> if Float.is_integer f && f >= 0. && f <= 40. then Some (int_of_float f) else None) around in
  strings := List.sort_uniq compare (base_strings @ List.filter (fun s -> String.length s < 40) !strs @ List.map (fun n -> String.make n 'a') lengths)

let rec plain d depth : J.t =
  match d.Typed_gen.pick (if depth <= 0 then 5 else 7) with
  | 0 -> `Null
  | 1 -> `Bool (d.pick 2 = 0)
  | 2 -> `Int (Int64.to_int (choose d !ints))
  | 3 -> `Float (choose d !nums)
  | 4 -> `String (choose d !strings)
  | 5 -> `List (List.init (d.pick 3) (fun _ -> plain d (depth - 1)))
  | _ -> `Assoc (List.sort_uniq (fun (a, _) (b, _) -> compare a b) (List.init (d.pick 3) (fun _ -> (choose d [ "a"; "b"; "id"; "name"; "x-y" ], plain d (depth - 1)))))

let rec value (imp : Jsonschema.import) d depth (t : Jsonschema.t) : V.t =
  let v = value imp d (depth - 1) in
  match t with
  | Str n ->
      (* a string that fits the bound, counted in code points *)
      let fits s = match n with Some n -> (match Cordis_cel.Eval.call_function "size" [ V.String s ] with V.Int k -> Int64.to_int k <= n | _ -> false) | None -> true in
      V.String (choose d ("" :: List.filter fits !strings))
  | Int -> V.Int (choose d !ints)
  | Num -> V.Double (choose d !nums)
  | Bool -> V.Bool (d.pick 2 = 0)
  | Dyn | Checked _ -> Schema_pred.value (plain d 2)
  | Rec k ->
      (* the referenced type's values, or anything *)
      if depth > 0 && d.pick 2 = 0 then
        let n = List.assoc k imp.by_def in
        match Jsonschema_codec.write imp n (v (Named n)) with Ok j -> Schema_pred.value j | Error _ -> V.Null
      else Schema_pred.value (plain d 1)
  | Opt t -> if depth <= 0 || d.pick 3 = 0 then V.Optional None else V.Optional (Some (v t))
  | Lst (t, n) -> V.List (Array.init (if depth <= 0 then 0 else d.pick (1 + min 3 (Option.value n ~default:3))) (fun _ -> v t))
  | Mp (t, n) ->
      let keys = List.sort_uniq compare (List.init (if depth <= 0 then 0 else d.pick (1 + min 3 (Option.value n ~default:3))) (fun _ -> choose d [ "a"; "b"; "x-y"; "Q" ])) in
      V.make_map (List.map (fun k -> (V.String k, v t)) keys)
  | Named n -> (
      match List.assoc n imp.defs with
      | Record fs -> V.make_map (List.map (fun (f : Jsonschema.field) -> (V.String f.name, v f.ty)) fs)
      | Enum cases -> V.make_map [ (V.String "tag", V.String (fst (choose d cases))) ]
      | Variant { cases; _ } ->
          let c, _, p = choose d cases in
          V.make_map [ (V.String "tag", V.String c); (V.String "value", v (Named p)) ]
      | Alias t -> v t)

(* structural equality: doubles bit for bit, maps in any order *)
let rec same (a : V.t) (b : V.t) =
  match a, b with
  | V.Double x, V.Double y -> x = y
  | V.List xs, V.List ys -> Array.length xs = Array.length ys && Array.for_all2 same xs ys
  | V.Map xs, V.Map ys -> List.length xs = List.length ys && List.for_all (fun (k, v) -> match V.map_find ys k with Some w -> same v w | None -> false) xs
  | V.Optional (Some x), V.Optional (Some y) -> same x y
  | V.Int x, V.Int y -> x = y
  | _ -> a = b

