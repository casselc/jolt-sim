// -*- mode: Bluespec; -*-
/**
 * intake: the runner, the outbox's intake and its running calls
 * (docs/cordis/rfc/0006-turn-runtime.md, stage 2's review from the
 * halo-flash run).
 *
 * The runner releases an intent's attempt as a frame; the outbox accepts a
 * frame (journals it and acknowledges) and starts its call; a call ends
 * with an answer (a receipt) or with no answer (the outbox reports it
 * lost). Calls take as long as they take: the runner's clock runs out
 * while a call is still in flight, or before the outbox has read the frame
 * at all.
 *
 * The design: the outbox reads its intake whatever calls are running (it
 * runs them off its loop), and the runner tells an attempt the outbox never
 * accepted (it sends the same attempt again: nothing ran, nothing is
 * charged) from one it accepted (it waits: only the outbox says an
 * accepted attempt was lost, and only then is a new attempt sent and the
 * old one charged its worst case).
 *
 * Properties:
 *  - chargedOnlyIfAccepted: an attempt is charged as lost only if the
 *    outbox accepted it (a call that never started costs nothing);
 *  - callOnce: each attempt is called at most once;
 *  - notBlockedByCall: a frame waiting for intake is never held back by a
 *    running call.
 * Mutants:
 *  - TIMEOUT_AS_LOST (`timeoutAsLost`): the runner's clock running out is
 *    a lost receipt, accepted or not (the runner before this model) ->
 *    chargedOnlyIfAccepted;
 *  - SERIAL (`serial`): the outbox runs a call inline and reads nothing
 *    until it ends (the outbox before this model) -> notBlockedByCall.
 * Witnesses (checked as not(w), expected VIOLATED): resentUnaccepted (the
 * clock ran out before intake, and the same attempt went again),
 * lostThenRetried (the outbox reported a loss and a new attempt was
 * called), concurrentCalls (two calls in flight at once), allAnswered.
 */
module intake {
  const TIMEOUT_AS_LOST: bool
  const SERIAL: bool

  pure val INTENTS = Set(1, 2)
  pure val MAXA = 3

  type Phase = Released | Accepted | Answered | Failed
  type R = { attempt: int, phase: Phase }

  type S = {
    runner: int -> R,
    channel: Set[(int, int)],          // frames in flight to the outbox: (intent, attempt)
    accepted: Set[(int, int)],         // journaled by the outbox
    running: Set[(int, int)],          // calls in flight
    back: Set[(int, int, bool)],       // answers in flight to the runner: (intent, attempt, answered?)
    // ghosts
    charged: Set[(int, int)],
    calls: (int, int) -> int,
    resentSame: bool, retriedAfterLoss: bool, concurrent: bool,
  }

  var s: S

