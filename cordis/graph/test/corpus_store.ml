(* The corpus's content store: corpus/libs/* (library texts, descriptor
   sets), found by hash. A program names a stored file as HASH(file);
   LIBHASH is HASH(text.cel). *)

let read path = In_channel.with_open_bin path In_channel.input_all

let files dir =
  let libs = Filename.concat dir "libs" in
  Array.to_list (Sys.readdir libs) |> List.sort compare |> List.map (fun f -> (f, read (Filename.concat libs f)))

let hash s = "sha256:" ^ Cordis_cel.Sha256.digest s

let store dir =
  let fs = files dir in
  fun h -> List.find_map (fun (_, s) -> if hash s = h then Some s else None) fs

let substitute dir text =
  let text = List.fold_left (fun t (f, s) -> Str_replace.all ~sub:("HASH(" ^ f ^ ")") ~by:(hash s) t) text (files dir) in
  match List.assoc_opt "text.cel" (files dir) with Some s -> Str_replace.all ~sub:"LIBHASH" ~by:(hash s) text | None -> text
