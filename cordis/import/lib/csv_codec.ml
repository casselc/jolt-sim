(* CSV text and a table's rows (docs/cordis/rfc/0005-xml-csv-frames.md,
   revision 3, "CSV"), for a table imported from a Table Schema
   (table_schema.ml).

   The text is RFC 4180's, read strictly: a field is quoted from its first
   character or not at all, a quote inside an unquoted field or text after
   a closing quote is refused, and so is a bare CR or a blank line; line
   ends are CRLF or LF, the last optional. Writing quotes a field only
   where it must (the delimiter, the quote, CR or LF in it; or a row's one
   field when it is empty, so the row is not a blank line), ends every
   line with CRLF, and writes a header when the dialect has one.

   A row's cells become a record: a missing token is an absent property, a
   cell is read by its column's type (an integer as [+-]digits, a number as
   a finite decimal, a boolean by its true and false values) and the JSON
   Schema codec reads the row strictly, its residue included. Reading
   checks the header against the fields, every row's column count, and
   unique and primary keys across the table. A refusal names the row (the
   header is row 1) and the column. *)

module J = Yojson.Safe
module V = Cordis_cel.Value
module T = Table_schema
module C = Jsonschema_codec

exception Bad of string

let bad fmt = Printf.ksprintf (fun m -> raise (Bad m)) fmt

(* ---- the text ---------------------------------------------------------------------------------------- *)

let parse (d : T.dialect) (s : string) : string list list =
  let n = String.length s in
  let rows = ref [] and row = ref [] and buf = Buffer.create 64 and i = ref 0 and line = ref 1 in
  let end_field () = row := Buffer.contents buf :: !row; Buffer.clear buf in
  (* a row of one empty field is a blank line unless the field was quoted *)
  let end_row ~quoted =
    end_field ();
    if !row = [ "" ] && not quoted then bad "line %d: a blank line" !line;
    rows := List.rev !row :: !rows;
    row := [];
    incr line
  in
  let fin = ref (n = 0) in
  while not !fin do
    let quoted = !i < n && s.[!i] = d.quote in
    if quoted then begin
      incr i;
      let closed = ref false in
      while not !closed do
        if !i >= n then bad "line %d: a quoted field is not closed" !line
        else if s.[!i] = d.quote then
          if !i + 1 < n && s.[!i + 1] = d.quote then (Buffer.add_char buf d.quote; i := !i + 2) else (incr i; closed := true)
        else (Buffer.add_char buf s.[!i]; incr i)
      done
    end
    else
      while !i < n && s.[!i] <> d.delimiter && s.[!i] <> '\n' && s.[!i] <> '\r' do
        if s.[!i] = d.quote then bad "line %d: a quote inside an unquoted field" !line;
        Buffer.add_char buf s.[!i];
        incr i
      done;
    if !i >= n then (end_row ~quoted; fin := true)
    else if s.[!i] = d.delimiter then (end_field (); incr i)
    else if s.[!i] = '\r' && !i + 1 < n && s.[!i + 1] = '\n' then (end_row ~quoted; i := !i + 2; fin := !i >= n)
    else if s.[!i] = '\n' then (end_row ~quoted; incr i; fin := !i >= n)
    else if s.[!i] = '\r' then bad "line %d: a bare CR" !line
    else bad "line %d: text after a closing quote" !line
  done;
  List.rev !rows

let print (d : T.dialect) (rows : string list list) =
  let delim = String.make 1 d.delimiter and q = String.make 1 d.quote in
  let field f =
    if String.exists (fun c -> c = d.delimiter || c = d.quote || c = '\r' || c = '\n') f then
      q ^ Schema_pred.replace ~sub:q ~by:(q ^ q) f ^ q
    else f
  in
  String.concat "" (List.map (fun r -> (match r with [ "" ] -> q ^ q | r -> String.concat delim (List.map field r)) ^ "\r\n") rows)

(* ---- cells ------------------------------------------------------------------------------------------- *)

let integer s =
  let n = String.length s in
  let start = if n > 0 && (s.[0] = '+' || s.[0] = '-') then 1 else 0 in
  n > start && String.for_all (fun c -> c >= '0' && c <= '9') (String.sub s start (n - start))

(* a finite decimal: [+-] digits [. digits] [e [+-] digits], with digits on one side of the point *)
let number s =
  let n = String.length s and i = ref 0 in
  let digits () = let st = !i in while !i < n && s.[!i] >= '0' && s.[!i] <= '9' do incr i done; !i - st in
  if !i < n && (s.[!i] = '+' || s.[!i] = '-') then incr i;
  let a = digits () in
  let b = if !i < n && s.[!i] = '.' then (incr i; digits ()) else 0 in
  let mant = a + b > 0 in
  let exp_ok =
    if !i < n && (s.[!i] = 'e' || s.[!i] = 'E') then (
      incr i;
      if !i < n && (s.[!i] = '+' || s.[!i] = '-') then incr i;
      digits () > 0)
    else true
  in
  mant && exp_ok && !i = n

