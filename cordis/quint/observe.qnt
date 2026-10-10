// -*- mode: Bluespec; -*-
/**
 * observe: observational emissions (docs/cordis/rfc/0006-turn-runtime.md,
 * revision 2, "Observational emissions").
 *
 * DESIGN.md's emission rule is "reconcile, never blind retry": a lost
 * receipt puts the fiber in Uncertain until a reconcile read asks the
 * world whether the intent's key took effect. Most inference calls and
 * read-only tool calls cannot answer that question, and need not: their
 * only effect beyond the answer is spend. This model adds a second kind of
 * emission, declared by the intent kind and granted by policy:
 *
 *  - an **effectful** intent (Eff) keeps the rule: a lost receipt makes
 *    the fiber Uncertain, and only a reconcile moves it on;
 *  - an **observational** intent (Obs) may be sent again after a lost
 *    receipt, as a new attempt, each attempt under its own reservation of
 *    its worst-case cost. An abandoned attempt's reservation is held until
 *    its late answer settles it at its actual cost, or it expires and is
 *    charged at the worst case; its answer is never applied. Attempts are
 *    bounded; when they run out the fiber fails, as nothing is uncertain.
 *
 * One fiber emits the intents KIND[0], KIND[1], ... in order. The world
 * may run an attempt or not, answer it or lose the answer, and answer
 * late. Real spend is a ghost: what the world actually charged.
 *
 * Properties:
 *  - effectOnce: an effectful intent takes effect at most once;
 *  - receiptOnce: one answer is applied per intent;
 *  - withinBudget: settled plus reserved spend never exceeds the budget;
 *  - accounted: real spend never exceeds settled plus reserved spend (a
 *    lost attempt is never assumed free);
 *  - obsNeverUncertain: an observational intent never makes the fiber
 *    Uncertain (the point of the class: no reconcile it cannot answer).
 * Mutants:
 *  - MISCLASSIFY (`misclassify`): an effectful kind is treated as
 *    observational (a cell, not policy, chose its class) -> effectOnce;
 *  - REFUND_LOST (`refundLost`): an expired attempt is refunded, not
 *    charged -> accounted;
 *  - NO_RESERVE (`noReserve`): an attempt is sent without a reservation,
 *    settled after -> withinBudget;
 *  - APPLY_LATE (`applyLate`): an abandoned attempt's late answer is also
 *    applied -> receiptOnce;
 *  - ONE_CLASS (`oneClass`): DESIGN.md's rule before this class, every
 *    lost receipt is Uncertain -> obsNeverUncertain (an inference call
 *    waits on a reconcile no provider can answer).
 * Witnesses (checked as not(w), expected VIOLATED): retriedThenDone,
 * lateSettled, exhausted, effectfulUncertain, reconciled.
 */
module observe {
  const MISCLASSIFY: bool
  const REFUND_LOST: bool
  const NO_RESERVE: bool
  const APPLY_LATE: bool
  const ONE_CLASS: bool

  type Kind = Obs | Eff
  type Phase = Ready | Sent | Lost | Uncertain | Failed | Done

  pure val KIND: List[Kind] = [Obs, Eff, Obs]
  pure val N = 3
  pure val R = 2          // an attempt's worst-case cost: its reservation
  pure val B = 9          // the fiber's budget
  pure val MAXA = 3       // attempts per observational intent
  pure val NONE = -1

  // how the runtime treats an intent: by its declared kind, unless misclassified
  pure def retryable(k: Kind): bool = (k == Obs and not(ONE_CLASS)) or MISCLASSIFY

  type Attempt = { intent: int, n: int, ran: bool, cost: int, live: bool }

  type S = {
    pc: int,                 // the intent being emitted
    phase: Phase,
    tries: int,              // attempts of the current intent
    cur: int,                // the current attempt's index in [attempts], or NONE
    attempts: List[Attempt], // every attempt sent; live while its answer may come
    reserved: int,
    settled: int,
    real: int,               // ghost: what the world charged
    effects: List[int],      // ghost: per intent, times it took effect
    applied: List[int],      // per intent, answers applied
    lates: int, retries: int, reconciles: int,
  }

  var s: S

