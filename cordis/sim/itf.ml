(* Model-level ITF traces (the Informal Trace Format, as `quint run --mbt`
   writes them) over the variables of quint/lifecycle.qnt, so that
   `quintgo validate` can replay a simulator run in the model. *)

open Cordis_kernel

type value = Yojson.Safe.t

let int n : value = `Assoc [ ("#bigint", `String (string_of_int n)) ]
let set l : value = `Assoc [ ("#set", `List l) ]
let map pairs : value = `Assoc [ ("#map", `List (List.map (fun (k, v) -> `List [ k; v ]) pairs)) ]
let variant tag v : value = `Assoc [ ("tag", `String tag); ("value", v) ]
let unit_variant tag = variant tag (`Assoc [ ("#tup", `List []) ])
let some v = variant "Some" v

type world = { applied : (int * int) list; hidden : (int * bool) list }

let life_value (s : Scenario.shape) (life : Kernel.life) =
  let pc rest = s.iters - List.length rest in
  match life with
  | Kernel.Inactive -> unit_variant "Inactive"
  | Reloading { rest; awaiting = None; _ } -> variant "Reloading" (int (pc rest))
  | Reloading { rest; awaiting = Some _; _ } -> variant "Awaiting" (int (pc rest))
  | Active _ -> unit_variant "Active"
  | Uncertain { rest; _ } -> variant "Uncertain" (int (pc rest))
  | Unloading { failing; _ } -> variant "Unloading" (`Bool failing)
  | Failed -> unit_variant "Failed"

(* The model's variables, projected from the registry and the world. *)
let vars (sc : Scenario.t) (reg : Kernel.registry) (w : world) : (string * value) list =
  let per f = map (List.map (fun (s : Scenario.shape) -> (int s.fid, f s)) sc.shapes) in
  let fiber s = Kernel.Fmap.find s.Scenario.fid reg in
  [
    ("life", per (fun s -> life_value s (fiber s).life));
    ( "view",
      per (fun s ->
          match Kernel.committed reg s.fid with
          | Some w -> map (Kernel.Kmap.bindings w |> List.map (fun (k, m) -> (`String k, int m)))
          | None -> map []) );
    ("retired", per (fun s -> `Bool (fiber s).retired));
    ("episode", per (fun s -> int (fiber s).episode));
    ( "bound",
      per (fun s -> set (Kernel.Kmap.bindings (fiber s).table |> List.map (fun (k, _) -> `String k))) );
    ("applied", map (List.map (fun (i, c) -> (int i, int c)) w.applied));
    ("hidden", map (List.map (fun (n, b) -> (int n, `Bool b)) w.hidden));
  ]

type step = { action : string; picks : (string * value) list; state : (string * value) list }

let trace ~spec ~module_ ~constants ~invariants ~actor ~description (steps : step list) : value =
  let var_names = match steps with s :: _ -> List.map fst s.state | [] -> [] in
  let state i s =
    `Assoc
      ([
         ("#meta", `Assoc [ ("index", `Int i) ]);
         ("mbt::actionTaken", `String s.action);
         ("mbt::nondetPicks", `Assoc (List.map (fun (k, v) -> (k, some v)) s.picks));
       ]
      @ s.state)
  in
  `Assoc
    [
      ( "#meta",
        `Assoc
          [
            ("format", `String "ITF");
            ("source", `String "cordis-sim");
            ("description", `String description);
            ( "quintgo",
              `Assoc
                [
                  ("spec", `String spec);
                  ("module", `String module_);
                  ("constants", `Assoc (List.map (fun (k, v) -> (k, `String v)) constants));
                  ("invariants", `List (List.map (fun s -> `String s) invariants));
                  ("actor", `String actor);
                ] );
          ] );
      ("vars", `List (List.map (fun s -> `String s) var_names));
      ("states", `List (List.mapi state steps));
    ]

let write path (v : value) =
  let oc = open_out path in
  Yojson.Safe.pretty_to_channel oc v;
  output_char oc '\n';
  close_out oc
