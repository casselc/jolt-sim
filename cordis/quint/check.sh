#!/usr/bin/env bash
# The model gate for cordis (docs/cordis/DESIGN.md, build order items 2 and
# 3a-3d): simulate each model's design, witnesses and mutants; check each design
# with Apalache to VERIFY_STEPS (default 8); replay simulator runs in the
# model with quintgo (conformance); and hand the report to model-verdict.sh,
# whose fail and warn rules decide the exit.
#
#   quint/check.sh                       # from cordis/
#   OUT=report.txt SAMPLES=2000 quint/check.sh
#
# Needs quint (npm i -g @informalsystems/quint), Java 17+ for the Apalache that
# `quint verify` fetches, dune with the cordis switch,
# and quintgo (casselc/observability/quintgo; set QUINTGO to its binary).
# Without QUINTGO the conformance rows report ERROR and the gate fails: a
# skipped check is not a pass.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
OUT=${OUT:-${TMPDIR:-/tmp}/cordis-model.txt}
SAMPLES=${SAMPLES:-1000}
STEPS=${STEPS:-40}
BACKEND=${QUINT_BACKEND:-typescript}
TRACES=${TRACES:-8}
QUINTGO=${QUINTGO:-quintgo}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
: > "$OUT"

VERIFY_STEPS=${VERIFY_STEPS:-8}

result() { # quint output -> ok | VIOLATED | ERROR (a deadlock is a broken model, not a finding)
  if echo "$1" | grep -q 'reached a deadlock'; then echo "$1" | tail -3 >&2; echo ERROR
  elif echo "$1" | grep -q '\[ok\] No violation'; then echo ok
  elif echo "$1" | grep -q '\[violation\]'; then echo VIOLATED
  else echo "$1" | tail -5 >&2; echo ERROR
  fi
}

sim() { # file main invariant expect
  local file=$1 main=$2 inv=$3 expect=$4 out
  out=$(cd "$here" && quint run --backend "$BACKEND" --main "$main" --invariant "$inv" \
          --max-samples "$SAMPLES" --max-steps "$STEPS" "$file" 2>&1)
  echo "sim $file $main $inv $SAMPLES x $STEPS $(result "$out") (expect $expect)" | tee -a "$OUT"
}

verify() { # file main invariant expect: Apalache, bounded (an :exhausted grade to that depth)
  local file=$1 main=$2 inv=$3 expect=$4 out
  out=$(cd "$here" && quint verify --main "$main" --invariant "$inv" --max-steps "$VERIFY_STEPS" "$file" 2>&1)
  echo "verify $file $main $inv $VERIFY_STEPS $(result "$out") (expect $expect)" | tee -a "$OUT"
}

# ---- lifecycle.qnt -------------------------------------------------------------------
# the design: every safety property holds
for inv in guardHolds atMostOnce boundOnlyWhenRun; do sim lifecycle.qnt design "$inv" ok; done
verify lifecycle.qnt design safety ok
# witnesses: each state must be reachable
for w in someUncertain guardWaiting someFailed failingUnload; do
  sim lifecycle.qnt design "not($w)" VIOLATED
done
# a restart that emits again needs a long life: nothing retired
sim lifecycle.qnt longLived "not(restartedEmits)" VIOLATED
# mutants: each must break the property it exists to protect
sim lifecycle.qnt noGuard guardHolds VIOLATED
sim lifecycle.qnt blindRetry atMostOnce VIOLATED

# ---- outbox.qnt ----------------------------------------------------------------------
for inv in atMostOnce withinLimit onlyOutboxEgress receiptsTrue; do sim outbox.qnt design "$inv" ok; done
verify outbox.qnt design safety ok
for w in someUnknown limitFull reconciledApplied reconciledNotApplied rolledBackAfterSend; do
  sim outbox.qnt design "not($w)" VIOLATED
done
sim outbox.qnt journalInSnapshot atMostOnce VIOLATED
sim outbox.qnt localLimits withinLimit VIOLATED
sim outbox.qnt pfOpen onlyOutboxEgress VIOLATED
sim outbox.qnt resendOnRestart atMostOnce VIOLATED

# ---- branches.qnt --------------------------------------------------------------------
for inv in onlyChosen atMostOnce realOnlyToChosen answered; do sim branches.qnt design "$inv" ok; done
verify branches.qnt design safety ok
for w in sharedKeyHeldBack divergentSimulated chosenGotReal; do sim branches.qnt design "not($w)" VIOLATED; done
sim branches.qnt releaseEarly onlyChosen VIOLATED
# defense in depth: with the hold-back broken, oracle-drawn keys still stop duplicates
sim branches.qnt releaseEarly atMostOnce ok
sim branches.qnt releaseEarlyNoDedup atMostOnce VIOLATED
sim branches.qnt noForkIds realOnlyToChosen VIOLATED

# ---- gateway.qnt ---------------------------------------------------------------------
for inv in atMostOnce remoteViaOutbox; do sim gateway.qnt design "$inv" ok; done
verify gateway.qnt design safety ok
for w in misannotatedReplayed emissionDeduped; do sim gateway.qnt design "not($w)" VIOLATED; done
sim gateway.qnt ungated atMostOnce VIOLATED
sim gateway.qnt missingIsInternal atMostOnce VIOLATED
# honest annotations and "missing means emission" suffice without gating
sim gateway.qnt honestUngated safety ok

# ---- caps.qnt ------------------------------------------------------------------------
for inv in noWidening attenuates; do sim caps.qnt design "$inv" ok; done
verify caps.qnt design safety ok
for w in narrowedTwice emptyRights; do sim caps.qnt design "not($w)" VIOLATED; done
sim caps.qnt uncoveredCaveats noWidening VIOLATED
sim caps.qnt unionCaveats noWidening VIOLATED

# conformance: simulator runs replayed in the model
conform() { # label scenario mutant expect(pass|fail) extra-args...
  local label=$1 scenario=$2 mutant=$3 expect=$4; shift 4
  local dir="$work/$label" pass=0 fail=0
  mkdir -p "$dir"
  (cd "$root" && dune exec --no-print-directory -- cordis-sim --scenario "$scenario" \
     --mutant "$mutant" --runs "$TRACES" --itf "$dir" --itf-runs "$TRACES" \
     --spec "$here/lifecycle.qnt" "$@" >/dev/null)
  for f in "$dir"/*.itf.json; do
    [ -e "$f" ] || continue
    if [ "$expect" = fail ]; then
      # only a run that reconciled differs from the design
      grep -q '"mbt::actionTaken": "reconcile"' "$f" || continue
      sed -i 's/"RECONCILE": "false"/"RECONCILE": "true"/' "$f"
    fi
    if "$QUINTGO" validate -backend "$BACKEND" -dir "$work/q" "$f" 2>&1 | grep -q 'RESULT: trace conforms'; then
      [ "$expect" = pass ] && pass=$((pass + 1)) || fail=$((fail + 1))
    else
      [ "$expect" = fail ] && pass=$((pass + 1)) || fail=$((fail + 1))
    fi
  done
  echo "runs conformance-$label $scenario passed $pass failed $fail (expect all-pass)" | tee -a "$OUT"
}
if command -v "$QUINTGO" >/dev/null 2>&1; then
  conform chain chain design pass --seed 100
  conform random random design pass --seed 500 --fibers 4
  # a blind-retry run that reports itself as the design must be rejected
  TRACES=40 conform mutant-as-design chain blind-retry fail --seed 7
else
  echo "runs conformance quintgo not found (QUINTGO=$QUINTGO) ERROR" | tee -a "$OUT"
fi

exec "$here/model-verdict.sh" "$OUT"
