#!/usr/bin/env bash
# The central limiter shared by two agents (quint/outbox.qnt's withinLimit;
# RFC 0006 decision 4F). Each agent runs the review loop (six emissions:
# three reads, three completions) through its own outbox; the limit is
# 10, one token of which a probe holds, having asked for one emission
# three times.
# Agent a runs first, crashing twice right after a receipt so its intents
# are released again; then agent b. Then:
#   - a ships, having used exactly six grants (a released-again intent gets
#     its grant back, never a second token);
#   - b is granted the three left, its next intent waits in its journal, and
#     the runner stops as blocked by the limiter;
#   - the limiter granted nine, and exactly nine emissions ran.
#
#   limiter.sh CORDIS_RUN CORDIS_OUTBOX CORDIS_LIMITER REVIEW.cg STUB_LLM.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 limiter_bin=$3 graph=$4 stub=$5
T=$(mktemp -d)
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT

python3 "$stub" "$T/port" "$T/count" & pids+=($!)
for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
mkdir -p "$T/repo/src" && echo "let x = 1" > "$T/repo/src/main.ml"

"$limiter_bin" --socket "$T/limiter.sock" --ledger "$T/ledger.db" --limit 10 > "$T/limiter.out" & pids+=($!)
for _ in $(seq 100); do grep -q ready "$T/limiter.out" 2>/dev/null && break; sleep 0.1; done

# one emission asked for three times (its answer lost, its outbox restarted between grant and run):
# one token, granted each time
python3 - "$T/limiter.sock" <<'PY'
import json, socket, sys
for _ in range(3):
    s = socket.socket(socket.AF_UNIX); s.connect(sys.argv[1])
    s.sendall(b'{"op":"grant","emission":"p/probe"}\n')
    assert json.loads(s.makefile().readline())["op"] == "granted"
    s.close()
PY

for a in a b; do
  "$outbox_bin" --socket "$T/$a.sock" --journal "$T/$a.journal" --llm "http://127.0.0.1:$(cat "$T/port")" --repo "$T/repo" \
    --limiter "$T/limiter.sock" --agent "$a" > "$T/$a.outbox" & pids+=($!)
  for _ in $(seq 100); do grep -q ready "$T/$a.outbox" 2>/dev/null && break; sleep 0.1; done
done

run() {
  local a=$1; shift
  "$run_bin" --graph "$graph" --name review_loop --store "$T/$a.run" --socket "$T/$a.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 1 --max-wait 4 "$@"
}

for m in 2 3; do
  set +e; run a --crash-after-receipts "$m" > /dev/null 2>&1; code=$?; set -e
  [ "$code" = 3 ] || { echo "agent a: expected a crash, got $code"; exit 1; }
done
out_a=$(run a)
set +e; run b > "$T/b.out" 2> "$T/b.err"; code_b=$?; set -e
[ "$code_b" = 2 ] || { echo "agent b: expected to be blocked (exit 2), got $code_b"; cat "$T/b.err"; exit 1; }
grep -q "limiter" "$T/b.err" || { echo "agent b stopped, but not for the limiter:"; cat "$T/b.err"; exit 1; }

python3 - "$out_a" "$T" <<'PY'
import json, sqlite3, sys
out_a, T = json.loads(sys.argv[1]), sys.argv[2]
assert out_a["assets"]["shipped"] is True, out_a
ledger = sqlite3.connect(f"{T}/ledger.db")
grants = dict(ledger.execute("SELECT substr(emission, 1, 1), COUNT(*) FROM grants GROUP BY 1").fetchall())
assert grants == {"a": 6, "b": 3, "p": 1}, f"grants {grants}: an emission asked for again must not take a new token"
ran = {a: sqlite3.connect(f"{T}/{a}.journal").execute("SELECT COUNT(*) FROM journal WHERE state = 'done'").fetchone()[0] for a in "ab"}
waiting = sqlite3.connect(f"{T}/b.journal").execute("SELECT COUNT(*) FROM journal WHERE state = 'waiting'").fetchone()[0]
assert ran == {"a": 6, "b": 3}, f"emissions run {ran}"
assert waiting >= 1, "b's next intent should wait for a token"
assert sum(ran.values()) == sum(grants.values()) - 1 == 9
print(f"ok: limit 10 held; grants {grants}; run {ran}; b waiting {waiting}")
PY
