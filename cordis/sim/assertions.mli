(** The assertion vocabulary of the Antithesis SDK, so cell and kernel code
    written against it runs unchanged under Antithesis later.

    A catalog is shared by every run of a campaign. [always] fails the
    campaign on the first false; [sometimes] and [reachable] are witnesses
    that must hold at least once across the campaign; [unreachable] fails
    when hit. The verdict follows casselc/observability's model-verdict
    rules: a violated [always] or a hit [unreachable] fails; an unreached
    witness warns (a coverage gap, never a pass); an empty campaign fails. *)

type t

val create : unit -> t
val always : t -> bool -> string -> unit
val always_or_unreachable : t -> bool -> string -> unit
val sometimes : t -> bool -> string -> unit
val reachable : t -> string -> unit
val unreachable : t -> string -> unit
val setup_complete : t -> unit
val setup_done : t -> bool

val declare_witness : t -> string -> unit
(** Name a [sometimes]/[reachable] witness up front, so that one never
    evaluated still reports as unreached. *)

val runs : t -> int
val end_run : t -> unit

type line = Fail of string | Warn of string | Ok_ of string
type verdict = { lines : line list; failed : bool }

val verdict : t -> verdict
val first_failure : t -> string option
val print_verdict : Format.formatter -> verdict -> unit
