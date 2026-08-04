#!/bin/sh
# verify-noio.sh — process-level check that a scripted perturb run performs no I/O.
#
#   JOLT=/path/to/jolt/bin/jolt JOLT_CHEZ=/usr/local/bin/chez dev/verify-noio.sh
#
# CHEZ=chez fails on newer Makefiles; pass the full path.
#
# WHAT THIS MEASURES. `jolt -M:noio` writes PERTURB-NOIO-BEGIN, runs a complete
# scripted nREPL session (clone, three evals, close, one octet per recv) printing
# nothing, then writes PERTURB-NOIO-END. This script straces the process and
# reports every syscall between those two writes. For the claim to hold, nothing
# in that window may be attributable to perturb: no openat, no mmap, no socket,
# no write. Not "no socket calls" — no syscalls.
#
# ONE RESIDUAL, NAMED RATHER THAN FILTERED QUIETLY. The window is not literally
# empty: it contains a handful of clock_gettime(CLOCK_PROCESS_CPUTIME_ID) calls.
# Those are Chez's collector reading the process CPU clock while perturb
# allocates persistent vectors (CLOCK_PROCESS_CPUTIME_ID is not served by the
# vDSO, so it is a real syscall). They are the runtime's accounting, not
# perturb's I/O, and they are the ONLY thing in the window. The script counts
# them separately and prints them, so the residual is a stated measurement
# rather than a filter that could hide something.
#
# WHY THERE IS A POSITIVE CONTROL. A trace showing nothing proves nothing unless
# the same instrument, pointed at code that does the thing, shows it. Run 2 is
# `-M:noio --touch-native`, which performs exactly one `:connect` through
# perturb.posix/handler inside the window. Run 2's window must contain socket()
# and connect(). If it does not, run 1's clean window is meaningless.
#
# WHAT RUNS 1-4 DO NOT GIVE YOU, AND RUN 2b DOES. Everything above is a
# MEASUREMENT of a window: E16 nonclaim 2 conceded that attribution is by
# instrument, over enumerated bindings, so a sixth path is invisible to it.
# perturb now also FAILS CLOSED — `perturb.posix/gate!` refuses a native
# crossing with no handler in dynamic extent, before the library load, before
# symbol resolution, before the syscall — and LATCHES the refusal in run state,
# so a caller that catches the exception cannot make the run report success.
# Run 2b is that mechanism's positive control: the same socket(2) as run 2,
# written outside any handler, with the exception swallowed. Its window must be
# clean AND its report must say all-handled? false. Runs 1 and 2 must both say
# all-handled? true, over a required-symbol set, so neither can pass vacuously.
#
# WHAT strace CANNOT SEE, and why the in-process counter exists. `(load-library)`
# with no argument is dlopen(NULL): it binds the process's own already-mapped
# symbols and issues zero syscalls (run 4 demonstrates the instrument's blind
# spot by loading a library that IS unmapped, which shows openat+mmap). dlsym
# does not syscall either. So library loading and symbol resolution are covered
# by perturb.posix/native-log and by the absent-symbol canary, both reported by
# -M:noio itself; strace covers everything else.
set -e

JOLT="${JOLT:-../../../jolt/bin/jolt}"
export JOLT_CHEZ="${JOLT_CHEZ:-/usr/local/bin/chez}"

here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
root="$(CDPATH= cd -- "$here/.." && pwd)"

if [ ! -x "$JOLT" ]; then
  echo "set JOLT to the jolt launcher (currently: $JOLT)" >&2
  exit 2
fi
JOLT="$(CDPATH= cd -- "$(dirname -- "$JOLT")" && pwd)/$(basename -- "$JOLT")"

