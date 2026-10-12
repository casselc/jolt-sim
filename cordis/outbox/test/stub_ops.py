"""A stand-in for halo-flash's operation API (docs/cordis/rfc/0007-boundaries.md;
halo-flash's docs/OPERATIONS.md, revision 3): the verbs, the incarnation, the
monotonic clock, tombstones, a capture window, a records ceiling, and faults a
test can switch on. It answers the review loop's completions as stub_llm.py does.

    python3 stub_ops.py PORT_FILE COUNT_FILE [MODE...]

Verbs (all requests carry Halo-Incarnation, Halo-Not-After and Halo-Digest; all
answers carry Halo-Incarnation and Halo-Time):
  GET  /v1/halo/contract
  POST /v1/operations                       {operation_id, attempt, not_after, api, request, mode}
  GET  /v1/operations/{op}/{attempt}        status
  GET  /v1/operations/{op}/{attempt}/result the body and usage, while held
  POST /v1/operations/{op}/{attempt}/cancel
  POST /v1/operations/{op}/{attempt}/ack

Modes (startup):
  delay=S          a submit runs for S seconds before it finishes
  capture=S        the capture window (default 3600)
  records=N        the records ceiling (default 100000)
  approve          approve every patch
  no-usage         no usage reported (the worst case is charged)

Faults (POST /admin/fault, JSON; each applies once, to the next matching request):
  {"lose_submit": true}          the next submit is lost: nothing is recorded, nothing is answered
  {"lose_submit_answer": true}   the next submit is accepted and its answer is lost
  {"delay_submit": S}            the next submit is held S seconds before it is processed (a late copy)
  {"dup_submit": true}           the next submit is processed twice
  {"lose_ack_answer": true}      the next ack is applied and its answer is lost
  {"lose_cancel": true}          the next cancel is lost
  POST /admin/restart            a new incarnation and clock: everything held is gone
  GET  /admin/state              what the server holds, for a test to read
"""
import hashlib
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

port_file, count_file = sys.argv[1], sys.argv[2]
modes = sys.argv[3:]


def mode(name, default):
    for m in modes:
        if m.startswith(name + "="):
            return float(m.split("=", 1)[1])
    return default


DELAY = mode("delay", 0.0)
CAPTURE_S = mode("capture", 3600.0)
MAX_RECORDS = int(mode("records", 100000))
MAX_SUBMIT_AGE_S = 600.0

lock = threading.RLock()
faults = {}
state = {}  # reset by restart()
requests = 0


def restart():
    global state
    with lock:
        state = {
            "incarnation": hashlib.sha256(str(time.time_ns()).encode()).hexdigest()[:32],
            "t0": time.monotonic(),
            "attempts": {},  # (owner, op, attempt) -> record
            "generated": 0,
        }


restart()


def now_ms():
    return int((time.monotonic() - state["t0"]) * 1000)


def digest_of(api, request, not_after, mode_):
    return hashlib.sha256(json.dumps([api, request, not_after, mode_], sort_keys=True).encode()).hexdigest()


def generate(req):
    """The stand-in's answer to a chat completion, as stub_llm.py's."""
    prompt = req["messages"][-1]["content"]
    parts = prompt.split("\n\n")
    context = parts[-2] if len(parts) >= 2 else parts[0]
    writer = prompt.startswith("Apply the review notes")
    approve = prompt.count("\n// ") >= 2 or "(fixed)" in prompt or "approve" in modes
    if writer:
        _, patch, notes = (prompt.split("\n\n", 2) + ["", ""])[:3]
        text = patch if not notes.strip() else patch + "\n(fixed)"
    elif approve:
        text = "LGTM"
    else:
        text = "needs work (" + context + ")"
    return text, "stop"


