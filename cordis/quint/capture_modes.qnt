// -*- mode: Bluespec; -*-
/**
 * capture_modes: capture.qnt's design and mutants (see there).
 */
module design {
  import capture(ACK_FIRST = false, CLEAR_EARLY = false, NO_KEY = false).* from "./capture"
}
module ackFirst {
  import capture(ACK_FIRST = true, CLEAR_EARLY = false, NO_KEY = false).* from "./capture"
}
module clearEarly {
  import capture(ACK_FIRST = false, CLEAR_EARLY = true, NO_KEY = false).* from "./capture"
}
module noKey {
  import capture(ACK_FIRST = false, CLEAR_EARLY = false, NO_KEY = true).* from "./capture"
}
