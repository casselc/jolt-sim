(** The pure decisions of supervision, ported from ensemble's [ensemble.order].

    A child is [(id, restart)]. [restart_ok] decides whether an exit earns a
    restart at all; [restart_plan] says which running children the strategy
    stops first (stop order: start order reversed) and which it then starts
    (start order); [intensity] decides whether the restart is allowed or
    whether too many restarts inside the period mean the supervisor gives up. *)

type restart = Permanent | Transient | Temporary
type reason = Normal | Shutdown | Crash of string
type strategy = One_for_one | One_for_all | Rest_for_one

val restart_ok : restart -> reason -> bool
(** [Permanent] always, [Temporary] never, [Transient] unless the reason is an
    orderly stop ([Normal] or [Shutdown]). *)

val restart_plan : strategy -> ('id * restart) list -> 'id -> 'id list * 'id list
(** [(stop, start)] for restarting [id], which has exited. [stop] is the other
    affected children in stop order; [start] is the affected children to
    start again in start order: [id] itself and every stopped child that is
    not [Temporary]. An id that is not a child plans nothing. *)

val stop_order : 'id list -> 'id list

type verdict = Allow of int list | Exceed of int list

val intensity : times:int list -> now:int -> period:int -> max_r:int -> verdict
(** Record a restart at [now] against the restart [times] so far. At most
    [max_r] restarts may fall within [period]; one more is [Exceed]. The list
    returned is the restarts still inside the period, [now] included. *)