  action init = s' = {
    pc: 0, phase: Ready, tries: 0, cur: NONE, attempts: List(),
    reserved: 0, settled: 0, real: 0, effects: [0, 0, 0], applied: [0, 0, 0],
    lates: 0, retries: 0, reconciles: 0,
  }

  pure def inc(l: List[int], i: int): List[int] = l.replaceAt(i, l[i] + 1)

  // send an attempt of the current intent, under a reservation of R
  action send: bool = all {
    s.pc < N,
    s.phase == Ready or (s.phase == Lost and s.tries < MAXA),
    NO_RESERVE or s.settled + s.reserved + R <= B,
    s' = { ...s,
      phase: Sent, tries: s.tries + 1, cur: s.attempts.length(),
      attempts: s.attempts.append({ intent: s.pc, n: s.tries, ran: false, cost: 0, live: true }),
      reserved: if (NO_RESERVE) s.reserved else s.reserved + R,
      retries: if (s.phase == Lost) s.retries + 1 else s.retries,
    },
  }

  // the world runs a live attempt, at most once, charging 1..R
  action worldRuns: bool = {
    nondet a = s.attempts.indices().oneOf()
    nondet c = 1.to(R).oneOf()
    val at = s.attempts[a]
    all {
      at.live, not(at.ran),
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, ran: true, cost: c }),
        real: s.real + c,
        effects: if (KIND[at.intent] == Eff) inc(s.effects, at.intent) else s.effects,
      },
    }
  }

  // settle an attempt's reservation at its actual cost
  def settle(at: Attempt): (int, int) =
    (if (NO_RESERVE) s.reserved else s.reserved - R, s.settled + at.cost)

  // an answer arrives for a live attempt that ran
  action answer: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    val current = a == s.cur and s.phase == Sent
    val st = settle(at)
    all {
      at.live, at.ran,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, live: false }),
        reserved: st._1, settled: st._2,
        pc: if (current) s.pc + 1 else s.pc,
        phase: if (current) Ready else s.phase,
        tries: if (current) 0 else s.tries,
        cur: if (current) NONE else s.cur,
        applied: if (current or APPLY_LATE) inc(s.applied, at.intent) else s.applied,
        lates: if (current) s.lates else s.lates + 1,
      },
    }
  }

  // the current attempt's answer is lost (a timeout): an observational
  // intent may try again; an effectful one is Uncertain
  action lose: bool = all {
    s.phase == Sent,
    s' = { ...s,
      phase: if (retryable(KIND[s.pc])) (if (s.tries < MAXA) Lost else Failed) else Uncertain,
      cur: NONE,
    },
  }

  // an abandoned attempt will never answer: charge it at the worst case
  action expire: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      at.live, a != s.cur,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, live: false }),
        reserved: if (NO_RESERVE) s.reserved else s.reserved - R,
        settled: if (REFUND_LOST) s.settled else s.settled + R,
      },
    }
  }

  // an effectful intent is reconciled: the outbox asks the world by key,
  // fencing the key, and the reservation settles at what the world reports
  action reconcile: bool = {
    nondet a = s.attempts.indices().oneOf()
    val at = s.attempts[a]
    all {
      s.phase == Uncertain, at.intent == s.pc, at.live,
      s' = { ...s,
        attempts: s.attempts.replaceAt(a, { ...at, live: false }),
        reserved: if (NO_RESERVE) s.reserved else s.reserved - R,
        settled: s.settled + (if (at.ran) at.cost else 0),
        pc: if (at.ran) s.pc + 1 else s.pc,
        phase: if (at.ran) Ready else Failed,
        tries: 0,
        applied: if (at.ran) inc(s.applied, at.intent) else s.applied,
        reconciles: s.reconciles + 1,
      },
    }
  }

  action idle: bool = s' = s

  action step = any { send, worldRuns, answer, lose, expire, reconcile, idle }

  // ---- properties -------------------------------------------------------------------------
  val effectOnce: bool = 0.to(N - 1).forall(i => KIND[i] == Eff implies s.effects[i] <= 1)
  val receiptOnce: bool = s.applied.foldl(true, (ok, n) => ok and n <= 1)
  val withinBudget: bool = s.settled + s.reserved <= B
  val accounted: bool = s.real <= s.settled + s.reserved
  val obsNeverUncertain: bool = not(s.phase == Uncertain and s.pc < N and KIND[s.pc] == Obs)
  val safety: bool = effectOnce and receiptOnce and withinBudget and accounted and obsNeverUncertain

  // witnesses
  val retriedThenDone: bool = s.retries > 0 and s.pc == N
  val lateSettled: bool = s.lates > 0
  val exhausted: bool = s.phase == Failed and s.pc < N and KIND[s.pc] == Obs
  val effectfulUncertain: bool = s.phase == Uncertain
  val reconciled: bool = s.reconciles > 0 and s.pc > 1
}

module design {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false).* from "./observe"
}
module misclassify {
  import observe(MISCLASSIFY = true, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false).* from "./observe"
}
module refundLost {
  import observe(MISCLASSIFY = false, REFUND_LOST = true, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = false).* from "./observe"
}
module noReserve {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = true, APPLY_LATE = false, ONE_CLASS = false).* from "./observe"
}
module applyLate {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = true, ONE_CLASS = false).* from "./observe"
}
module oneClass {
  import observe(MISCLASSIFY = false, REFUND_LOST = false, NO_RESERVE = false, APPLY_LATE = false, ONE_CLASS = true).* from "./observe"
}
