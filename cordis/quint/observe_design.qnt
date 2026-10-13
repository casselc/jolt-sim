// -*- mode: Bluespec; -*-
/**
 * observe_design: observe.qnt's design alone, for Apalache (`verify observe_design.qnt design safety`). Every instance
 * of observe.qnt adds to a file's IR, and observe_modes.qnt's has passed Apalache's request limit with the bounded-report
 * constants; the sim rows still use observe_modes.qnt.
 */
module design {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false, FORGET = false, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false, BOUNDED = false, DROP_UNRESOLVED = false).* from "./observe"
}
