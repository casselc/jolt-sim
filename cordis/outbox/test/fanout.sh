#!/usr/bin/env bash
# N agents at once against one completions server (the stand-in here; a
# real server for a halo-flash run), each with its own outbox, run store,
# journal and a fresh --agent: contention, the server's 429s and their
# Retry-After, and exactly-once under them. Per agent it checks:
#   - it came to rest (exit 0, or 4: at rest unshipped);
#   - one answered completion per completion intent, and no send left
#     unknown (a lost keyed send is superseded);
#   - settled is what the journal says the calls cost (no worst case
#     charged for a lost send, none for a deferred one);
# and prints one JSON line per agent and a summary, deferrals included
# (the outbox's "again in" lines: 429s and 503s waited out).
#
#   fanout.sh CORDIS_RUN CORDIS_OUTBOX GRAPH.cg GRAPH_NAME LLM_URL N
#             [--keyed] [--input JSON] [--repo DIR] [--budget N]
#   LLM_URL "stub:MODE,..." starts the stand-in (stub_llm.py beside this script)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
run_bin=$1 outbox_bin=$2 graph=$3 name=$4 llm=$5 n=$6
shift 6
class=--observational input='{"path":"src/main.ml","patch":"let x = "}' repo="" budget=1000000
while [ $# -gt 0 ]; do
  case $1 in
    --keyed) class=--keyed; shift ;;
    --input) input=$2; shift 2 ;;
    --repo) repo=$2; shift 2 ;;
    --budget) budget=$2; shift 2 ;;
    *) echo "fanout.sh: unknown $1"; exit 2 ;;
  esac
done
T=$(mktemp -d)
pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT
if [ -z "$repo" ]; then repo=$T/repo; mkdir -p "$repo/src" && echo "let x = 1 (* the file *)" > "$repo/src/main.ml"; fi
case $llm in
  stub:*)
    IFS=, read -r -a modes <<< "${llm#stub:}"
    python3 "$here/stub_llm.py" "$T/port" "$T/count" "${modes[@]}" & pids+=($!)
    for _ in $(seq 100); do [ -s "$T/port" ] && break; sleep 0.1; done
    llm="http://127.0.0.1:$(cat "$T/port")" ;;
esac
stamp=$(date +%s)-$$
for i in $(seq "$n"); do
  d=$T/agent$i; mkdir -p "$d"
  "$outbox_bin" --socket "$d/outbox.sock" --journal "$d/journal.db" --llm "$llm" --repo "$repo" --agent "fanout-$stamp-$i" \
    > "$d/outbox.out" 2> "$d/outbox.err" & pids+=($!)
done
for i in $(seq "$n"); do for _ in $(seq 100); do grep -q ready "$T/agent$i/outbox.out" 2>/dev/null && break; sleep 0.1; done; done
runs=()
for i in $(seq "$n"); do
  d=$T/agent$i
  ( set +e
    "$run_bin" --graph "$graph" --name "$name" --store "$d/run.db" --socket "$d/outbox.sock" --input "request=$input" \
      "$class" llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget "$budget" --timeout 10 > "$d/run.out" 2> "$d/run.err"
    echo $? > "$d/code" ) & runs+=($!)
done
wait "${runs[@]}"
python3 - "$T" "$n" <<'PY'
import json, os, sqlite3, sys
T, n = sys.argv[1], int(sys.argv[2])
bad, total_deferred, total_completions = [], 0, 0
for i in range(1, n + 1):
    d = f"{T}/agent{i}"
    code = int(open(f"{d}/code").read())
    out = json.load(open(f"{d}/run.out")) if os.path.getsize(f"{d}/run.out") else {}
    rows = sqlite3.connect(f"{d}/journal.db").execute("SELECT jkey, key, kind, state, cost FROM journal").fetchall()
    llm = [r for r in rows if r[2] == "llm.complete"]
    intents = {r[1] for r in llm}
    done = [r for r in llm if r[3] == "done"]
    unknown = [r for r in rows if r[3] in ("unknown", "sent", "waiting")]
    costs = sum(r[4] for r in rows if r[3] == "done")
    deferred = sum(1 for l in open(f"{d}/outbox.err") if "again in" in l)
    total_deferred += deferred
    total_completions += len(done)
    line = {"agent": i, "code": code, "outcome": out.get("outcome"), "intents": len(intents), "answered": len(done),
            "superseded": sum(1 for r in llm if r[3] == "superseded"), "unknown": len(unknown), "deferred": deferred,
            "settled": out.get("settled"), "journal_cost": costs}
    print(json.dumps(line))
    why = []
    if code not in (0, 4): why.append(f"exit {code}: {open(f'{d}/run.err').read().strip()[-300:]}")
    if len(done) != len(intents): why.append("not one answered completion per intent")
    if unknown: why.append(f"sends left unknown: {unknown}")
    if out.get("settled") != costs or out.get("reserved") != 0: why.append(f"settled {out.get('settled')} (reserved {out.get('reserved')}), the journal's costs {costs}")
    if why: bad.append((i, why))
print(json.dumps({"agents": n, "completions": total_completions, "deferred": total_deferred, "failed": len(bad)}))
for i, why in bad:
    print(f"agent {i}: " + "; ".join(why))
sys.exit(1 if bad else 0)
PY
