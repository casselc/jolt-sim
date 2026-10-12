// -*- mode: Bluespec; -*-
/**
 * expiry_modes: expiry.qnt's design and mutants (see there).
 */
module design {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = false, GUESS_DEADLINE = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module dropOutcome {
  import expiry(DROP_OUTCOME = true, CLOSED_EARLY = false, GUESS_DEADLINE = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module closedEarly {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = true, GUESS_DEADLINE = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module pruneEarly {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = false, GUESS_DEADLINE = false, PRUNE_EARLY = true, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
module lateAccept {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = false, GUESS_DEADLINE = false, PRUNE_EARLY = false, LATE_ACCEPT = true, CLOCK_BACK = false).* from "./expiry"
}
module clockBack {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = false, GUESS_DEADLINE = false, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = true).* from "./expiry"
}
module guessDeadline {
  import expiry(DROP_OUTCOME = false, CLOSED_EARLY = false, GUESS_DEADLINE = true, PRUNE_EARLY = false, LATE_ACCEPT = false, CLOCK_BACK = false).* from "./expiry"
}
