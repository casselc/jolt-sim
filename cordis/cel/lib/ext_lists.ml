(* The lists extension library, as cel-go's ext.Lists at its latest version
   (slice, flatten, distinct, reverse, sort, sortBy, lists.range), with the
   optional-returning first and last of cel-go's optional library, and the
   sets library. Messages follow cel-go. *)

open Value

let fail_parse fmt = Printf.ksprintf (fun s -> raise (Parser.Parse_error s)) fmt

(* lists.range refuses to build more than this many elements (cel-go's
   default maximum). *)
let max_range = 1_000_000L

(* ---- slicing and reshaping ---------------------------------------------------------- *)

let slice xs s e =
  let n = Int64.of_int (Array.length xs) in
  if Int64.compare s 0L < 0 || Int64.compare e 0L < 0 then
    Error (Printf.sprintf "cannot slice(%Ld, %Ld), negative indexes not supported" s e)
  else if Int64.compare s e > 0 then
    Error (Printf.sprintf "cannot slice(%Ld, %Ld), start index must be less than or equal to end index" s e)
  else if Int64.compare n e < 0 then
    Error (Printf.sprintf "cannot slice(%Ld, %Ld), list is length %Ld" s e n)
  else List (Array.sub xs (Int64.to_int s) (Int64.to_int (Int64.sub e s)))

(* Splice nested lists in, [depth] levels deep. *)
let rec flatten_items depth xs =
  List.concat_map
    (function List ys when depth > 0L -> flatten_items (Int64.pred depth) ys | v -> [ v ])
    (Array.to_list xs)

let flatten xs depth =
  if Int64.compare depth 0L < 0 then Error "level must be non-negative"
  else List (Array.of_list (flatten_items depth xs))

(* Keep the first of each run of equal elements, in order. *)
let distinct xs =
  let keep acc x = if List.exists (equal x) acc then acc else x :: acc in
  List (Array.of_list (List.rev (Array.fold_left keep [] xs)))

let reverse xs =
  let n = Array.length xs in
  List (Array.init n (fun i -> xs.(n - 1 - i)))

let range n =
  if Int64.compare n 0L < 0 then Error (Printf.sprintf "lists.range: size must be non-negative, got %Ld" n)
  else if Int64.compare n max_range > 0 then
    Error (Printf.sprintf "lists.range: size %Ld exceeds maximum allowed (%Ld)" n max_range)
  else List (Array.init (Int64.to_int n) (fun i -> Int (Int64.of_int i)))

(* ---- sorting -------------------------------------------------------------------------- *)

