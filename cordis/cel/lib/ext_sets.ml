(* The sets extension library, as cel-go's ext.Sets: lists read as sets,
   membership by CEL's heterogeneous equality (so 1, 1u and 1.0 are the
   same member). *)

open Value

let mem xs v = Array.exists (equal v) xs

(* Every element of [sub] is in [l]. *)
let contains l sub = Array.for_all (mem l) sub

let on_lists name f =
  Registry.register name (function [ List a; List b ] -> Some (Bool (f a b)) | _ -> None)

let register () =
  on_lists "sets.contains" contains;
  on_lists "sets.equivalent" (fun a b -> contains a b && contains b a);
  on_lists "sets.intersects" (fun a b -> Array.exists (mem b) a)
