(* A scenario: the components of one run, in the shape the Quint model
   (quint/lifecycle.qnt) abstracts, so that a run's trace can be checked
   against the model with the same constants. *)

open Cordis_kernel

type shape = {
  fid : Kernel.fid;
  requires : string list;
  provides : string list;
  iters : int;
  prov_at : int;  (** the iteration that binds every provided key; -1 for none *)
  emit_at : int list;  (** iterations that end in an emission *)
  compensated : bool;  (** emissions name a compensating intent *)
  op : [ `Add | `Put ];  (** what each iteration does to the required keys *)
  restart : Order.restart;
  retirable : bool;
}

type t = { name : string; shapes : shape list; max_restarts : int; period : int }

let provided_value fid = 10 * fid

let intent_key fid i = Printf.sprintf "%d:%d" fid i
let iid fid i = (fid * 100) + i

let component s =
  let iteration i =
    let provide =
      if i = s.prov_at then List.map (fun k -> Kernel.Provide (k, provided_value s.fid)) s.provides
      else []
    in
    let ops =
      List.map
        (fun k -> Kernel.Op (k, match s.op with `Add -> Kernel.Add 1 | `Put -> Kernel.Put (s.fid + i)))
        s.requires
    in
    let emit =
      if List.mem i s.emit_at then
        Some
          {
            Kernel.kind = Printf.sprintf "call.%d" s.fid;
            ikey = intent_key s.fid i;
            compensate = (if s.compensated then Some (Printf.sprintf "undo.%d" s.fid) else None);
          }
      else None
    in
    { Kernel.stages = provide @ ops; emit }
  in
  {
    Kernel.name = Printf.sprintf "c%d" s.fid;
    requires = Kernel.Keys.of_list s.requires;
    provides = Kernel.Keys.of_list s.provides;
    iters = List.init s.iters iteration;
  }

let shape ?(requires = []) ?(provides = []) ?(iters = 1) ?prov_at ?(emit_at = [])
    ?(compensated = false) ?(op = `Add) ?(restart = Order.Transient) ?(retirable = true) fid =
  let prov_at = match prov_at with Some p -> p | None -> if provides = [] then -1 else 0 in
  { fid; requires; provides; iters; prov_at; emit_at; compensated; op; restart; retirable }

(* The Quint `design` instance: 1 provides db; 2 reads db, provides api and
   emits on its first iteration; 3 reads api and emits. *)
let chain =
  {
    name = "chain";
    shapes =
      [
        shape 1 ~provides:[ "db" ] ~iters:1;
        shape 2 ~requires:[ "db" ] ~provides:[ "api" ] ~iters:2 ~prov_at:1 ~emit_at:[ 0 ]
          ~compensated:true ~retirable:false;
        shape 3 ~requires:[ "api" ] ~iters:1 ~emit_at:[ 0 ];
      ];
    max_restarts = 3;
    period = 50;
  }

(* A random acyclic scenario: fiber i may read keys provided by fibers < i. *)
let random (o : Oracle.t) ~fibers =
  let key i = Printf.sprintf "k%d" i in
  let shapes =
    List.init fibers (fun j ->
        let fid = j + 1 in
        let provides = if Oracle.chance o 70 then [ key fid ] else [] in
        let requires =
          List.filter_map
            (fun p -> if Oracle.chance o 40 then Some (key p) else None)
            (List.init j (fun p -> p + 1))
        in
        let iters = 1 + o.int 3 in
        let prov_at = if provides = [] then -1 else o.int iters in
        let emit_at = List.filter (fun _ -> Oracle.chance o 40) (List.init iters Fun.id) in
        shape fid ~requires ~provides ~iters ~prov_at ~emit_at ~compensated:(Oracle.chance o 50)
          ~retirable:(Oracle.chance o 60))
  in
  (* a key nobody provides cannot be required *)
  let provided = List.concat_map (fun s -> s.provides) shapes in
  let shapes =
    List.map (fun s -> { s with requires = List.filter (fun k -> List.mem k provided) s.requires }) shapes
  in
  { name = "random"; shapes; max_restarts = 3; period = 40 }

(* ---- Quint constants for this scenario ------------------------------------------------ *)

let quint_set f l = "Set(" ^ String.concat ", " (List.map f l) ^ ")"
let quint_str s = "\"" ^ s ^ "\""
let quint_map f (shapes : shape list) =
  "Map(" ^ String.concat ", " (List.map (fun s -> Printf.sprintf "%d -> %s" s.fid (f s)) shapes) ^ ")"

let quint_constants ?(guard = true) ?(reconcile = true) t =
  [
    ("FIBERS", quint_set (fun s -> string_of_int s.fid) t.shapes);
    ("REQ", quint_map (fun s -> quint_set quint_str s.requires) t.shapes);
    ("PROV", quint_map (fun s -> quint_set quint_str s.provides) t.shapes);
    ("ITERS", quint_map (fun s -> string_of_int s.iters) t.shapes);
    ("PROV_AT", quint_map (fun s -> string_of_int s.prov_at) t.shapes);
    ("EMIT_AT", quint_map (fun s -> quint_set string_of_int s.emit_at) t.shapes);
    ( "RETIRABLE",
      quint_set (fun s -> string_of_int s.fid) (List.filter (fun s -> s.retirable) t.shapes) );
    ("GUARD", string_of_bool guard);
    ("RECONCILE", string_of_bool reconcile);
  ]
