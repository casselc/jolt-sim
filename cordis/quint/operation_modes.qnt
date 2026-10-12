// -*- mode: Bluespec; -*-
/**
 * operation_modes: operation.qnt's design, worlds and mutants.
 *
 *  - design: restarts, duplicated submits, a retention promise, all three
 *    rules (fencing, tombstones, abandon only by a tombstoning cancel);
 *  - promised: no restart, a promise: nothing is ever unknown, so
 *    noOvercharge holds (a lost submit abandoned costs nothing);
 *  - cacheLike: no restart, no promise (eviction under load):
 *    noOvercharge is violated, as the ledger pays the worst case for a
 *    result evicted before it was acked; accounted still holds;
 *  - noFence, noTomb, abandonUntombed, cacheNoTomb: each rule broken
 *    (runsOnce, accounted, runsOnce, accounted-or-runsOnce);
 *  - resubmitSame: an unknown attempt sent again under the same identity
 *    breaks accounted.
 */
module design {
  import operation(RESTART = true, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module promised {
  import operation(RESTART = false, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module cacheLike {
  import operation(RESTART = false, PROMISE = false, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module noFence {
  import operation(RESTART = true, PROMISE = true, DUP = true, NO_FENCE = true, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module noTomb {
  import operation(RESTART = false, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = true, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module abandonUntombed {
  import operation(RESTART = false, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = true, CACHE_TOMB = false, RESUBMIT_SAME = false).* from "./operation"
}
module cacheNoTomb {
  import operation(RESTART = false, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = true, RESUBMIT_SAME = false).* from "./operation"
}
module resubmitSame {
  import operation(RESTART = true, PROMISE = true, DUP = true, NO_FENCE = false, NO_TOMB = false, ABANDON_UNTOMBED = false, CACHE_TOMB = false, RESUBMIT_SAME = true).* from "./operation"
}
