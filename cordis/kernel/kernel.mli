(** The Cordis lifecycle calculus, as total pure functions.

    This is the calculus of Cordis §4.2 (Shi, Zhang and Cui, "A Programming
    Paradigm for Spatiotemporal Composability") over a registry of fibers,
    with the additions of docs/cordis/DESIGN.md "The model":

    - effects are classed by the system boundary: stages are internal
      (they return an inverse), and an iteration may end in one emission,
      an [intent] that is released only once its turn has committed;
    - [Uncertain]: an emission whose outcome was lost holds the fiber, which
      accepts only [Reconcile] and cannot unload;
    - turns: an iteration's stages and its intent commit together;
    - compensation: a released intent may name a compensating intent, which
      unloading issues in place of an inverse;
    - failure (Cordis §4.4): a [Failed] receipt unwinds the partial
      accumulator and leaves the fiber [Failed], for a supervisor to restart.

    Nothing here performs IO or reads a clock. A shell (the simulator, later
    an Eio runtime) changes state only by calling [apply] and [orchestrate],
    and it alone decides which enabled step happens next. *)

type key = string
type fid = int

module Keys : Set.S with type elt = key
module Kmap : Map.S with type key = key
module Fmap : Map.S with type key = fid

(** {1 Components} *)

type op =
  | Put of int  (** overwrite; does not commute with another [Put] *)
  | Add of int  (** increment; commutes with every other [Add] *)

type stage =
  | Provide of key * int  (** bind a provided key in the fiber's own table *)
  | Op of key * op
      (** operate on a declared key, in whichever table holds it: the
          fiber's own for a provided key, the provider's (through the
          committed view) for a required one *)

type intent = {
  kind : string;  (** the intent kind, e.g. ["llm.complete"] *)
  ikey : string;
      (** idempotency key. A component names one per emission; the kernel
          appends ["@" ^ episode] when it releases the intent. *)
  compensate : string option;  (** the compensating intent kind, if any *)
}

type iteration = { stages : stage list; emit : intent option }

type component = {
  name : string;
  requires : Keys.t;  (** specification d *)
  provides : Keys.t;  (** provision p *)
  iters : iteration list;  (** the effect iterator, unrolled *)
}

(** {1 The registry} *)

type inverse =
  | Restore of fid * key * int option
      (** put the binding back as it was ([None]: remove it) *)
  | Sub of fid * key * int  (** the inverse of an [Add] *)
  | Compensate of intent  (** a released emission: issue its compensation *)
  | Irreversible of intent  (** a released emission with no compensation *)

type view = fid Kmap.t
(** A view maps each declared key to the fiber providing it. *)

type receipt = Definite | Failed | Lost
(** What came back for a released intent. [Lost] means published, outcome
    unknown, and puts the fiber in [Uncertain]. *)

type life =
  | Inactive
  | Reloading of {
      rest : iteration list;
      acc : inverse list;  (** newest first: applied head first *)
      committed : view;
      awaiting : intent option;  (** a released intent with no receipt yet *)
    }
  | Active of { acc : inverse list; committed : view }
  | Uncertain of {
      rest : iteration list;
      acc : inverse list;
      committed : view;
      pending : intent;
    }
  | Unloading of { acc : inverse list; committed : view; failing : bool }
  | Failed

type fiber = {
  comp : component;
  parent : fid option;
  table : int Kmap.t;  (** sigma: this fiber's bindings *)
  retired : bool;  (** tau *)
  life : life;
  episode : int;
      (** how many times this fiber has begun (Cordis's episodes). A released
          intent's idempotency key carries it, so a restarted fiber's
          emissions are new emissions, not duplicates of the last episode's. *)
}

type registry = fiber Fmap.t

val empty : registry

(** {1 Reading the registry} *)

val installed : registry -> fid -> bool
(** Reloading, Active, Uncertain or Unloading. *)

val committed : registry -> fid -> view option
val provider : registry -> key -> fid option
(** The Active fiber whose table binds the key. *)

val target : registry -> fid -> view option
(** [None]: the fiber should not be running (retired, or a declared key has
    no Active provider). *)

val relied : registry -> fid -> bool
(** Some other installed fiber's committed view names this one: the guard
    on [Unload]. *)

val quiet : registry -> bool
(** Every fiber has settled at its target view, with no transition, receipt
    or reconciliation outstanding. [Failed] fibers are excluded, as Cordis
    excludes failed fibers from confluence. *)

val bindings : registry -> (fid * key * int) list
(** Every binding of every table, sorted. *)

(** {1 Steps} *)

type rule = Begin | Iter | Finish | Divert | Leave | Unload

val rule_name : rule -> string

val next : registry -> fid -> rule option
(** The lifecycle rule whose premises hold for this fiber, if any. At most
    one does: the states are exclusive and each state's rules split on
    whether the target equals the committed view. *)

val enabled : registry -> (fid * rule) list

type output =
  | Released of fid * intent  (** hand to the outbox now: its turn committed *)
  | Compensated of fid * intent  (** a compensating intent to issue *)

type error = string

val apply : registry -> fid -> rule -> (registry * output list, error) result
(** Take one lifecycle step. [Error] when the rule's premises do not hold. *)

type orch =
  | Insert of fid * component * fid option
  | Retire of fid
  | Remove of fid
  | Receive of fid * receipt  (** the outbox's receipt for the awaited intent *)
  | Reconcile of fid * bool
      (** the outcome of reading the world for an Uncertain intent: did it
          take effect? *)
  | Restart of fid  (** a supervisor's decision: Failed back to Inactive *)

val orch_name : orch -> string

val orchestrate : registry -> orch -> (registry, error) result
(** An external input. [Insert] checks freshness, the parent, single
    provision and confinement (every stage names a declared key). *)

val check_component : component -> (unit, error) result
