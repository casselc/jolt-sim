(* A small proto3 schema and every kind of change RFC 0004's
   compatibility table names, each with whether it breaks: shared by the
   classifier's tests and the graph admission tests. *)

open Desc_build

(* the base schema *)
let base =
  { pkg = "t"; syntax = "proto3";
    enums = [ { ename = "E"; values = [ ("Z", 0); ("A", 1); ("B", 2) ] } ];
    messages =
      [ { mname = "N"; oneofs = []; fields = [ field "x" 1 5 ] };
        { mname = "M"; oneofs = [ "k" ];
          fields =
            [ field "i" 1 5; field "l" 2 3; field "s" 3 9; field "b" 4 8; field "e" 5 14 ~tref:"t.E";
              field "n" 6 11 ~tref:"t.N"; field "r" 7 5 ~label:3; field "o" 8 9 ~optional:true;
              field "ka" 9 9 ~oneof:0; field "kb" 10 5 ~oneof:0 ] } ] }

(* ---- changes ----------------------------------------------------------------------------------- *)

let on_message name f (file : file) =
  { file with messages = List.map (fun m -> if m.mname = name then f m else m) file.messages }

let on_field mname fname g = on_message mname (fun m -> { m with fields = List.map (fun f -> if f.fname = fname then g f else f) m.fields })
let on_enum f (file : file) = { file with enums = List.map f file.enums }

(* (name, breaking, change) *)
let changes : (string * bool * (file -> file)) list =
  [ ("nothing", false, Fun.id);
    ("a field added", false, on_message "M" (fun m -> { m with fields = m.fields @ [ field "added" 20 5 ] }));
    ("a field added beneath (in a message a field holds)", false, on_message "N" (fun m -> { m with fields = m.fields @ [ field "y" 2 9 ] }));
    ("an enum value added", false, on_enum (fun e -> { e with values = e.values @ [ ("C", 3) ] }));
    ("a field removed", true, on_message "M" (fun m -> { m with fields = List.filter (fun f -> f.fname <> "i") m.fields }));
    ("a field renumbered", true, on_field "M" "i" (fun f -> { f with num = 21 }));
    ("a field renamed", true, on_field "M" "i" (fun f -> { f with fname = "ii"; json = Some "ii" }));
    ("a field's JSON name changed", true, on_field "M" "s" (fun f -> { f with json = Some "sss" }));
    ("a field retyped (int32 to int64)", true, on_field "M" "i" (fun f -> { f with ty = 3 }));
    ("an enum field retyped to int32", true, on_field "M" "e" (fun f -> { f with ty = 5; tref = None }));
    ("a field made repeated", true, on_field "M" "s" (fun f -> { f with label = 3 }));
    ("a repeated field made singular", true, on_field "M" "r" (fun f -> { f with label = 1 }));
    ("a field lost presence", true, on_field "M" "o" (fun f -> { f with optional = false }));
    ("a field gained presence", true, on_field "M" "s" (fun f -> { f with optional = true }));
    ("a field moved out of its oneof", true, on_field "M" "kb" (fun f -> { f with oneof = None }));
    ("a field retyped beneath", true, on_field "N" "x" (fun f -> { f with ty = 9 }));
    ("an enum value removed", true, on_enum (fun e -> { e with values = List.filter (fun (n, _) -> n <> "B") e.values }));
    ("an enum value renumbered", true, on_enum (fun e -> { e with values = List.map (fun (n, v) -> if n = "A" then (n, 3) else (n, v)) e.values }));
    ("an enum value renamed", true, on_enum (fun e -> { e with values = List.map (fun (n, v) -> if n = "A" then ("AA", v) else (n, v)) e.values }));
    ("the syntax changed", true, fun f -> { f with syntax = "proto2" });
    ("a message removed", true, fun f -> { f with messages = List.filter (fun m -> m.mname <> "N") f.messages }) ]

