#!/usr/bin/env bash
# The review loop with a writer that applies the notes (graph/examples/
# review_fix.cg), end to end against the stand-in: the writer is asked with
# no notes and returns the patch as it is, the critic rejects it, the writer
# applies the note (its reply fenced, which it strips), and the critic
# approves the fixed patch. Four completions (two writer, two critic), and
# the shipped draft is code, not comments.
#
#   fix.sh CORDIS_RUN CORDIS_OUTBOX REVIEW_FIX.cg STUB_LLM.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
cleanup() { kill "${llm_pid:-}" "${outbox_pid:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
mkdir -p "$T/repo/src" && echo "let x = 1 (* the file *)" > "$T/repo/src/main.ml"
python3 "$stub" "$T/port" "$T/count" fenced & llm_pid=$!
for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
"$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --llm "http://127.0.0.1:$(cat "$T/port")" --repo "$T/repo" \
  > "$T/outbox.out" 2> "$T/outbox.err" & outbox_pid=$!
for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done
set +e
"$run_bin" --graph "$graph" --name fix_loop --store "$T/run.db" --socket "$T/outbox.sock" \
  --input 'request={"path":"src/main.ml","patch":"let x = 1"}' --observational llm.complete \
  --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 5 > "$T/run.out" 2> "$T/run.err"
code=$?
set -e
python3 - "$code" "$T/run.out" "$T/count" "$T/journal.db" "$T/run.err" <<'PY'
import json, sqlite3, sys
code, out, count, journal, err = int(sys.argv[1]), json.load(open(sys.argv[2])), int(open(sys.argv[3]).read()), sys.argv[4], open(sys.argv[5]).read()
a = out["assets"]
assert code == 0 and a["shipped"] is True, (code, out, err)
assert a["draft"]["patch"] == "let x = 1\n(fixed)", a["draft"]
rows = sqlite3.connect(journal).execute("SELECT jkey, kind, state FROM journal ORDER BY rowid").fetchall()
done = [r for r in rows if r[1] == "llm.complete" and r[2] == "done"]
assert count == 4 and len(done) == 4, (count, rows)
print(f"ok: the writer applied the note (fences stripped) and the fixed patch shipped; {count} completions")
PY