let cell (f : T.field) s : J.t =
  match f.kind with
  | T.String | T.Any -> `String s
  | T.Integer -> if integer s then `Intlit (if s.[0] = '+' then String.sub s 1 (String.length s - 1) else s) else bad "not an integer: %S" s
  | T.Number ->
      if not (number s) then bad "not a number: %S" s;
      let x = float_of_string s in
      if Float.is_finite x then `Float x else bad "not a finite number: %S" s
  | T.Boolean -> if List.mem s f.true_values then `Bool true else if List.mem s f.false_values then `Bool false else bad "not a boolean: %S" s

(* the shortest decimal that reads back as the same double *)
let decimal f =
  let rec go p = let s = Printf.sprintf "%.*g" p f in if p >= 17 || float_of_string s = f then s else go (p + 1) in
  go 1

let text (f : T.field) (j : J.t) =
  match f.kind, j with
  | (T.String | T.Any), `String s -> s
  | T.Integer, (`Int i) -> string_of_int i
  | T.Integer, `Intlit s -> s
  | T.Integer, `Float x when Float.is_integer x && Float.abs x < 1e18 -> Printf.sprintf "%.0f" x
  | T.Number, `Int i -> string_of_int i
  | T.Number, `Intlit s -> s
  | T.Number, `Float x -> if Float.is_finite x then decimal x else bad "%s: a number that is not finite has no form here" f.name
  | T.Boolean, `Bool b -> (match if b then f.true_values else f.false_values with v :: _ -> v | [] -> bad "%s: no value for %b" f.name b)
  | _, j -> bad "%s: not a %s" f.name (J.to_string j)

(* ---- the table --------------------------------------------------------------------------------------- *)

let read (t : T.t) (s : string) : (V.t list, string) result =
  match
    let rows = parse t.dialect s in
    let names = List.map (fun (f : T.field) -> f.name) t.fields in
    let first, rows =
      if t.dialect.header then
        match rows with
        | h :: rest -> if h <> names then bad "row 1: the header %s is not the fields %s" (String.concat "," h) (String.concat "," names) else (2, rest)
        | [] -> bad "no header"
      else (1, rows)
    in
    let width = List.length names in
    let values =
      List.mapi
        (fun k r ->
          let rn = first + k in
          if List.length r <> width then bad "row %d: %d columns, not %d" rn (List.length r) width;
          let props =
            List.concat
              (List.map2
                 (fun (f : T.field) c ->
                   if List.mem c t.missing then []
                   else match cell f c with j -> [ (f.name, j) ] | exception Bad m -> bad "row %d, column %s: %s" rn f.name m)
                 t.fields r)
          in
          match C.read t.imp t.row (`Assoc props) with Ok v -> (v, props) | Error m -> bad "row %d: %s" rn m)
        rows
    in
    (* unique and primary keys: no two rows with every key cell present share them *)
    List.iter
      (fun keys ->
        let seen = Hashtbl.create 16 in
        List.iteri
          (fun k (_, props) ->
            let vals = List.map (fun key -> List.assoc_opt key props) keys in
            if List.for_all Option.is_some vals then begin
              let key = J.to_string (`List (List.map Option.get vals)) in
              (match Hashtbl.find_opt seen key with
               | Some k0 -> bad "row %d: %s repeats row %d's" (first + k) (String.concat ", " keys) (first + k0)
               | None -> ());
              Hashtbl.replace seen key k
            end)
          values)
      t.unique;
    List.map fst values
  with
  | v -> Ok v
  | exception Bad m -> Error m

let write (t : T.t) (rows : V.t list) : (string, string) result =
  match
    let header = if t.dialect.header then [ List.map (fun (f : T.field) -> f.name) t.fields ] else [] in
    let body =
      List.mapi
        (fun k v ->
          let props = match C.write t.imp t.row v with Ok (`Assoc kv) -> kv | Ok _ -> bad "row %d: not a record" k | Error m -> bad "row %d: %s" k m in
          List.map
            (fun (f : T.field) ->
              match List.assoc_opt f.name props with
              | None | Some `Null -> (match t.missing with m :: _ -> m | [] -> bad "%s: absent, and the table has no missing value" f.name)
              | Some j ->
                  let s = text f j in
                  if List.mem s t.missing then bad "%s: %S reads back as missing" f.name s;
                  s)
            t.fields)
        rows
    in
    print t.dialect (header @ body)
  with
  | s -> Ok s
  | exception Bad m -> Error m
