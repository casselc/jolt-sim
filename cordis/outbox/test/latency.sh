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
#   D. HTTP 400: a definite failure, run once, not retried; exit 2;
#   F. a reply that reasons first and gives its verdict on the last line:
#      approved at the first review;
#   G. HTTP 429 with Retry-After: the request waits that long, then runs;
#   E. a server that keeps idempotency keys, which drops the first answer
#      after generating it: llm.complete run keyed is sent again with the
#      same key and answered from what the server kept, generated once
#      (it says so: Halo-Answer: replayed), and the lost send not charged;
#   H. a server that keeps nothing of the first answer (past its grace
#      window: it stopped the generation): the resend is generated afresh,
#      says so (Halo-Answer: generated), and the lost send is charged its
#      worst case (quint/observe.qnt's graceExpiryReported).
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
  rm -f "$T/port" "$T/count" "$T/count.thinking" "$T/count.temperature" "$T/count.generated" "$T/count.times" "$T/run.db" "$T/journal.db" "$T/outbox.out"
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
    ${CLASS:---observational} llm.complete --worst llm.complete=4096 --worst repo.read=1 --budget 100000 --timeout 1 "$@" > "$T/run.out" 2> "$T/run.err"
  code=$?
  set -e
}

check() { # phase code
  python3 - "$1" "$2" "$T/run.out" "$T/count" "$T/journal.db" "$T/run.err" <<'PY'
import json, sqlite3, sys
phase, code, out, count, journal, err = sys.argv[1], int(sys.argv[2]), json.load(open(sys.argv[3])), int(open(sys.argv[4]).read()), sys.argv[5], open(sys.argv[6]).read()
db = sqlite3.connect(journal)
rows = db.execute("SELECT jkey, state FROM journal WHERE kind = 'llm.complete'").fetchall()
sources = [s for (s,) in db.execute("SELECT source FROM journal WHERE kind = 'llm.complete' AND state = 'done'")]
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
    # the critic asks the model not to think, and the receipt says it did not
    thinking = [l.strip() for l in open(sys.argv[4] + ".thinking")]
    assert thinking and all(t == "false" for t in thinking), thinking
    temperature = [l.strip() for l in open(sys.argv[4] + ".temperature")]
    assert temperature and all(t == "0.0" for t in temperature), temperature
    print(f"A ok: {count} slow completions, each once, none charged as lost, thinking off; settled {out['settled']}")
elif phase == "B":
    assert code == 4 and out["outcome"] == "rest_unbound", (code, out)
    assert out["unbound"] == ["shipped"] and a["verdict"]["tag"] == "Fail", out
    assert out["refused"] >= 1, out
    print(f"B ok: cut-off answers never approved; rested unshipped (exit 4), {out['refused']} append refused at the cap")
elif phase == "C":
    assert code == 0 and a["shipped"] is True, (code, out)
    assert out["settled"] == 4096 * count + count, f"no usage, yet settled {out['settled']} for {count} completions"
    print(f"C ok: no usage reported, {count} completions charged the worst case: settled {out['settled']}")
elif phase == "E":
    assert code == 0 and a["shipped"] is True, (code, out, err)
    generated = int(open(sys.argv[4] + ".generated").read())
    assert count == generated + 1, f"requests {count}, generated {generated}: the resend should be answered from the kept key"
    # the dropped send is marked superseded by the send that was answered
    assert [s for _, s in rows].count("superseded") == 1 and [s for _, s in rows].count("unknown") == 0, rows
    # one intent sent twice (its first send's answer dropped), every other once
    sends = {}
    for k, _ in rows:
        sends[k.rsplit("#", 1)[0]] = sends.get(k.rsplit("#", 1)[0], 0) + 1
    assert sorted(sends.values()) == [1] * (len(sends) - 1) + [2], rows
    # the dropped send is released, not charged its worst case: 10 tokens a generation, 1 a read
    assert out["settled"] == 10 * generated + generated and out["reserved"] == 0, out
    assert sources.count("replayed") == 1 and sources.count("generated") == len(sources) - 1, sources
    print(f"E ok: a dropped answer resent with its key and replayed: {count} requests, {generated} generated; settled {out['settled']}, the lost send not charged")
elif phase == "H":
    assert code == 0 and a["shipped"] is True, (code, out, err)
    generated = int(open(sys.argv[4] + ".generated").read())
    assert count == generated, f"requests {count}, generated {generated}: every request generates"
    # the first send's outcome stays unknown: no answer covers it
    assert [s for _, s in rows].count("unknown") == 1 and [s for _, s in rows].count("superseded") == 0, rows
    assert all(s == "generated" for s in sources), sources
    # the forgotten send is charged its worst case; every answered one its 10 tokens, and one read per
    # computation (one completion each, the forgotten one aside)
    computations = generated - 1
    assert out["settled"] == 4096 + 10 * computations + computations and out["reserved"] == 0, out
    print(f"H ok: a forgotten answer generated afresh and said so: the lost send charged its worst case; settled {out['settled']}")
elif phase == "F":
    assert code == 0 and a["shipped"] is True and count == 1, (code, count, out)
    assert "//" not in a["draft"]["patch"], a["draft"]
    print("F ok: a verdict on the last line approved at the first review")
elif phase == "G":
    assert code == 0 and a["shipped"] is True, (code, out, err)
    times = [float(l) for l in open(sys.argv[4] + ".times")]
    assert times[1] - times[0] >= 1.9, f"asked again {times[1] - times[0]:.2f}s after a Retry-After of 2"
    assert all(s == "done" for _, s in rows) and out["reserved"] == 0, (rows, out)
    print(f"G ok: a 429 waited out its Retry-After ({times[1] - times[0]:.1f}s), then answered")
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
start keys drop-first; CLASS=--keyed run; check E $code
start keys forget-first; CLASS=--keyed run; check H $code
start approve verdict-last; run; check F $code
start busy-first=2; run; check G $code
