#!/usr/bin/env bash
# The review loop against a stand-in with a real model's habits (the
# halo-flash run's findings), each phase on a fresh store, journal and
# server:
#   A. calls slower than the runner's --timeout, answers long and
#      multi-line: a slow call is not a lost one (quint/intake.qnt): one
#      completion per critic computation, none charged as lost, the notes
#      held to their bound, and it ships;
#   B. every answer cut off by its length: never a verdict, so the loop
#      rests at its cap unshipped, says so, and exits 4;
#   C. no usage reported: each completion is charged its worst case;
#   D. HTTP 400: a definite failure, run once, not retried; exit 2.
#
#   latency.sh CORDIS_RUN CORDIS_OUTBOX REVIEW.cg STUB_LLM.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
cleanup() { kill "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
mkdir -p "$T/repo/src" && echo "let x = 1 (* the file *)" > "$T/repo/src/main.ml"

start() {
  kill "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  wait "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true
  rm -f "$T/port" "$T/count" "$T/run.db" "$T/journal.db" "$T/outbox.out"
  python3 "$stub" "$T/port" "$T/count" "$@" & llm_pid=$!
  for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
  "$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --llm "http://127.0.0.1:$(cat "$T/port")" --repo "$T/repo" \
    > "$T/outbox.out" 2> "$T/outbox.err" & outbox_pid=$!
  for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
}

run() {
  set +e
  "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 1 "$@" > "$T/run.out" 2> "$T/run.err"
  code=$?
  set -e
}

check() { # phase code
  python3 - "$1" "$2" "$T/run.out" "$T/count" "$T/journal.db" "$T/run.err" <<'PY'
import json, sqlite3, sys
phase, code, out, count, journal, err = sys.argv[1], int(sys.argv[2]), json.load(open(sys.argv[3])), int(open(sys.argv[4]).read()), sys.argv[5], open(sys.argv[6]).read()
rows = sqlite3.connect(journal).execute("SELECT jkey, state FROM journal WHERE kind = 'llm.complete'").fetchall()
a = out["assets"]
if phase == "A":
    assert code == 0 and out["outcome"] == "rest", (code, out, err)
    assert a["shipped"] is True, a
    assert count == len(rows) and all(s == "done" for _, s in rows), (count, rows)
    assert all(k.endswith("#1") for k, _ in rows), f"an attempt was retried: {rows}"
    assert out["settled"] == 10 * count + count, f"charged as lost: {out['settled']} for {count} completions"
    # (each critic computation reads the file once too: one unit)
    # every line of a note is a comment in the draft
    assert all(l.startswith("// ") for l in a["draft"]["patch"].split("\n")[1:]), a["draft"]["patch"]
    print(f"A ok: {count} slow completions, each once, none charged as lost; settled {out['settled']}")
elif phase == "B":
    assert code == 4 and out["outcome"] == "rest_unbound", (code, out)
    assert out["unbound"] == ["shipped"] and a["verdict"]["tag"] == "Fail", out
    assert out["refused"] >= 1, out
    print(f"B ok: cut-off answers never approved; rested unshipped (exit 4), {out['refused']} append refused at the cap")
elif phase == "C":
    assert code == 0 and a["shipped"] is True, (code, out)
    assert out["settled"] == 4096 * count + count, f"no usage, yet settled {out['settled']} for {count} completions"
    print(f"C ok: no usage reported, {count} completions charged the worst case: settled {out['settled']}")
elif phase == "D":
    assert code == 2 and out["outcome"] == "failed", (code, out)
    assert count == 1 and rows and rows[0][1] == "refused", (count, rows)
    assert "refused" in json.dumps(out["failures"]), out
    assert out["reserved"] == 0 and out["settled"] == 1, out
    print(f"D ok: HTTP 400 refused once, not retried, nothing charged for it; failed (exit 2)")
PY
}

start delay=3 long; run; check A $code
start length; run; check B $code
start no-usage; run; check C $code
start refuse; run; check D $code
