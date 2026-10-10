#!/bin/sh
# The run store's native rollback-fidelity check (docs/cordis/rfc/0006-turn-
# runtime.md, revision 2, stage 3). quint/handoff.qnt takes "the run store
# rolls back exactly with the step snapshot" as given; this is the evidence
# for that on the host's own filesystem. It compares the store's LOGICAL
# content (its durable records, as cordis-run --dump-records prints them),
# not written@snap: yard's SR1 found mount-time intent-log housekeeping
# dirties a dataset by 8 KiB with no logical change, so a physical measure
# gives false positives. written@ is printed for the record only.
#
# Run as root on the FreeBSD host, from cordis/ after `dune build`:
#   sh ops/rollback-fidelity.sh POOL
#
# What it does (D is a fresh dataset POOL/cordis-fidelity-PID; the outbox's
# journal is outside D, as the outbox jail's is):
#   1. starts a stand-in completions server and the outbox;
#   2. runs the review loop with its store on D until a crash after 6
#      actions; dumps the records (base);
#   3. snapshots D@base; resumes until a crash after 6 more actions; dumps
#      (after: must differ from base, the positive control);
#   4. rolls D back to @base; dumps (rolled: must equal base);
#   5. unmounts and mounts D; dumps (remounted: must equal base);
#   6. resumes from the rolled-back store to rest: the loop ships, and the
#      completions server has answered exactly three requests: the intents
#      the rollback made the engine release again were answered from the
#      outbox's journal, outside the snapshot (handoff.qnt's
#      rolledBackReissued, natively);
#   7. destroys D.
#
# Predictions (stated before any run):
#   - base, rolled and remounted dumps identical (sha256 equal): 99%;
#   - after differs from base: 99%;
#   - written@base after the remount: 0 or 8192 bytes, either is fine;
#   - three completions in total: 95% (if more: a re-released intent ran
#     again, which handoff.qnt says must not happen);
#   - wall time under a minute.
set -eu
POOL=${1:?usage: rollback-fidelity.sh POOL}
here=$(cd "$(dirname "$0")/.." && pwd)
RUN="$here/_build/default/engine/bin/cordis_run.exe"
OUTBOX="$here/_build/default/outbox/bin/cordis_outbox.exe"
GRAPH="$here/graph/examples/review.cg"
STUB="$here/outbox/test/stub_llm.py"
D="$POOL/cordis-fidelity-$$"
W=$(mktemp -d)
cleanup() {
  kill "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  zfs destroy -r "$D" 2>/dev/null || true
  rm -rf "$W"
}
trap cleanup EXIT

zfs create "$D"
M=$(zfs get -H -o value mountpoint "$D")
python3 "$STUB" "$W/port" "$W/count" & llm_pid=$!
while [ ! -s "$W/port" ]; do sleep 0.1; done
mkdir -p "$W/repo/src" && echo "let x = 1" > "$W/repo/src/main.ml"
"$OUTBOX" --socket "$W/outbox.sock" --journal "$W/journal.db" --llm "http://127.0.0.1:$(cat "$W/port")" --repo "$W/repo" > "$W/outbox.out" & outbox_pid=$!
until grep -q ready "$W/outbox.out" 2>/dev/null; do sleep 0.1; done

run() {
  "$RUN" --graph "$GRAPH" --name review_loop --store "$M/run.db" --socket "$W/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 10 "$@"
}
sha() { if command -v sha256 > /dev/null; then sha256 -q "$1"; else sha256sum "$1" | cut -d' ' -f1; fi; }
dump() { "$RUN" --graph "$GRAPH" --name review_loop --store "$M/run.db" --socket /dev/null --dump-records > "$W/$1.txt"; sha "$W/$1.txt"; }

run --crash-after 6 > /dev/null 2>&1 || true
base=$(dump base)
zfs snapshot "$D@base"
run --crash-after 6 > /dev/null 2>&1 || true
after=$(dump after)
zfs rollback "$D@base"
rolled=$(dump rolled)
zfs unmount "$D" && zfs mount "$D"
remounted=$(dump remounted)
written=$(zfs get -H -p -o value written@base "$D")
out=$(run --audit)

echo "records: base $(wc -l < "$W/base.txt"), after $(wc -l < "$W/after.txt"), rolled $(wc -l < "$W/rolled.txt"), remounted $(wc -l < "$W/remounted.txt")"
echo "written@base after the remount: $written bytes (physical; for the record only)"
fail=0
[ "$after" != "$base" ] || { echo "FAIL: the positive control: the resumed run changed nothing"; fail=1; }
[ "$rolled" = "$base" ] || { echo "FAIL: the rollback did not restore the records"; diff "$W/base.txt" "$W/rolled.txt" | head; fail=1; }
[ "$remounted" = "$base" ] || { echo "FAIL: the remount changed the records"; diff "$W/base.txt" "$W/remounted.txt" | head; fail=1; }
echo "$out" | grep -q '"shipped":true' || { echo "FAIL: the resumed loop did not ship: $out"; fail=1; }
[ "$(cat "$W/count")" = 3 ] || { echo "FAIL: $(cat "$W/count") completions; a re-released intent ran again"; fail=1; }
[ "$fail" = 0 ] && echo "PASS: rollback restores the run store's records exactly, across a remount; the outbox answered what the rollback re-released"
exit "$fail"
