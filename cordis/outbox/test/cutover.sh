#!/usr/bin/env bash
# Admission across processes (RFC 0006 decision 6; quint/cutover.qnt):
# cordis-run --admit soaks a candidate in a shadow answered from the run's
# own records and cuts the running instance over to it.
#   1. the review loop runs until a crash part way;
#   2. a candidate whose critic divides by zero fails its soak: refused,
#      the store unchanged;
#   3. a candidate that rewires the graph is refused, the store unchanged;
#   4. a candidate whose critic asks for max_tokens 4001 is admitted, and
#      the runner crashes in the middle of the cutover;
#   5. --admit asked again finishes it (an admission is idempotent);
#   6. a plain restart with --audit audits the records clean across both
#      revisions, from the revisions the store keeps.
# The store holds both revisions' texts, its records the cutover's begin
# and end, and the outbox's journal shows the completions asked for with
# 4000 until the switch and 4001 after it.
#
#   cutover.sh CORDIS_RUN CORDIS_OUTBOX REVIEW.cg STUB_LLM.py
set -euo pipefail
run_bin=$1 outbox_bin=$2 graph=$3 stub=$4
T=$(mktemp -d)
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT

python3 "$stub" "$T/port" "$T/count" & pids+=($!)
for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
mkdir -p "$T/repo/src" && echo "let x = 1" > "$T/repo/src/main.ml"
"$outbox_bin" --socket "$T/outbox.sock" --journal "$T/journal.db" --llm "http://127.0.0.1:$(cat "$T/port")" --repo "$T/repo" > "$T/outbox.out" & pids+=($!)
for _ in $(seq 100); do grep -q ready "$T/outbox.out" 2>/dev/null && break; sleep 0.1; done

sed 's/max_tokens: 4000/max_tokens: 4001/' "$graph" > "$T/good.cg"
# the fault is where every run of the critic reaches it (behind && it would hide on the paths the
# records can answer: a shadow exercises only those)
sed "s/: resp.text};/: resp.text + string(1 \/ (size(resp.text) - size(resp.text)))};/" "$graph" > "$T/broken.cg"
sed 's/up to 8/up to 7/' "$graph" > "$T/rewired.cg"
cmp -s "$graph" "$T/broken.cg" && { echo "the broken candidate is not broken"; exit 1; }

run() {
  "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket "$T/outbox.sock" \
    --input 'request={"path":"src/main.ml","patch":"let x = 1"}' \
    --observational llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 10 "$@"
}
records() { "$run_bin" --graph "$graph" --name review_loop --store "$T/run.db" --socket /dev/null --dump-records | wc -l; }
expect() { local want=$1; shift; set +e; "$@" > "$T/out" 2> "$T/err"; local code=$?; set -e
  [ "$code" = "$want" ] || { echo "expected exit $want, got $code: $*"; cat "$T/err"; exit 1; }; }

expect 3 run --crash-after-receipts 3
n=$(records)
expect 2 run --admit "$T/broken.cg"
grep -q "failed its soak" "$T/err" || { echo "the broken candidate was refused, but not for its soak:"; cat "$T/err"; exit 1; }
[ "$(records)" = "$n" ] || { echo "a refused candidate changed the store"; exit 1; }
expect 2 run --admit "$T/rewired.cg"
grep -q "not admitted" "$T/err" || { echo "the rewiring was refused, but not as a rewiring:"; cat "$T/err"; exit 1; }
[ "$(records)" = "$n" ] || { echo "a refused candidate changed the store"; exit 1; }
expect 3 run --admit "$T/good.cg" --crash-after 3
expect 0 run --admit "$T/good.cg"
final=$(cat "$T/out")
expect 0 run --audit
again=$(cat "$T/out")

python3 - "$final" "$again" "$T" <<'PY'
import json, sqlite3, sys
final, again, T = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
run = sqlite3.connect(f"{T}/run.db")
revs = run.execute("SELECT id, role FROM revisions ORDER BY rowid").fetchall()
assert [r for _, r in revs] == ["start", "candidate"], revs
candidate = revs[1][0]
assert final["revision"] == candidate and again["revision"] == candidate, (final["revision"], again["revision"], candidate)
assert final["assets"]["shipped"] is True, final
assert again["assets"] == final["assets"], "the restart changed the rest"
bodies = [b for (b,) in run.execute("SELECT body FROM records ORDER BY seq")]
begins = sum('"cutover-begin"' in b for b in bodies); ends = sum('"cutover-end"' in b for b in bodies)
assert begins == 1 and ends == 1, (begins, ends)
reqs = [r for (r,) in sqlite3.connect(f"{T}/journal.db").execute("SELECT request FROM journal WHERE kind = 'llm.complete' ORDER BY rowid")]
tokens = []
for r in reqs:
    m = dict((k[1], v) for k, v in json.loads(r)[1])
    tokens.append(int(m["max_tokens"][1]))
switches = [t for i, t in enumerate(tokens) if i == 0 or t != tokens[i - 1]]
assert switches == [4000, 4001], f"completions asked with max_tokens {tokens}"
print(f"ok: cut over to {candidate[:19]}...; refused a failed soak and a rewiring; completions {tokens}")
PY
