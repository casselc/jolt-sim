// -*- mode: Bluespec; -*-
/**
 * expiry_modes: expiry.qnt's design and mutants (see there).
 */
module design {
  import expiry(ABSENT_ALWAYS = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module absentAlways {
  import expiry(ABSENT_ALWAYS = true, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module pruneEarly {
  import expiry(ABSENT_ALWAYS = false, PRUNE_EARLY = true, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module lateAccept {
  import expiry(ABSENT_ALWAYS = false, PRUNE_EARLY = false, LATE_ACCEPT = true, CLOCK_BACK = false).* from "./expiry"
}
module clockBack {
  import expiry(ABSENT_ALWAYS = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = true).* from "./expiry"
}
