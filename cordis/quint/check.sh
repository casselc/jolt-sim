#!/usr/bin/env bash
# The model gate for cordis phase 1 (docs/cordis/DESIGN.md, build order item
# 2): simulate quint/lifecycle.qnt's design, its witnesses and its mutants,
# replay simulator runs in the model with quintgo (conformance), and hand the
# report to model-verdict.sh, whose fail and warn rules decide the exit.
#
#   quint/check.sh                       # from cordis/
#   OUT=report.txt SAMPLES=2000 quint/check.sh
#
# Needs quint (npm i -g @informalsystems/quint), dune with the cordis switch,
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

sim() { # main invariant expect
  local main=$1 inv=$2 expect=$3 r
  out=$(cd "$here" && quint run --backend "$BACKEND" --main "$main" --invariant "$inv" \
          --max-samples "$SAMPLES" --max-steps "$STEPS" lifecycle.qnt 2>&1)
  if echo "$out" | grep -q '\[ok\] No violation'; then r=ok
  elif echo "$out" | grep -q '\[violation\]'; then r=VIOLATED
  else r=ERROR; echo "$out" | tail -5 >&2
  fi
  echo "sim lifecycle.qnt $main $inv $SAMPLES x $STEPS $r (expect $expect)" | tee -a "$OUT"
}

# the design: every safety property holds
for inv in guardHolds atMostOnce boundOnlyWhenRun; do sim design "$inv" ok; done
# witnesses: each state must be reachable
for w in someUncertain guardWaiting someFailed failingUnload restartedEmits; do
  sim design "not($w)" VIOLATED
done
# mutants: each must break the property it exists to protect
sim noGuard guardHolds VIOLATED
sim blindRetry atMostOnce VIOLATED

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