(* The orderable types: cel-go's comparable element types. *)
let orderable = function
  | Int _ | Uint _ | Double _ | Bool _ | String _ | Bytes _ | Timestamp _ | Duration _ -> true
  | _ -> false

(* A total order on keys of one orderable type. Doubles use Float.compare,
   which puts NaN first (as Go's cmp.Compare does) where cel-go's sort
   leaves NaN wherever its sorting algorithm happens to put it. *)
let compare_keys a b =
  match a, b with
  | Int x, Int y -> Int64.compare x y
  | Uint x, Uint y -> Int64.unsigned_compare x y
  | Double x, Double y -> Float.compare x y
  | Bool x, Bool y -> Bool.compare x y
  | String x, String y | Bytes x, Bytes y -> String.compare x y
  | Timestamp (s, n), Timestamp (s', n') | Duration (s, n), Duration (s', n') -> compare (s, n) (s', n')
  | _ -> invalid_arg "Ext_lists.compare_keys"

(* Order [xs] by the matching [keys], stably. The keys must all be of the
   first key's type, and that type must be orderable. *)
let sort_by_keys xs keys =
  let n = Array.length xs in
  if n <> Array.length keys then
    Error
      (Printf.sprintf
         "@sortByAssociatedKeys() expected a list of the same size as the associated keys list, but got %d and %d elements respectively"
         n (Array.length keys))
  else if n = 0 then List xs
  else if not (orderable keys.(0)) then Error "list elements must be comparable"
  else if Array.exists (fun k -> type_of k <> type_of keys.(0)) keys then
    Error "list elements must have the same type"
  else
    let pairs = Array.to_list (Array.map2 (fun k x -> (k, x)) keys xs) in
    let sorted = List.stable_sort (fun (a, _) (b, _) -> compare_keys a b) pairs in
    List (Array.of_list (List.map snd sorted))

let input_var = "@__sortBy_input__"

(* l.sortBy(e, key) binds l once and sorts it by l.map(e, key):

     comprehension over [] with accumulator @__sortBy_input__ = l,
       result @sortByAssociatedKeys(@__sortBy_input__, @__sortBy_input__.map(e, key))

   as cel-go expands it; the map is built as the parser builds map(). *)
let sort_by_macro : Registry.expander =
 fun ~mk ~target args ->
  match target, args with
  | Some t, [ v; key ] ->
      (match t.kind with
       | Ast.List_lit _ | Ast.Select _ | Ast.Ident _ | Ast.Comprehension _ | Ast.Call _ -> ()
       | _ -> fail_parse "sortBy can only be applied to a list, identifier, comprehension, call or select expression");
      let var = match v.kind with Ast.Ident n -> n | _ -> fail_parse "argument is not an identifier" in
      let input () = mk (Ast.Ident input_var) in
      let accu () = mk (Ast.Ident "@result") in
      let keys =
        mk
          (Ast.Comprehension
             { iter_var = var; iter_var2 = None; range = input (); accu_var = "@result";
               accu_init = mk (Ast.List_lit []); loop_cond = mk (Ast.Const (Bool true));
               loop_step = mk (Ast.Call { fn = "_+_"; target = None; args = [ accu (); mk (Ast.List_lit [ key ]) ] });
               result = accu () })
      in
      Some
        (mk
           (Ast.Comprehension
              { iter_var = "#unused"; iter_var2 = None; range = mk (Ast.List_lit []); accu_var = input_var;
                accu_init = t; loop_cond = mk (Ast.Const (Bool false)); loop_step = input ();
                result = mk (Ast.Call { fn = "@sortByAssociatedKeys"; target = None; args = [ input (); keys ] }) }))
  | _ -> None

(* ---- registration ------------------------------------------------------------------- *)

(* A receiver function on a list; other receivers (a string's reverse, say)
   are left to other libraries. *)
let on_list name f = Registry.register name (function [ List xs ] -> Some (f xs) | _ -> None)

(* Signatures, as cel-go's ext.Lists declares them (first and last as its
   optional library does). sort and @sortByAssociatedKeys take lists of
   the comparable types; sortBy expands to @sortByAssociatedKeys. *)
let declare () =
  let m params result = (true, params, result) in
  let d = Registry.declare in
  let comparable = [ "int"; "uint"; "double"; "bool"; "google.protobuf.Duration"; "google.protobuf.Timestamp"; "string"; "bytes" ] in
  d "slice" [ m [ "list(T)"; "int"; "int" ] "list(T)" ];
  d "flatten" [ m [ "list(list(T))" ] "list(T)"; m [ "list(dyn)"; "int" ] "list(dyn)" ];
  d "distinct" [ m [ "list(T)" ] "list(T)" ];
  d "reverse" [ m [ "list(T)" ] "list(T)" ];
  d "sort" (List.map (fun t -> m [ "list(" ^ t ^ ")" ] ("list(" ^ t ^ ")")) comparable);
  d "first" [ m [ "list(T)" ] "optional_type(T)" ];
  d "last" [ m [ "list(T)" ] "optional_type(T)" ];
  d "@sortByAssociatedKeys" (List.map (fun u -> (false, [ "list(T)"; "list(" ^ u ^ ")" ], "list(T)")) comparable);
  d "lists.range" [ (false, [ "int" ], "list(int)") ]

let register () =
  declare ();
  Registry.register "slice" (function [ List xs; Int s; Int e ] -> Some (slice xs s e) | _ -> None);
  Registry.register "flatten" (function
    | [ List xs ] -> Some (flatten xs 1L)
    | [ List xs; Int d ] -> Some (flatten xs d)
    | _ -> None);
  on_list "distinct" distinct;
  on_list "reverse" reverse;
  on_list "sort" (fun xs -> sort_by_keys xs xs);
  on_list "first" (fun xs -> Optional (if Array.length xs = 0 then None else Some xs.(0)));
  on_list "last" (fun xs -> Optional (if Array.length xs = 0 then None else Some xs.(Array.length xs - 1)));
  Registry.register "@sortByAssociatedKeys" (function
    | [ List xs; List keys ] -> Some (sort_by_keys xs keys)
    | _ -> None);
  Registry.register_macro "sortBy" sort_by_macro;
  Registry.register "lists.range" (function [ Int n ] -> Some (range n) | _ -> None);
  Ext_sets.register ()
