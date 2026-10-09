(** The one entropy oracle. Every choice the simulator faces — which enabled
    step runs next, which receipt comes back, whether a lost intent took
    effect — is a draw from here, so a run is a function of its oracle.

    [splitmix] is a seeded oracle for plain runs and replays. A Hegel-backed
    oracle is the same record built over a Hegel test case's draws, so a
    failing run shrinks to a minimal schedule and fault set. *)

type t = {
  int : int -> int;  (** [int n] is uniform in [0, n); [n > 0] *)
  label : string;  (** for reports: the seed, or the engine's name *)
}

val splitmix : int -> t

val choose : t -> 'a list -> 'a
(** One element; the list must not be empty. *)

val chance : t -> int -> bool
(** [chance o pct] is true with probability [pct]%. *)

val recording : t -> t * (unit -> int list)
(** Wrap an oracle so every answer is recorded; the second component reads
    the answers so far, oldest first. *)

val replay : int list -> t
(** An oracle answering from a recorded list (then zeros): a run replays
    exactly. *)