  action init = s' = {
    runner: INTENTS.mapBy(_ => { attempt: 1, phase: Released }),
    channel: INTENTS.map(i => (i, 1)), accepted: Set(), running: Set(), back: Set(),
    charged: Set(), calls: Map(), resentSame: false, retriedAfterLoss: false, concurrent: false,
  }

  // ---- the outbox ---------------------------------------------------------------------------
  // intake: journal and acknowledge; a serial outbox reads nothing while a call runs
  action receive: bool = {
    nondet f = s.channel.oneOf()
    all {
      not(SERIAL) or s.running == Set(),
      s' = { ...s,
        channel: s.channel.exclude(Set(f)),
        accepted: s.accepted.union(Set(f)),
        // a frame already accepted is not called again (the journal answers it)
        running: if (s.accepted.contains(f)) s.running else s.running.union(Set(f)),
        calls: if (s.accepted.contains(f)) s.calls else s.calls.put(f, if (s.calls.keys().contains(f)) s.calls.get(f) + 1 else 1),
        concurrent: s.concurrent or (not(s.accepted.contains(f)) and s.running.size() >= 1),
      },
    }
  }

  // a call ends: answered, or no answer at all (reported lost)
  action finish: bool = {
    nondet f = s.running.oneOf()
    nondet answered = Set(true, false).oneOf()
    s' = { ...s, running: s.running.exclude(Set(f)), back: s.back.union(Set((f._1, f._2, answered))) }
  }

  // ---- the runner ---------------------------------------------------------------------------
  action deliver: bool = {
    nondet b = s.back.oneOf()
    val r = s.runner.get(b._1)
    all {
      s' = { ...s,
        back: s.back.exclude(Set(b)),
        runner:
          if (r.attempt != b._2 or r.phase == Answered or r.phase == Failed) s.runner
          else if (b._3) s.runner.set(b._1, { ...r, phase: Answered })
          else if (r.attempt < MAXA) s.runner.set(b._1, { attempt: r.attempt + 1, phase: Released })
          else s.runner.set(b._1, { ...r, phase: Failed }),
        channel:
          if (r.attempt == b._2 and not(b._3) and r.attempt < MAXA and (r.phase == Released or r.phase == Accepted))
            s.channel.union(Set((b._1, r.attempt + 1)))
          else s.channel,
        charged: if (r.attempt == b._2 and not(b._3) and (r.phase == Released or r.phase == Accepted)) s.charged.union(Set((b._1, b._2))) else s.charged,
        retriedAfterLoss: s.retriedAfterLoss or (r.attempt == b._2 and not(b._3) and r.attempt < MAXA),
      },
    }
  }

  // the runner hears the acknowledgement: the attempt is accepted
  action hearAck: bool = {
    nondet i = INTENTS.oneOf()
    val r = s.runner.get(i)
    all { r.phase == Released, s.accepted.contains((i, r.attempt)), s' = { ...s, runner: s.runner.set(i, { ...r, phase: Accepted }) } }
  }

  // the runner's clock runs out on an attempt with no answer
  action timeout: bool = {
    nondet i = INTENTS.oneOf()
    val r = s.runner.get(i)
    all {
      r.phase == Released or r.phase == Accepted,
      if (TIMEOUT_AS_LOST) all {
        // taken as lost: the attempt charged its worst case, the next one sent
        r.attempt < MAXA,
        s' = { ...s,
          runner: s.runner.set(i, { attempt: r.attempt + 1, phase: Released }),
          channel: s.channel.union(Set((i, r.attempt + 1))),
          charged: s.charged.union(Set((i, r.attempt))),
        },
      } else all {
        // the design: an accepted attempt is waited on; an unaccepted one is sent again, as it is
        r.phase == Released,
        s' = { ...s, channel: s.channel.union(Set((i, r.attempt))), resentSame: true },
      },
    }
  }

  action step = any { receive, finish, deliver, hearAck, timeout }

  // ---- properties -------------------------------------------------------------------------
  val chargedOnlyIfAccepted: bool = s.charged.forall(f => s.accepted.contains(f))
  val callOnce: bool = s.calls.keys().forall(f => s.calls.get(f) <= 1)
  // whenever a frame waits, intake can take it, whatever is running
  val notBlockedByCall: bool = s.channel != Set() implies (not(SERIAL) or s.running == Set())
  val safety: bool = chargedOnlyIfAccepted and callOnce and notBlockedByCall

  // witnesses
  val resentUnaccepted: bool = s.resentSame
  val lostThenRetried: bool = s.retriedAfterLoss
  val concurrentCalls: bool = s.concurrent
  val allAnswered: bool = INTENTS.forall(i => s.runner.get(i).phase == Answered)
}

module design {
  import intake(TIMEOUT_AS_LOST = false, SERIAL = false).* from "./intake"
}
module timeoutAsLost {
  import intake(TIMEOUT_AS_LOST = true, SERIAL = false).* from "./intake"
}
module serial {
  import intake(TIMEOUT_AS_LOST = false, SERIAL = true).* from "./intake"
}
