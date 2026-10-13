// -*- mode: Bluespec; -*-
/**
 * observe_report: observe.qnt's worlds and mutants for what a keyed answer
 * reports (docs/cordis/rfc/0006-turn-runtime.md, the halo-flash runs).
 * Kept apart from observe.qnt only so that each file's IR stays under
 * Apalache's request limit.
 *
 *  - releaseUnreported: the rule before Halo-Answer, an answer that says
 *    nothing releases the other sends (witness supersededReleased);
 *  - keyIgnoredRelease: with it, a world that keeps no keys breaks
 *    accounted (keyIgnored, which says nothing and so is charged, holds it);
 *  - graceExpiryRelease: with it, grace expiry breaks accounted;
 *  - graceExpiryDiscarded: the world says what it discarded under the key
 *    (Halo-Discarded-Tokens), and a fresh answer charges the other sends
 *    that, not their worst case: accounted holds (witness exactCharged);
 *  - underReport: it reports only the latest stopped send's tokens ->
 *    accounted.
 */
module releaseUnreported {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = true, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
module keyIgnoredRelease {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = true, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = true, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
module graceExpiryRelease {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = false, RELEASE_UNREPORTED = true, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
module graceExpiryDiscarded {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = true, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
module underReport {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = true, UNDER_REPORT = true, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}

module expireReported {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = true, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
module expireUnder {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = true, EXPIRE_UNDER = true, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
