// -*- mode: Bluespec; -*-
/**
 * observe_forget: observe.qnt with a world that forgets keys (halo-flash
 * forgets a key unused for replay_ttl_s, 600 s, or evicted past 4096 keys,
 * and with it the generations, discarded tokens and sends it held).
 *
 *  - forgetDiscarded: the round-5 rule (Halo-Answer, and the key's
 *    cumulative Halo-Discarded-Tokens on every answer) under forgetting: a
 *    send admitted before the world forgot the key is in no count, and a
 *    later replay reporting 0 releases it -> accounted;
 *  - forgetAttempts: every answer also says how many sends the world
 *    admitted under the key since it last forgot it, and a send it does
 *    not count is charged its worst case: accounted holds (witnesses
 *    exactCharged, forgotThenDone).
 */
module forgetDiscarded {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = true, UNDER_REPORT = false, FORGET = true, ATTEMPTS = false, EXPIRE_REPORTED = false, EXPIRE_UNDER = false).* from "./observe"
}
module forgetAttempts {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false, KEY_IGNORED = false, RELEASE_OBS = false, GRACE_EXPIRY = true, REPORTED = true, RELEASE_UNREPORTED = false, DISCARDED = true, UNDER_REPORT = false, FORGET = true, ATTEMPTS = true, EXPIRE_REPORTED = false, EXPIRE_UNDER = false).* from "./observe"
}
