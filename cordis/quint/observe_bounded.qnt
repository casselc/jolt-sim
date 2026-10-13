// -*- mode: Bluespec; -*-
/**
 * observe_bounded: observe.qnt's bounded-report world and its mutant (RFC 0008 revision 6), kept in a file of their
 * own so that each file's IR stays under Apalache's request limit (see observe_modes.qnt).
 *  - expireBounded: an expired attempt's report may be `bounded`, with unanswered work inside it charged at its reserved
 *    maximum: `accounted` holds (witness boundedW);
 *  - dropUnresolved: a bounded report is settled at its confirmed part only -> accounted.
 */
module expireBounded {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = true, EXPIRE_UNDER = false, BOUNDED = true, DROP_UNRESOLVED = false).* from "./observe"
}
module dropUnresolved {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = true, EXPIRE_UNDER = false, BOUNDED = true, DROP_UNRESOLVED = true).* from "./observe"
}
