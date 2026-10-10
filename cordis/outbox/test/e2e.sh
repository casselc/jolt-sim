#!/usr/bin/env bash
# The review loop end to end as processes (RFC 0006 revision 2, stage 2):
# cordis-run (the engine, its run store on SQLite) and cordis-outbox (its
# journal on SQLite) over a Unix socket, against a stand-in completions
# server. The runner is killed every few actions and started again on the same
# store, while one outbox serves them all (restarted once, with its journal); the loop must still ship, every completion must have been asked
# for once (three critic computations, three completions), and the read
# must have run once per key.
#
#   e2e.sh CORDIS_RUN CORDIS_OUTBOX REVIEW.cg STUB_LLM.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
cleanup() { kill "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT

python3 "$stub" "$T/port" "$T/count" & llm_pid=$!
for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
mkdir -p "$T/repo/src" && echo "let x = 1 (* the file *)" > "$T/repo/src/main.ml"

start_outbox() {
  : > "$T/outbox.out"
  "$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --llm "http://127.0.0.1:$(cat "$T/port")" --repo "$T/repo" > "$T/outbox.out" & outbox_pid=$!
  for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
}
start_outbox

run() {
  "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 10 "$@"
}

# crash until a run finishes: mostly right after the 1st or 2nd receipt of a run, before it is synced
# (which lands after a completion's receipt), and now and then after a few actions
crashes=0
for c in r1 r2 r1 r2 a3 r1 r2 r1 r2 a4 r1 r2 r1 r2 a2 r1 r2 r1 r2 a3 r1 r2 r1 r2 r1 r2 r1 r2; do
  case $c in r*) how=--crash-after-receipts ;; a*) how=--crash-after ;; esac; m=${c#?}
  set +e; run "$how" "$m" > "$T/run.out" 2> "$T/crash.err"; code=$?; set -e
  [ "$code" = 0 ] && { out=$(cat "$T/run.out"); break; }
  [ "$code" = 3 ] || { echo "expected a crash (exit 3), got $code"; cat "$T/crash.err"; exit 1; }
  crashes=$((crashes + 1))
  # the outbox outlives the engines that die talking to it; once, part way, it restarts too, its
  # journal kept: what it answered it answers again from the journal
  if [ "$crashes" = 5 ]; then kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true; start_outbox; fi
  kill -0 "$outbox_pid" 2>/dev/null || { echo "the outbox died when an engine crashed"; cat "$T/outbox.out"; exit 1; }
done
[ -n "${out:-}" ] || out=$(run)

# the audit: the records read again against their own graph are clean; against a graph whose
# critic asks for something else, the runner refuses to resume them
"$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" --audit \
  --input 'request={"path":"src/main.ml","patch":"let x = 1"}' --observational llm.complete --worst llm.complete=4096 \
  --worst repo.read=1 --budget 100000 --timeout 10 > /dev/null
sed 's/max_tokens: 4000/max_tokens: 4001/' "$graph" > "$T/changed.cg"
set +e
"$run_bin" --graph "$T/changed.cg" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" --audit \
  --input 'request={"path":"src/main.ml","patch":"let x = 1"}' --observational llm.complete --worst llm.complete=4096 \
  --worst repo.read=1 --budget 100000 --timeout 10 > /dev/null 2> "$T/audit.err"; code=$?
set -e
[ "$code" = 2 ] && grep -q "not resuming" "$T/audit.err" || { echo "the audit should refuse a changed graph (exit $code)"; cat "$T/audit.err"; exit 1; }

# an engine that dies mid-exchange: send an intent and hang up before the answer; the outbox must live on
python3 - "$T/outbox.sock" <<'PY'
import json, socket, sys
s = socket.socket(socket.AF_UNIX); s.connect(sys.argv[1])
intent = {"key": "probe/hangup#1.0", "kind": "repo.read", "request": ["s", "src/main.ml"], "cls": "effectful", "attempt": 1}
s.sendall((json.dumps({"op": "intent", "intent": intent}) + "\n").encode())
s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b"\x01\x00\x00\x00\x00\x00\x00\x00")
s.close()
PY
sleep 1
kill -0 "$outbox_pid" 2>/dev/null || { echo "the outbox died when an engine hung up mid-answer"; exit 1; }
echo "$out"
python3 - "$out" "$(cat "$T/count")" "$T/journal.db" "$crashes" <<'PY'
import json, sqlite3, sys
out, count, journal, crashes = json.loads(sys.argv[1]), int(sys.argv[2]), sys.argv[3], int(sys.argv[4])
assert crashes >= 4, f"only {crashes} crashes"
assets = out["assets"]
assert assets["shipped"] is True, f"not shipped: {assets}"
assert assets["verdict"]["tag"] == "Pass", assets["verdict"]
assert assets["draft"]["patch"].count("\n// ") == 2, assets["draft"]
assert count == 3, f"{count} completions asked for, expected 3 (one per critic computation)"
assert out["reserved"] == 0, out
rows = sqlite3.connect(journal).execute("SELECT kind, state, COUNT(*) FROM journal GROUP BY kind, state").fetchall()
# the probe's read is the fourth
assert sorted(rows) == [("llm.complete", "done", 3), ("repo.read", "done", 4)], rows
print(f"ok: shipped after {crashes} crashes; {count} completions, journal {rows}, settled {out['settled']}")
PY
