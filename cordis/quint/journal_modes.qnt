// -*- mode: Bluespec; -*-
/**
 * journal_modes: journal.qnt's design and mutants (see there).
 */
module design {
  import journal(LATEST_WINS = false, ADJUST_AGAIN = false, ANSWER_EXPIRED = false, IGNORE_STORE = false).* from "./journal"
}
module latestWins {
  import journal(LATEST_WINS = true, ADJUST_AGAIN = false, ANSWER_EXPIRED = false, IGNORE_STORE = false).* from "./journal"
}
module adjustAgain {
  import journal(LATEST_WINS = false, ADJUST_AGAIN = true, ANSWER_EXPIRED = false, IGNORE_STORE = false).* from "./journal"
}
module answerExpired {
  import journal(LATEST_WINS = false, ADJUST_AGAIN = false, ANSWER_EXPIRED = true, IGNORE_STORE = false).* from "./journal"
}
module ignoreStore {
  import journal(LATEST_WINS = false, ADJUST_AGAIN = false, ANSWER_EXPIRED = false, IGNORE_STORE = true).* from "./journal"
}