def usage_for(req, text):
    if "no-usage" in modes:
        return None
    prompt = req["messages"][-1]["content"]
    return {"prompt": max(1, len(prompt) // 4), "cached": 0, "completion": 10}


def start_run(rec):
    """Execute the attempt: it finishes after DELAY, unless cancelled first."""

    def run():
        end = time.monotonic() + DELAY
        while time.monotonic() < end:
            if rec["cancel_requested"]:
                break
            time.sleep(0.01)
        with lock:
            if rec["phase"] != "running":
                return
            text, finish = generate(rec["request"])
            usage = usage_for(rec["request"], text)
            if rec["cancel_requested"]:
                finish, text = "cancelled", text[:2]
                usage = {"prompt": usage["prompt"], "cached": 0, "completion": 1} if usage else None
            rec.update(phase="done", body=text, finish=finish, usage=usage, done_ms=now_ms(),
                       expires_at=now_ms() + int(CAPTURE_S * 1000))

    threading.Thread(target=run, daemon=True).start()


def expire_bodies():
    """The capture window ends: the body goes, the outcome stays until it is acknowledged."""
    t = now_ms()
    for rec in state["attempts"].values():
        if rec["phase"] == "done" and t > rec["expires_at"]:
            rec.update(phase="expired", body=None)


def records_in_use():
    return len(state["attempts"])


def status_of(owner, op, attempt, not_after, digest):
    """The answer to a status query, against the deadline and digest it carries."""
    expire_bodies()
    rec = state["attempts"].get((owner, op, attempt))
    t = now_ms()
    if rec is None:
        # no record: not accepted yet, or no copy can be accepted any more
        return ({"status": "closed"} if t > not_after else {"status": "never_accepted"}), 200
    if digest and rec["digest"] != digest:
        return {"error": "conflict"}, 409
    ph = rec["phase"]
    if ph == "running":
        return {"status": "running"}, 200
    if ph == "done":
        return {"status": "done", "finish": rec["finish"], "usage": rec["usage"], "expires_at": rec["expires_at"]}, 200
    if ph == "expired":
        return {"status": "expired", "finish": rec["finish"], "usage": rec["usage"]}, 200
    if ph == "released":
        return {"status": "released"}, 200
    if ph == "cancelled_before_accept":
        return {"status": "cancelled_before_accept"}, 200
    return {"status": "unknown"}, 200


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, code, obj, extra=()):
        out = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.send_header("Halo-Incarnation", state["incarnation"])
        self.send_header("Halo-Time", str(now_ms()))
        for h in extra:
            self.send_header(*h)
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(out)

    def drop(self):
        self.close_connection = True
        self.connection.shutdown(2)

    def owner(self):
        return self.headers.get("Authorization", "anon")

    def carried(self):
        """The incarnation, deadline and digest a request carries."""
        inc = self.headers.get("Halo-Incarnation")
        na = self.headers.get("Halo-Not-After")
        return inc, (int(na) if na else None), self.headers.get("Halo-Digest")

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n)) if n else {}

    # ---- GET ----
    def do_GET(self):
        global requests
        with lock:
            requests += 1
            path = self.path.split("?")[0]
            if path == "/admin/state":
                expire_bodies()
                return self.reply(200, {"incarnation": state["incarnation"], "records": records_in_use(),
                                        "generated": state["generated"],
                                        "unacked_outcomes": sum(1 for r in state["attempts"].values() if r["phase"] in ("done", "expired")),
                                        "attempts": {"/".join(map(str, k)): v["phase"] for k, v in state["attempts"].items()}})
            if path == "/v1/halo/contract":
                expire_bodies()
                unacked = [r for r in state["attempts"].values() if r["phase"] in ("done", "expired")]
                oldest = max([now_ms() - r["done_ms"] for r in unacked], default=0)
                return self.reply(200, {
                    "incarnation": state["incarnation"], "time_ms": now_ms(),
                    "retention": {"capture_s": CAPTURE_S, "until": "ack or capture window"},
                    "records": {"max": MAX_RECORDS, "in_use": records_in_use(), "unacked_outcomes": len(unacked), "oldest_unacked_ms": oldest},
                    "dedup": {"scope": "owner, incarnation", "max_submit_age_s": MAX_SUBMIT_AGE_S},
                    "cancel": "next unit boundary; usage reported",
                    "cost": "tokens: prompt, cached, completion (disjoint)",
                    "repeat": {"external_effects": "none", "spend": "a new attempt spends again"}})
            parts = path.strip("/").split("/")
            if len(parts) >= 4 and parts[:2] == ["v1", "operations"]:
                inc, na, dg = self.carried()
                if inc != state["incarnation"]:
                    return self.reply(200, {"status": "unknown"})
                op, attempt = parts[2], parts[3]
                if len(parts) == 4:
                    obj, code = status_of(self.owner(), op, int(attempt), na if na is not None else 0, dg)
                    return self.reply(code, obj)
                if parts[4] == "result":
                    expire_bodies()
                    rec = state["attempts"].get((self.owner(), op, int(attempt)))
                    if rec is None or rec["phase"] != "done":
                        return self.reply(410, {"status": rec["phase"] if rec else "never_accepted"})
                    return self.reply(200, {"body": rec["body"], "finish": rec["finish"], "usage": rec["usage"]})
            return self.reply(404, {"error": "not found"})

    # ---- POST ----
    def do_POST(self):
        global requests
        path = self.path.split("?")[0]
        if path == "/admin/restart":
            restart()
            return self.reply(200, {"incarnation": state["incarnation"]})
        if path == "/admin/fault":
            with lock:
                faults.update(self.body())
            return self.reply(200, faults)
        if path == "/v1/operations":
            return self.submit()
        parts = path.strip("/").split("/")
        if len(parts) == 5 and parts[:2] == ["v1", "operations"] and parts[4] in ("cancel", "ack"):
            return self.cancel_or_ack(parts[2], int(parts[3]), parts[4])
        return self.reply(404, {"error": "not found"})

    def submit(self):
        global requests
        body = self.body()
        with lock:
            requests += 1
            with open(count_file, "w") as f:
                f.write(str(requests))
            if faults.pop("lose_submit", None):
                return self.drop()
            late = faults.pop("delay_submit", None)
            dup = faults.pop("dup_submit", None)
            lose_answer = faults.pop("lose_submit_answer", None)
        if late:
            time.sleep(float(late))
        with lock:
            code, obj = self.process_submit(body)
            if dup:
                self.process_submit(body)
            if lose_answer:
                return self.drop()
            return self.reply(code, obj)

    def process_submit(self, body):
        inc, _, _ = self.carried()
        owner, op, attempt = self.owner(), body["operation_id"], body["attempt"]
        if inc != state["incarnation"]:
            return 409, {"error": "stale_incarnation", "incarnation": state["incarnation"]}
        not_after = body["not_after"]
        digest = digest_of(body["api"], body["request"], not_after, body.get("mode", "relaxed"))
        if self.headers.get("Halo-Digest") not in (None, digest):
            return 409, {"error": "conflict"}
        if not_after > now_ms() + MAX_SUBMIT_AGE_S * 1000:
            return 400, {"error": "not_after too far ahead"}
        key = (owner, op, attempt)
        rec = state["attempts"].get(key)
        if rec is not None:
            if rec["digest"] != digest:
                return 409, {"error": "conflict"}
            return 200, status_of(owner, op, attempt, not_after, digest)[0]  # a duplicate: the current status
        if now_ms() > not_after:
            return 410, {"error": "past not_after"}
        if records_in_use() >= MAX_RECORDS:
            return 503, {"error": "retention", "reason": "records"}
        rec = {"digest": digest, "not_after": not_after, "request": body["request"], "phase": "running",
               "cancel_requested": False, "body": None, "finish": None, "usage": None, "done_ms": 0, "expires_at": 0}
        state["attempts"][key] = rec
        state["generated"] += 1
        start_run(rec)
        return 202, {"status": "accepted"}

    def cancel_or_ack(self, op, attempt, verb):
        with lock:
            inc, na, dg = self.carried()
            if faults.pop("lose_cancel", None) and verb == "cancel":
                return self.drop()
            if inc != state["incarnation"]:
                return self.reply(409, {"error": "stale_incarnation", "incarnation": state["incarnation"]})
            key = (self.owner(), op, attempt)
            rec = state["attempts"].get(key)
            lose_answer = verb == "ack" and faults.pop("lose_ack_answer", None)
            if verb == "cancel":
                if rec is None:
                    # never accepted: the id is tombstoned with the carried deadline and digest
                    state["attempts"][key] = {"digest": dg, "not_after": na or 0, "request": None, "phase": "cancelled_before_accept",
                                              "cancel_requested": True, "body": None, "finish": None, "usage": None,
                                              "done_ms": 0, "expires_at": 0}
                    return self.reply(200, {"status": "cancelled_before_accept"})
                if dg and rec["digest"] and rec["digest"] != dg:
                    return self.reply(409, {"error": "conflict"})
                if rec["phase"] == "running":
                    rec["cancel_requested"] = True
                    return self.reply(200, {"status": "stopping"})
                return self.reply(200, status_of(*key, rec["not_after"], rec["digest"])[0])
            # ack: the outbox has committed the result: the body is released, the outcome kept until pruned
            if rec is None:
                return self.reply(404, {"error": "no such attempt"})
            if rec["phase"] in ("done", "expired"):
                rec.update(phase="released", body=None)
            if lose_answer:
                return self.drop()
            return self.reply(200, {"status": "released"})


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True


server = Server(("127.0.0.1", 0), Handler)
with open(port_file + ".tmp", "w") as f:
    f.write(str(server.server_port))
import os

os.replace(port_file + ".tmp", port_file)
server.serve_forever()
