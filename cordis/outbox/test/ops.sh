#!/usr/bin/env bash
# The review loop through the outbox's halo operation adapter (docs/cordis/rfc/0007-boundaries.md), against
# stub_ops.py, which serves the wire halo-flash serves. Each phase is a fresh server, journal and store:
#   A. clean: shipped, one generation per critic computation, every result acknowledged and released;
#   B. a submit accepted whose answer is lost: sent again as first sent, one generation (no duplicate);
#   C. a submit lost outright: sent again, accepted;
#   D. an acknowledgement applied whose answer is lost: acknowledged again, nothing left held;
#   E. a submit delivered twice: one generation;
#   F. halo restarts while a result is pending: the old incarnation's work is beyond reach (charged the worst case),
#      a new attempt runs in the new incarnation, and the loop still ships;
#   H. the capture window passes before the result is taken (a slow poll): halo answers expired, which carries the
#      usage; the attempt is charged that, not the worst case, and the engine tries again (the loop does not ship here:
#      every attempt expires);
#   I. a second run in a fresh journal against the same server: the operation ids are salted with the journal's store
#      identity, so none rebinds an attempt halo still holds under another deadline (409 conflict);
#   J. an attempt bound to another request (409 conflict, forced by changing the stored submit under halo's record):
#      not a refusal, which costs nothing; charged as unknown (the worst case), and loud;
#   M. a refused copy (422) closes the copy, not the attempt: the adapter fences it with a cancel and releases the
#      reservation only on cancelled_before_accept (RFC 0008); nothing was generated, a tombstone stands;
#   G. an outbox that stopped after journaling a result and before acknowledging it owes the acknowledgement: the next
#      one makes it (the journal's obligations, committed with the answers, are paid again at start);
#   K. the outbox is killed exactly between journaling a result and acknowledging it (CORDIS_OUTBOX_CRASH=after-journal):
#      halo still holds the body, the journal holds the answer and the obligation, and the next outbox pays it;
#   L. the same for an expired outcome (after-expired-journal): its usage is durable in the journal before halo is
#      told, and the next outbox acknowledges it.
#
#   ops.sh CORDIS_RUN CORDIS_OUTBOX REVIEW.cg STUB_OPS.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
cleanup() { [ -n "${DEBUG:-}" ] && { cat "$T/run.err" "$T/outbox.err" 2>/dev/null | tail -20; }; kill "${srv_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
mkdir -p "$T/repo/src" && echo "let x = 1 (* the file *)" > "$T/repo/src/main.ml"

start() {
  kill "${srv_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  wait "${srv_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  rm -f "$T/port" "$T/count" "$T/run.db" "$T/journal.db" "$T/outbox.out"
  python3 "$stub" "$T/port" "$T/count" "$@" & srv_pid=$!
  for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
  start_outbox
}
start_outbox() {
  : > "$T/outbox.out"
  "$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --ops "http://127.0.0.1:$(cat "$T/port")" --principal ops-test --repo "$T/repo" ${OUTBOX_ARGS:-} \
    > "$T/outbox.out" 2> "$T/outbox.err" & outbox_pid=$!
  for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
}
admin() { python3 - "$(cat "$T/port")" "$@" <<'PY'
import json, sys, urllib.request
port, method, path = sys.argv[1:4]
data = sys.argv[4].encode() if len(sys.argv) > 4 else (b"{}" if method == "POST" else None)
req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method, headers={"Content-Type": "application/json"})
print(urllib.request.urlopen(req, timeout=10).read().decode())
PY
}
run() {
  set +e
  "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout "${TIMEOUT:-20}" "$@" > "$T/run.out" 2> "$T/run.err"
  code=$?
  set -e
}
# wait until the stub holds no unacknowledged outcome (acknowledgements go off the loop, after the commit)
settle() { for _ in $(seq 100); do [ "$(admin GET /admin/state | python3 -c 'import json,sys; print(json.load(sys.stdin)["unacked_outcomes"])')" = 0 ] && return; sleep 0.1; done; }
check() { python3 - "$T/run.out" "$(admin GET /admin/state)" "$T/journal.db" "$code" "$@" <<'PY'
import json, sqlite3, sys
out, state, journal, code, name = json.load(open(sys.argv[1])) if sys.argv[4] == "0" else None, json.loads(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5]
want_gen, want_settled_min = int(sys.argv[6]), int(sys.argv[7])
assert code == 0, f"{name}: exit {code}"
assert out["assets"]["shipped"] is True, f"{name}: not shipped: {out['assets']}"
assert state["generated"] >= want_gen, f"{name}: {state['generated']} generations, expected {want_gen}"
if sys.argv[8] == "exact":
    assert state["generated"] == want_gen, f"{name}: {state['generated']} generations, expected exactly {want_gen}"
assert out["settled"] >= want_settled_min, f"{name}: settled {out['settled']}"
assert state["unacked_outcomes"] == 0, f"{name}: unacknowledged outcomes left: {state}"
assert out["reserved"] == 0, out
rows = sqlite3.connect(journal).execute("SELECT state, COUNT(*) FROM journal WHERE kind = 'llm.complete' GROUP BY state").fetchall()
print(f"{name} ok: shipped; {state['generated']} generations, settled {out['settled']}, attempts {sorted(state['attempts'].values())}, journal {sorted(rows)}")
PY
}

start
run; settle; check A 3 33 exact

start; admin POST /admin/fault '{"lose_submit_answer": true}' > /dev/null
run; settle; check B 3 33 exact

start; admin POST /admin/fault '{"lose_submit": true}' > /dev/null
run; settle; check C 3 33 exact

start; admin POST /admin/fault '{"lose_ack_answer": true}' > /dev/null
run; settle; check D 3 33 exact

start; admin POST /admin/fault '{"dup_submit": true}' > /dev/null
run; settle; check E 3 33 exact

# F: halo restarts while the first completion is pending
start delay=1
( sleep 0.5; admin POST /admin/restart > /dev/null ) & bg=$!
run; wait "$bg"; settle; check F 3 33 atleast

# G: after a clean run, make the journal say the acknowledgements were never made, and restart the outbox
start
run; settle; check G 3 33 exact
kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true
python3 - "$T/journal.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1]); db.execute("UPDATE obligations SET done = 0"); db.execute("UPDATE ops SET acked = 0"); db.commit()
assert db.execute("SELECT COUNT(*) FROM obligations WHERE done = 0").fetchone()[0] == 3
PY
start_outbox
owed() { python3 -c "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute('SELECT COUNT(*) FROM obligations WHERE done = 0').fetchone()[0])" "$T/journal.db"; }
for _ in $(seq 50); do [ "$(owed)" = 0 ] && break; sleep 0.1; done
[ "$(owed)" = 0 ] || { echo "G: obligations still owed"; exit 1; }
echo "G ok: the owed acknowledgements were made again at start"

# H: expiry
OUTBOX_ARGS="--ops-poll 1.6" start delay=0.2 capture=1
TIMEOUT=10 run
python3 - "$T/run.out" "$code" "$T/journal.db" <<'PY'
import json, sqlite3, sys
out, code, journal = json.load(open(sys.argv[1])), int(sys.argv[2]), sys.argv[3]
rows = sqlite3.connect(journal).execute("SELECT state, source, cost FROM journal WHERE kind = 'llm.complete'").fetchall()
assert rows and all(r[0] == "unknown" and r[1] == "expired" and 0 < r[2] < 4096 for r in rows), rows
assert out["settled"] < 4096, f"charged the worst case: {out['settled']}"
assert out["settled"] >= sum(r[2] for r in rows) - 0, (out["settled"], rows)
print(f"H ok: {len(rows)} attempts expired, each charged its actual usage {[r[2] for r in rows]}; settled {out['settled']}, exit {code}")
PY

# I: the same computations in a fresh journal, the server unchanged
start
run; settle; check I1 3 33 exact
kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true
rm -f "$T/journal.db" "$T/journal.db-wal" "$T/journal.db-shm" "$T/run.db" "$T/run.db-wal" "$T/run.db-shm"
start_outbox
run; settle
python3 - "$T/run.out" "$code" "$(admin GET /admin/state)" <<'PY'
import json, sys
out, code, state = json.load(open(sys.argv[1])), int(sys.argv[2]), json.loads(sys.argv[3])
assert code == 0 and out["assets"]["shipped"] is True, (code, out)
ops = {k.split("/")[1] for k in state["attempts"]}
assert len(ops) == 6, f"expected six distinct operations across two journals, got {len(ops)}"
print(f"I ok: a fresh journal's run shipped beside the first's records: {len(ops)} operations, none rebound")
PY

# J: a conflict is charged, not refused
start
run; settle
kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true
python3 - "$T/journal.db" <<'PY'
import hashlib, json, sqlite3, sys
db = sqlite3.connect(sys.argv[1])
for op, body in db.execute("SELECT op, body FROM ops").fetchall():
    b = json.loads(body); b["not_after"] += 1
    nb = json.dumps(b, separators=(",", ":"))
    db.execute("UPDATE ops SET body = ?, digest = ?, not_after = ?, settled = 0, committed = 0, acked = 0 WHERE op = ?", (nb, hashlib.sha256(nb.encode()).hexdigest(), b["not_after"], op))
for t in ("facts", "intents", "ledger"):
    db.execute(f"DELETE FROM {t}")
db.commit()
PY
rm -f "$T/run.db" "$T/run.db-wal" "$T/run.db-shm"
start_outbox
TIMEOUT=10 run
python3 - "$T/run.out" "$code" "$T/journal.db" "$T/outbox.err" <<'PY'
import json, sqlite3, sys
out, code, journal, err = json.load(open(sys.argv[1])), int(sys.argv[2]), sys.argv[3], open(sys.argv[4]).read()
rows = sqlite3.connect(journal).execute("SELECT state FROM journal WHERE kind = 'llm.complete'").fetchall()
assert out["settled"] >= 4096, f"a conflict was not charged: {out['settled']}"
assert "CONFLICT" in err, "a conflict was not reported"
assert not any(r[0] == "refused" for r in rows), rows
print(f"J ok: a conflicting attempt was charged as unknown, not refused (settled {out['settled']}, journal {sorted(set(rows))}, exit {code})")
PY

unacked() { admin GET /admin/state | python3 -c 'import json,sys; print(json.load(sys.stdin)["unacked_outcomes"])'; }
owed() { python3 -c "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute('SELECT COUNT(*) FROM obligations WHERE done = 0').fetchone()[0])" "$T/journal.db"; }
wait_for() { for _ in $(seq 100); do [ "$("$1")" = 0 ] && return 0; sleep 0.1; done; return 1; }

# K: killed between the journal's commit and the acknowledgement
start
kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true
CORDIS_OUTBOX_CRASH=after-journal start_outbox
TIMEOUT=5 run
sleep 0.5
kill -0 "$outbox_pid" 2>/dev/null && { echo "K: the outbox did not crash"; exit 1; }
[ "$(unacked)" -ge 1 ] || { echo "K: halo holds nothing unacknowledged; the crash did not land in the window"; exit 1; }
[ "$(owed)" -ge 1 ] || { echo "K: the journal did not record the obligation with the answer"; exit 1; }
start_outbox
wait_for unacked || { echo "K: halo's outcome was never acknowledged"; exit 1; }
wait_for owed || { echo "K: the obligation was never discharged"; exit 1; }
run; settle
python3 - "$T/run.out" "$code" <<'PY'
import json, sys
out, code = json.load(open(sys.argv[1])), int(sys.argv[2])
assert code == 0 and out["assets"]["shipped"] is True, (code, out)
print("K ok: killed between journal and acknowledgement; the next outbox acknowledged it, and the run shipped")
PY

# L: an expired outcome, killed after its usage is journaled
OUTBOX_ARGS="--ops-poll 1.6" start delay=0.2 capture=1
kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true
OUTBOX_ARGS="--ops-poll 1.6" CORDIS_OUTBOX_CRASH=after-expired-journal start_outbox
TIMEOUT=5 run
sleep 0.5
kill -0 "$outbox_pid" 2>/dev/null && { echo "L: the outbox did not crash"; exit 1; }
python3 - "$T/journal.db" <<'PY'
import sqlite3, sys
rows = sqlite3.connect(sys.argv[1]).execute("SELECT state, source, cost FROM journal WHERE kind = 'llm.complete'").fetchall()
assert rows and rows[0][0] == "unknown" and rows[0][1] == "expired" and 0 < rows[0][2] < 4096, rows
PY
[ "$(unacked)" -ge 1 ] || { echo "L: halo's expired outcome was already acknowledged before the journal held its usage"; exit 1; }
OUTBOX_ARGS="--ops-poll 1.6" start_outbox
wait_for unacked || { echo "L: the expired outcome was never acknowledged"; exit 1; }
wait_for owed || { echo "L: the obligation was never discharged"; exit 1; }
echo "L ok: killed after the expired usage was journaled; it was durable before halo was told, and the next outbox acknowledged it"

# M: a refused copy is fenced by a cancel before the cell sees the refusal
start; admin POST /admin/fault '{"refuse_submit": true}' > /dev/null
TIMEOUT=10 run
python3 - "$T/run.out" "$code" "$(admin GET /admin/state)" "$T/journal.db" <<'PY'
import json, sqlite3, sys
out, code, state, journal = json.load(open(sys.argv[1])), int(sys.argv[2]), json.loads(sys.argv[3]), sys.argv[4]
assert code == 2, f"a refused task fails the run: exit {code}"
tombs = [k for k, v in state["attempts"].items() if v == "cancelled"]
assert len(tombs) == 1 and state["generated"] == 0, (state["attempts"], state["generated"])
rows = sqlite3.connect(journal).execute("SELECT state FROM journal WHERE kind = 'llm.complete'").fetchall()
assert rows == [("refused",)], rows
assert out["settled"] <= 1 and out["reserved"] == 0, out
print(f"M ok: a refused copy was fenced by a cancel (tombstone {tombs[0].split('/')[1][:12]}…), nothing generated, then refused; reservation released")
PY
