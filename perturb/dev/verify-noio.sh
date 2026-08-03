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
echo "RUN 3 — LEAK 2 (INHERITED I12), measured. Same scripted run, but the three"
echo "  values are printed INSIDE the window with unmediated println."
echo "========================================================================"
run "jolt -M:noio --print-inside" "$tmp/loud" --print-inside
loud_w=$(window "$tmp/loud" | grep -c -E '\bwrite\(' || true)

echo "========================================================================"
echo "WHOLE-PROCESS network syscalls (not just the window)"
echo "========================================================================"
for f in clean ctl loud; do
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
echo "  NOTE  leak 2 exhibit: $loud_w write() syscalls in the window when the same"
echo "        scripted run prints its three values. Console output is real,"
echo "        unmediated I/O and this is its exact size (INHERITED I12)."
exit $fail