command -v strace >/dev/null 2>&1 || { echo "strace not found" >&2; exit 2; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

# Print every syscall strictly between the BEGIN and END marker writes.
window() {
  awk '
    /write\([0-9]+, "PERTURB-NOIO-BEGIN/ { inside=1; next }
    /write\([0-9]+, "PERTURB-NOIO-END/   { if (inside) exit }
    inside                               { print }
  ' "$1"
}

# Chez's collector reads the process CPU clock; that is runtime accounting, not
# perturb I/O. Everything else in the window is attributed to perturb.
gc_noise='clock_gettime\(CLOCK_PROCESS_CPUTIME_ID'

run() {                       # run <label> <tracefile> [args...]
  label="$1"; trace="$2"; shift 2
  echo "--- $label"
  ( cd "$root" && strace -f -o "$trace" "$JOLT" -M:noio "$@" ) > "$trace.out" 2>"$trace.err" || {
    echo "run failed; jolt output:"; cat "$trace.out" "$trace.err"; exit 1; }
  sed -n '/^mode:/,$p' "$trace.out" | sed 's/^/    /'
  echo
  n=$(window "$trace" | wc -l | tr -d ' ')
  g=$(window "$trace" | grep -c -E "$gc_noise" || true)
  a=$((n - g))
  echo "  syscalls in the window: $n total"
  echo "    $g  clock_gettime(CLOCK_PROCESS_CPUTIME_ID)  — Chez collector accounting"
  echo "    $a  attributable to perturb"
  if [ "$a" -gt 0 ]; then
    echo "  the $a attributable syscalls:"
    window "$trace" | grep -v -E "$gc_noise" | sed 's/^/    /'
  fi
  echo "  (full window, unfiltered:)"
  window "$trace" | sed 's/^/    /'
  echo
}

echo "========================================================================"
echo "RUN 1 — scripted only. Nothing in the window may be perturb's."
echo "========================================================================"
run "jolt -M:noio" "$tmp/clean"
clean_a=$(( $(window "$tmp/clean" | wc -l | tr -d ' ') - $(window "$tmp/clean" | grep -c -E "$gc_noise" || true) ))

echo "========================================================================"
echo "RUN 2 — POSITIVE CONTROL. Same shape, one real connect() in the window."
echo "========================================================================"
run "jolt -M:noio --touch-native" "$tmp/ctl" --touch-native
ctl_net=$(window "$tmp/ctl" | grep -c -E '\b(socket|connect)\(' || true)

echo "========================================================================"
echo "RUN 2b — LATCH POSITIVE CONTROL. The same socket(2) as run 2, written"
echo "  OUTSIDE any handler, with the caller catching the exception."
echo "  Two things must hold: the window contains no socket/connect (fail closed"
echo "  means refused BEFORE the crossing), and the run reports all-handled?"
echo "  false anyway (the latch is not catchable away)."
echo "========================================================================"
run "jolt -M:noio --unhandled-native" "$tmp/latch" --unhandled-native
latch_net=$(window "$tmp/latch" | grep -c -E '\b(socket|connect)\(' || true)
latch_fired=$(grep -c 'LATCH FIRED' "$tmp/latch.out" || true)
latch_false=$(grep -c 'all-handled?   false' "$tmp/latch.out" || true)
clean_handled=$(grep -c 'all-handled?   true' "$tmp/clean.out" || true)
ctl_handled=$(grep -c 'all-handled?   true' "$tmp/ctl.out" || true)
vacuous=$(grep -c 'vacuous-run check  all-handled? false' "$tmp/clean.out" || true)

echo "========================================================================"
echo "RUN 3 — LEAK 2 (INHERITED I12), measured. Same scripted run, but the three"
echo "  values are printed INSIDE the window with unmediated println."
echo "========================================================================"
run "jolt -M:noio --print-inside" "$tmp/loud" --print-inside
loud_w=$(window "$tmp/loud" | grep -c -E '\bwrite\(' || true)

echo "========================================================================"
echo "WHOLE-PROCESS network syscalls (not just the window)"
echo "========================================================================"
for f in clean ctl latch loud; do
  c=$(grep -c -E '\b(socket|connect|bind|listen|accept[0-9]*|sendto|recvfrom|sendmsg|recvmsg)\(' "$tmp/$f" || true)
  echo "  $f: $c"
done
echo

echo "========================================================================"
echo "RUN 4 — instrument sensitivity: what a REAL library load looks like."
echo "  (jolt.ffi/load-library)             -> dlopen(NULL), no syscalls"
echo "  (jolt.ffi/load-library \"libz.so.1\") -> openat + mmap, plainly visible"
echo "========================================================================"
cat > "$tmp/senseprobe.clj" <<'EOF'
(ns senseprobe (:require [jolt.ffi :as ffi]))
(defn -main [& args]
  (println "PERTURB-NOIO-BEGIN") (flush)
  (if (= "named" (first args)) (ffi/load-library "libz.so.1") (ffi/load-library))
  (println "PERTURB-NOIO-END") (flush))
EOF
printf '{:paths ["."] :aliases {:sense {:main-opts ["-m" "senseprobe"]}}}\n' > "$tmp/deps.edn"
for mode in noarg named; do
  ( cd "$tmp" && strace -f -o "$tmp/s.$mode" "$JOLT" -M:sense "$mode" ) >/dev/null 2>&1 || true
  echo "  load-library $mode -> $(window "$tmp/s.$mode" | wc -l | tr -d ' ') syscalls in window"
  window "$tmp/s.$mode" | sed 's/^/    /' | head -8
done
echo

echo "========================================================================"
echo "VERDICT"
echo "========================================================================"
fail=0
if [ "$clean_a" -eq 0 ]; then
  echo "  PASS  scripted run: 0 syscalls attributable to perturb between the markers"
  echo "        (residual is Chez collector clock_gettime only, printed above)"
else
  echo "  FAIL  scripted run: $clean_a attributable syscalls between the markers"; fail=1
fi
if [ "$ctl_net" -gt 0 ]; then
  echo "  PASS  positive control: $ctl_net socket/connect calls in the window"
  echo "        -> the instrument is live, so the clean window is a measurement"
else
  echo "  FAIL  positive control did not fire; the clean window proves nothing"; fail=1
fi
# --- the per-run invariant (E26 finding 3) --------------------------------
# Runs 1 and 2 must report all-handled? true; run 2b must report it false and
# must not have crossed. A run that simply performed no effect cannot pass:
# perturb.noio's required-symbol set is asserted by the same report.
if [ "$clean_handled" -gt 0 ] && [ "$ctl_handled" -gt 0 ]; then
  echo "  PASS  per-run invariant: runs 1 and 2 both report all-handled? true"
  echo "        (run 2 did real I/O — three C entry points, each gated inside a handler)"
else
  echo "  FAIL  per-run invariant: run1=$clean_handled run2=$ctl_handled all-handled? true"; fail=1
fi
if [ "$vacuous" -gt 0 ]; then
  echo "  PASS  anti-vacuity: a run that performs no effect reports all-handled?"
  echo "        false against the same required-symbol set, so the invariant"
  echo "        cannot be satisfied by doing nothing"
else
  echo "  FAIL  anti-vacuity: a run with no effects was not rejected"; fail=1
fi
if [ "$latch_fired" -gt 0 ] && [ "$latch_false" -gt 0 ] && [ "$latch_net" -eq 0 ]; then
  echo "  PASS  latch control: an unhandled native crossing was REFUSED before the"
  echo "        syscall ($latch_net socket/connect in its window), and the run reports"
  echo "        all-handled? false even though the caller caught the exception"
else
  echo "  FAIL  latch control: fired=$latch_fired all-handled-false=$latch_false"
  echo "        socket/connect in window=$latch_net — the boundary is blind"; fail=1
fi
echo "  NOTE  leak 2 exhibit: $loud_w write() syscalls in the window when the same"
echo "        scripted run prints its three values. Console output is real,"
echo "        unmediated I/O and this is its exact size (INHERITED I12)."
exit $fail
