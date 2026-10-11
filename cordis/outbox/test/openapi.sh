#!/usr/bin/env bash
# An imported API end to end (RFC 0003 revision 4): cordis-run runs a graph
# over the Notes API's imported effects, and cordis-outbox runs them with
# its http executor against a stand-in server that needs a bearer token,
# deduplicates creates by Idempotency-Key, and drops the first create's
# answer after applying it. The keyed create is sent again with the same
# key and applied once; the runner is crashed part way and recovered; the
# graph saves the note and reads it back. Against a server that ignores
# the key, the same run applies the create twice: the keyed class rests on
# the server's promise (observe.qnt's keyIgnored).
#
#   openapi.sh CORDIS_RUN CORDIS_OUTBOX CIMPORT NOTES.yaml NOTES_GRAPH.cg STUB_NOTES.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 cimport=$3 doc=$4 graph=$5 stub=$6
T=$(mktemp -d)
cleanup() { kill "${stub_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT

mkdir -p "$T/import"
"$cimport" openapi "$doc" --out "$T/import"
cat "$T/import/types.cg" "$graph" > "$T/notes.cg"

# a stand-in server and an outbox for it, with fresh state: start ARGS...
start() {
  kill "${stub_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  wait "${stub_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  rm -f "$T/port" "$T/run.db" "$T/journal.db" "$T/outbox.out"
  python3 "$stub" "$T/port" "$T/stats" 1 "$@" & stub_pid=$!
  for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
  CORDIS_CREDENTIAL_TOKEN=secret "$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" \
    --api "$doc" --api-base "http://127.0.0.1:$(cat "$T/port")" > "$T/outbox.out" 2> "$T/outbox.err" & outbox_pid=$!
  for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
}
start

run() {
  "$run_bin" --graph "$T/notes.cg" --name notes_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'text="hello"' --operations "$T/import/operations.json" \
    --worst notes.createNote=1 --worst notes.getNote=1 --budget 100 --timeout 5 "$@"
}

# crash right after the first receipt (the resent create's), before it is synced; then finish
crashes=0
set +e; run --crash-after-receipts 1 > "$T/run.out" 2> "$T/crash.err"; code=$?; set -e
[ "$code" = 3 ] || { echo "expected a crash (exit 3), got $code"; cat "$T/crash.err" "$T/run.out"; exit 1; }
out=$(run)
echo "$out"
python3 - "$out" "$T/stats" "$T/journal.db" "$T/import/operations.json" <<'PY'
import json, sqlite3, sys
out, stats, journal, ops = json.loads(sys.argv[1]), json.load(open(sys.argv[2])), sys.argv[3], json.load(open(sys.argv[4]))
assert out["assets"]["saved"] == "hello", out
assert ops["operations"]["notes.createNote"]["class"] == "keyed", ops
assert ops["operations"]["notes.getNote"]["class"] == "observational", ops
# the create was sent at least twice (its first answer was dropped) and applied once
assert stats["creates"] >= 2, stats
assert stats["applied"] == 1, stats
assert stats["unauthorised"] == 0, stats
rows = sqlite3.connect(journal).execute("SELECT jkey, kind, state FROM journal ORDER BY jkey").fetchall()
creates = [r for r in rows if r[1] == "notes.createNote"]
# journaled per send: the same intent key, attempts 1, 2, ...
assert len(creates) >= 2 and len({r[0].rsplit("#", 1)[0] for r in creates}) == 1, rows
assert sorted(int(r[0].rsplit("#", 1)[1]) for r in creates) == list(range(1, len(creates) + 1)), rows
print(f"ok: saved and read back; create sent {stats['creates']} times, applied {stats['applied']}; journal {rows}")
PY

# the planted fault: a server that ignores the key applies the resent create again
start ignore-key
set +e; run --crash-after-receipts 1 > /dev/null 2>&1; set -e
run > /dev/null
python3 - "$T/stats" <<'PY'
import json, sys
stats = json.load(open(sys.argv[1]))
assert stats["applied"] == 2, f"a server ignoring the key should apply the resent create again: {stats}"
print(f"ok: a server ignoring the key applied the create {stats['applied']} times, as observe.qnt's keyIgnored predicts")
PY
