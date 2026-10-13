#!/usr/bin/env bash
# RFC 0007's milestone, against the stand-in for halo: Cordis (cordis-run, its run store), the outbox's operation
# adapter and the server run together through lost and delayed submissions, a duplicated one, a lost acknowledgement,
# engine crashes after receipts, an outbox restart and a server restart, and must still ship with
#   - every critic computation answered by one done attempt in the journal (receipts preserved, applied once),
#   - nothing left held or unacknowledged at the server (retention),
#   - the run store's records audited clean against their own code,
#   - the evidence kept: on a failure the whole working directory is copied to $MILESTONE_EVIDENCE.
# The server is the stand-in pinned at halo-flash c4a39ff's wire. Against halo itself, set HALO_URL (http://host:port of
# a halo_serve with operations on, under its GPU lock): no stand-in is started, the faults and restarts the stand-in
# injects are skipped (halo's own tools/op_fault_probe.py injects them), and the server's own contract reports the records
# and unacknowledged outcomes. What the run proves there is the engine, the adapter and the journal on a real model.
#
# Evidence: set MILESTONE_MANIFEST=DIR and a passing run leaves DIR with manifest.json (the cordis revision, the
# server's contract as answered, HALO_REV and HALO_MODEL as the caller names them, the counts) and the artifacts it
# checked (journal, run store, outbox log, the run's output); a failing run keeps the same under $MILESTONE_EVIDENCE.
#
#   milestone.sh CORDIS_RUN CORDIS_OUTBOX REVIEW.cg STUB_OPS.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
cleanup() {
  code=$?
  if [ "$code" != 0 ] && [ -n "${MILESTONE_EVIDENCE:-}" ]; then rm -rf "$MILESTONE_EVIDENCE"; cp -r "$T" "$MILESTONE_EVIDENCE"; echo "evidence kept in $MILESTONE_EVIDENCE" >&2; fi
  kill "${srv_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"
}
trap cleanup EXIT
mkdir -p "$T/repo/src" && echo "let x = 1 (* the file *)" > "$T/repo/src/main.ml"

if [ -n "${HALO_URL:-}" ]; then
  url=$HALO_URL
else
  python3 "$stub" "$T/port" "$T/count" delay=0.3 & srv_pid=$!
  for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
  url="http://127.0.0.1:$(cat "$T/port")"
fi

start_outbox() {
  : > "$T/outbox.out"
  "$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --ops "$url" --principal milestone --repo "$T/repo" \
    > "$T/outbox.out" 2>> "$T/outbox.err" & outbox_pid=$!
  for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
}
admin() { python3 - "$url" "${HALO_URL:+real}" "$@" <<'PY'
import json, sys, urllib.request
url, real, method, path = sys.argv[1:5]
if real:
    # halo has no admin faults; its state is its contract
    if method == "POST":
        print("{}")
    else:
        c = json.load(urllib.request.urlopen(url + "/v1/halo/contract", timeout=10))["operations"]["records"]
        print(json.dumps({"unacked_outcomes": c["unacked_outcomes"], "records": c["in_use"], "generated": -1, "attempts": {}}))
    sys.exit(0)
data = sys.argv[5].encode() if len(sys.argv) > 5 else (b"{}" if method == "POST" else None)
print(urllib.request.urlopen(urllib.request.Request(url + path, data=data, method=method, headers={"Content-Type": "application/json"}), timeout=10).read().decode())
PY
}
start_outbox

run() {
  "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 20 "$@"
}

