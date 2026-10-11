// -*- mode: Bluespec; -*-
/**
 * observe_modes: observe.qnt's design and its mutants and worlds, as
 * observe.qnt describes them. The instances live apart from the module
 * (here, and in observe_report.qnt) so that each file's IR stays under
 * Apalache's request limit.
 */
module design {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module misclassify {
  import observe(MISCLASSIFY = true, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module refundLost {
  import observe(MISCLASSIFY = false, REFUND_LOST = true, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module noReserve {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = true, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module applyLate {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = true, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module oneClass {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = true, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module keyIgnored {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = true, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module releaseObs {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = true, GRACE_EXPIRY = false, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module graceExpiry {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = false, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module graceExpiryReported {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
module reported {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = false, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = false, UNDER_REPORT = false).* from "./observe"
}
