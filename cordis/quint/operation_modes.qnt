// -*- mode: Bluespec; -*-
/**
 * operation_modes: operation.qnt's design, worlds and mutant.
 *
 *  - design: the executor may restart and holds a retention promise;
 *  - promised: no restart, a promise: nothing is ever unknown, so
 *    noOvercharge holds;
 *  - cacheLike: no restart, no promise (eviction under load): noOvercharge
 *    is violated, as the ledger pays the worst case for a result evicted
 *    before capture; accounted still holds;
 *  - resubmitSame, resubmitSameEvict: an unknown attempt sent again under
 *    the same identity breaks accounted (after a restart; after an
 *    eviction alone).
 */
module design {
  import operation(RESTART = true, PROMISE = true, RESUBMIT_SAME = false).* from "./operation"
}
module promised {
  import operation(RESTART = false, PROMISE = true, RESUBMIT_SAME = false).* from "./operation"
}
module cacheLike {
  import operation(RESTART = false, PROMISE = false, RESUBMIT_SAME = false).* from "./operation"
}
module resubmitSame {
  import operation(RESTART = true, PROMISE = true, RESUBMIT_SAME = true).* from "./operation"
}
module resubmitSameEvict {
  import operation(RESTART = false, PROMISE = false, RESUBMIT_SAME = true).* from "./operation"
}