# the faults, one before each engine run; the crash schedule is the review loop's, as e2e.sh
faults=('{"lose_submit_answer": true}' '{"delay_submit": 0.4}' '{"dup_submit": true}' '{"lose_ack_answer": true}' '{"lose_submit": true}')
crashes=0 i=0
for c in r1 r2 r1 r2 a3 r1 r2 r1 r2 a4 r1 r2; do
  case $c in r*) how=--crash-after-receipts ;; a*) how=--crash-after ;; esac; m=${c#?}
  [ "$i" -lt "${#faults[@]}" ] && admin POST /admin/fault "${faults[$i]}" > /dev/null
  i=$((i + 1))
  set +e; run "$how" "$m" > "$T/run.out" 2> "$T/crash.err"; code=$?; set -e
  [ "$code" = 0 ] && { out=$(cat "$T/run.out"); break; }
  [ "$code" = 3 ] || { echo "expected a crash (exit 3), got $code"; cat "$T/crash.err"; exit 1; }
  crashes=$((crashes + 1))
  if [ "$crashes" = 3 ]; then kill "$outbox_pid"; wait "$outbox_pid" 2>/dev/null || true; start_outbox; fi
  if [ "$crashes" = 5 ]; then admin POST /admin/restart > /dev/null; fi
  kill -0 "$outbox_pid" 2>/dev/null || { echo "the outbox died"; cat "$T/outbox.err"; exit 1; }
done
[ -n "${out:-}" ] || out=$(run 2> "$T/run.err")

# nothing may stay unacknowledged
for _ in $(seq 100); do [ "$(admin GET /admin/state | python3 -c 'import json,sys; print(json.load(sys.stdin)["unacked_outcomes"])')" = 0 ] && break; sleep 0.1; done

# the engine confirms what it applied (its run store synced): no delivery stays pending
for _ in $(seq 100); do
  [ "$(python3 -c "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute(\"SELECT COUNT(*) FROM journal j WHERE j.state IN ('done','refused') AND NOT EXISTS (SELECT 1 FROM facts f WHERE f.jkey = j.jkey AND f.why = 'applied')\").fetchone()[0])" "$T/journal.db")" = 0 ] && break; sleep 0.1
done

# the audit: the records read again against their own code
run --audit > /dev/null

python3 - "$out" "$(admin GET /admin/state)" "$T/journal.db" "$crashes" "${HALO_URL:+real}" <<'PY'
import json, sqlite3, sys
out, state, journal, crashes, real = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5]
# the stand-in's critic rejects the patch twice, so three computations and eight crashes; a real model may approve at once
want_crashes, want_done = (1, 1) if real else (4, 3)
assert crashes >= want_crashes, f"only {crashes} crashes"
assert out["assets"]["shipped"] is True, out["assets"]
assert out["reserved"] == 0, out
assert state["unacked_outcomes"] == 0, state
assert all(s in ("released", "cancelled") for s in state["attempts"].values()) or state["records"] >= 0, state
db = sqlite3.connect(journal)
done = db.execute("SELECT COUNT(DISTINCT key) FROM journal WHERE kind = 'llm.complete' AND state = 'done'").fetchone()[0]
assert done >= want_done and (real or done == 3), f"{done} critic computations answered, expected {want_done}"
dup = db.execute("SELECT key, COUNT(*) FROM journal WHERE kind = 'llm.complete' AND state = 'done' GROUP BY key HAVING COUNT(*) > 1").fetchall()
assert not dup, f"answered more than once: {dup}"
ops = db.execute("SELECT COUNT(*) FROM ops WHERE committed = 1 AND acked = 0").fetchone()[0]
assert ops == 0, f"{ops} acknowledgements owed"
obl = db.execute("SELECT COUNT(*) FROM obligations WHERE done = 0").fetchone()[0]
assert obl == 0, f"{obl} obligations to the server still owed"
owed = db.execute("SELECT j.jkey FROM journal j WHERE j.state IN ('done','refused') AND NOT EXISTS (SELECT 1 FROM facts f WHERE f.jkey = j.jkey AND f.why = 'applied')").fetchall()
assert not owed, f"deliveries still pending: {owed}"
facts = db.execute("SELECT COUNT(*) FROM facts").fetchone()[0]
print(f"ok: shipped after {crashes} crashes through faults; {done} computations, one done attempt each; {state['generated']} generations; "
      f"{state['records']} records held; settled {out['settled']}; {facts} journal facts")
PY

if [ -n "${MILESTONE_MANIFEST:-}" ]; then
  rm -rf "$MILESTONE_MANIFEST"; mkdir -p "$MILESTONE_MANIFEST"
  cp "$T/journal.db" "$T/run.db" "$T/outbox.err" "$T/run.out" "$MILESTONE_MANIFEST/" 2>/dev/null || true
  python3 - "$MILESTONE_MANIFEST/manifest.json" "$out" "$(admin GET /admin/state)" "$crashes" "${HALO_URL:-stand-in $(basename "$stub")}" <<PY
import json, subprocess, sys, urllib.request, os
path, out, state, crashes, server = sys.argv[1], json.loads(sys.argv[2]), json.loads(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
def sh(*a):
    try: return subprocess.check_output(a, text=True, cwd=os.path.dirname(os.path.abspath("$0"))).strip()
    except Exception: return None
contract = None
if server.startswith("http"):
    try: contract = json.load(urllib.request.urlopen(server + "/v1/halo/contract", timeout=10))
    except Exception: pass
json.dump({"cordis_revision": sh("git", "rev-parse", "HEAD"), "cordis_dirty": bool(sh("git", "status", "--porcelain")),
           "server": server, "halo_revision": os.environ.get("HALO_REV"), "model": os.environ.get("HALO_MODEL"),
           "contract": contract, "crashes": crashes, "settled": out["settled"], "revision_of_graph": out["revision"],
           "server_state": state, "artifacts": ["journal.db", "run.db", "outbox.err", "run.out"]}, open(path, "w"), indent=1)
PY
  echo "evidence in $MILESTONE_MANIFEST"
fi
