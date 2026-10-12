"""A stand-in for halo-flash's operation API (docs/cordis/rfc/0007-boundaries.md;
halo-flash main c4a39ff, src/server/operation_service.cpp and operations.cpp):
the verbs, the incarnation, the monotonic clock, tombstones, a capture window,
a records ceiling, and faults a test can switch on. It answers the review loop's
completions as stub_llm.py does.

    python3 stub_ops.py PORT_FILE COUNT_FILE [MODE...]

The wire is the one halo serves:
  GET  /v1/halo/contract
  POST /v1/operations   {operation_id, attempt, not_after, api, request, mode}
                        needs Halo-Incarnation; Halo-Digest, if given, must be the SHA-256 (hex) of the body
                        bytes, which is the attempt's digest; the owner is X-Halo-Principal
  GET  /v1/operations/{op}/{attempt}[/result|/stream?from=N]
  POST /v1/operations/{op}/{attempt}/cancel | /ack
                        every other verb carries Halo-Incarnation, Halo-Not-After and Halo-Digest
All answers carry Halo-Incarnation and Halo-Time. A status is a view:
  {operation_id, attempt, status, digest, [cancel_requested], [finish, usage], [expires_at]}
with usage {prompt_tokens, cached_tokens, completion_tokens, engine_ms}. Errors are {"error": {message, type}}.

Modes (startup):
  delay=S          a submit runs for S seconds before it finishes
  capture=S        the capture window (default 3600)
  records=N        the records ceiling (default 100000)
  budget=N         the retention budget, bytes (default 256 MiB); a submit reserves 1000 + max_tokens x 4
  inference=exact  the server runs exact inference (default relaxed)
  approve          approve every patch
  no-usage         no usage reported (zero tokens)

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
import re
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
CAPTURE_MS = int(mode("capture", 3600.0) * 1000)
MAX_RECORDS = int(mode("records", 100000))
BUDGET = int(mode("budget", 256 * 1024 * 1024))
MAX_SUBMIT_AGE_MS = 600 * 1000
RETRY_AFTER_S = 1
OURS = "exact" if "inference=exact" in modes else "relaxed"
OP_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

lock = threading.RLock()
faults = {}
state = {}
requests = 0


def restart():
    with lock:
        state.clear()
        state.update(incarnation=hashlib.sha256(str(time.time_ns()).encode()).hexdigest()[:32], t0=time.monotonic(),
                     recs={}, generated=0, reserved=0)


restart()


def now_ms():
    return int((time.monotonic() - state["t0"]) * 1000)


def generate(req):
    """The stand-in's answer to a chat completion, as stub_llm.py's."""
    prompt = req["messages"][-1]["content"]
    parts = prompt.split("\n\n")
    context = parts[-2] if len(parts) >= 2 else parts[0]
    writer = prompt.startswith("Apply the review notes")
    approve = prompt.count("\n// ") >= 2 or "(fixed)" in prompt or "approve" in modes
    if writer:
        _, patch, notes = (prompt.split("\n\n", 2) + ["", ""])[:3]
        return (patch if not notes.strip() else patch + "\n(fixed)"), "stop"
    return ("LGTM" if approve else "needs work (" + context + ")"), "stop"


def start_run(key, rec):
    """Execute the attempt: it finishes after DELAY, unless cancelled first."""

    def run():
        end = time.monotonic() + DELAY
        while time.monotonic() < end and not rec["cancel_requested"]:
            time.sleep(0.01)
        with lock:
            if state["recs"].get(key) is not rec or rec["st"] != "running":
                return
            text, finish = generate(rec["request"])
            prompt_tokens = max(1, len(rec["request"]["messages"][-1]["content"]) // 4)
            completion = 0 if "no-usage" in modes else 10
            if rec["cancel_requested"]:
                finish, text, completion = "cancelled", text[:2], min(completion, 1)
            rec.update(st="done", finish=finish, done_at=now_ms(),
                       usage={"prompt_tokens": prompt_tokens, "cached_tokens": 0, "completion_tokens": completion, "engine_ms": 0.0},
                       body=json.dumps({"choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": finish}],
                                        "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion}}))

    threading.Thread(target=run, daemon=True).start()


def sweep(now):
    """The capture window ends: the body goes, the outcome stays until it is acknowledged. A record goes once its
    not_after has passed and, if it ran, its outcome was acknowledged."""
    for key, r in list(state["recs"].items()):
        if r["st"] == "done" and now > r["done_at"] + CAPTURE_MS:
            release(r)
            r.update(st="expired", acked=False)
        settled = r["st"] in ("released", "cancelled") or (r["st"] == "expired" and r["acked"])
        if settled and now > r["not_after"]:
            state["recs"].pop(key)


def release(r):
    state["reserved"] -= r["reserve"]
    r["reserve"] = 0
    r["body"] = None


def view(op, attempt, digest, r, now, absent_not_after=None):
    v = {"operation_id": op, "attempt": attempt, "digest": digest}
    if r is None:
        v["status"] = "closed" if now > absent_not_after else "never_accepted"
        return v
    st = r["st"]
    v["status"] = {"running": "running", "done": "done", "released": "released", "expired": "expired",
                   "cancelled": "cancelled_before_accept"}[st]
    if st == "running":
        v["cancel_requested"] = r["cancel_requested"]
    if st in ("done", "released", "expired"):
        v["finish"], v["usage"] = r["finish"], r["usage"]
    if st == "done":
        v["expires_at"] = r["done_at"] + CAPTURE_MS
    return v


def error(status, type_, message):
    return status, {"error": {"message": message, "type": type_}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, code, obj, extra=(), raw=None):
        out = raw if raw is not None else json.dumps(obj).encode()
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

    def reply_pair(self, pair, extra=()):
        return self.reply(pair[0], pair[1], extra)

    def drop(self):
        self.close_connection = True
        self.connection.shutdown(2)

    def principal(self):
        return self.headers.get("X-Halo-Principal", "")

    def raw_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    # ---- GET ----
    def do_GET(self):
        with lock:
            now = now_ms()
            sweep(now)
            path = self.path.split("?")[0]
            if path == "/admin/state":
                return self.reply(200, {"incarnation": state["incarnation"], "records": len(state["recs"]),
                                        "generated": state["generated"], "reserved": state["reserved"],
                                        "unacked_outcomes": sum(1 for r in state["recs"].values() if r["st"] in ("done", "expired") and not r.get("acked")),
                                        "attempts": {"/".join(map(str, k)): v["st"] for k, v in state["recs"].items()}})
            if path == "/v1/halo/contract":
                return self.reply(200, self.contract(now))
            if path.startswith("/v1/operations/"):
                return self.verb("GET", path)
            return self.reply_pair(error(404, "invalid_request_error", "not found"))

    def contract(self, now):
        unacked = [r for r in state["recs"].values() if r["st"] in ("done", "expired") and not r.get("acked")]
        oldest = max([now - r["done_at"] for r in unacked], default=0)
        return {"incarnation": state["incarnation"], "time_ms": now,
                "inference": {"mode": OURS, "cost": "tokens: prompt, cached, completion",
                              "repeat": {"external_effects": "none", "spend": "a new generation spends again"}},
                "operations": {
                    "apis": ["completions", "chat"], "owner": "X-Halo-Principal", "digest": "sha256 (hex) of the submit body",
                    "retention": {"capture_s": CAPTURE_MS // 1000, "until": "ack or capture window", "budget_bytes": BUDGET,
                                  "reserve": "at acceptance: 1000 + max_tokens x 4 bytes", "reserved_bytes": state["reserved"]},
                    "records": {"max": MAX_RECORDS, "in_use": len(state["recs"]), "unacked_outcomes": len(unacked), "oldest_unacked_ms": oldest},
                    "dedup": {"scope": "owner, incarnation", "max_submit_age_s": MAX_SUBMIT_AGE_MS // 1000},
                    "cancel": "next unit boundary (<= one decode unit or one prefill slice); usage reported",
                    "disconnect": "never cancels"}}

    # ---- POST ----
    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/admin/restart":
            restart()
            return self.reply(200, {"incarnation": state["incarnation"]})
        if path == "/admin/fault":
            body = json.loads(self.raw_body() or b"{}")
            with lock:
                faults.update(body)
            return self.reply(200, faults)
        if path == "/v1/operations":
            return self.submit()
        if path.startswith("/v1/operations/"):
            with lock:
                self.raw_body()
                sweep(now_ms())
                return self.verb("POST", path)
        return self.reply_pair(error(404, "invalid_request_error", "not found"))

    def submit(self):
        global requests
        raw = self.raw_body()
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
            sweep(now_ms())
            pair, extra = self.process_submit(raw)
            if dup:
                self.process_submit(raw)
            if lose_answer:
                return self.drop()
            return self.reply_pair(pair, extra)

    def process_submit(self, raw):
        inc = self.headers.get("Halo-Incarnation")
        if not inc:
            return error(400, "invalid_request_error", "a submit names its incarnation (Halo-Incarnation)"), ()
        try:
            body = json.loads(raw)
        except ValueError:
            body = None
        if not isinstance(body, dict):
            return error(400, "invalid_request_error", "the body is not a JSON object"), ()
        op, attempt, not_after = body.get("operation_id"), body.get("attempt"), body.get("not_after")
        if not isinstance(op, str) or not OP_ID.match(op):
            return error(400, "invalid_request_error", "operation_id: 1-128 of [A-Za-z0-9._:-]"), ()
        if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 0:
            return error(400, "invalid_request_error", "attempt: a non-negative integer"), ()
        if not isinstance(not_after, int) or isinstance(not_after, bool):
            return error(400, "invalid_request_error", "not_after: an integer (ms on the incarnation's clock)"), ()
        if body.get("api") not in ("completions", "chat"):
            return error(400, "invalid_request_error", 'api: "completions" or "chat"'), ()
        if not isinstance(body.get("request"), dict):
            return error(400, "invalid_request_error", "request: an object"), ()
        if body.get("mode") not in ("relaxed", "exact"):
            return error(400, "invalid_request_error", 'mode: "relaxed" or "exact"'), ()
        if body["mode"] != OURS:
            return error(422, "mode_unavailable", f"this server runs {OURS} inference (see GET /v1/halo/contract)"), ()
        digest = hashlib.sha256(raw).hexdigest()
        if self.headers.get("Halo-Digest") not in (None, digest):
            return error(400, "invalid_request_error", "Halo-Digest is not the SHA-256 of the body"), ()
        key, now = (self.principal(), op, attempt), now_ms()
        if inc != state["incarnation"]:
            return (409, {"error": {"message": f"meant for incarnation {inc}, this is {state['incarnation']}", "type": "stale_incarnation"},
                          "incarnation": state["incarnation"]}), ()
        rec = state["recs"].get(key)
        if rec is not None:
            if rec["digest"] != digest:
                return error(409, "conflict", "the attempt is bound to another request (digest)"), ()
            return (200, view(op, attempt, digest, rec, now)), ()
        if now > not_after:
            return error(410, "late", "past its not_after"), ()
        if not_after > now + MAX_SUBMIT_AGE_MS:
            return error(400, "invalid_request_error", "not_after is too far ahead"), ()
        retry = (("Retry-After", str(RETRY_AFTER_S)),)
        if len(state["recs"]) >= MAX_RECORDS:
            return error(503, "records", "at the record ceiling (unacknowledged outcomes and tombstones); not accepted"), retry
        reserve = 1000 + int(body["request"].get("max_tokens", 16)) * 4
        if state["reserved"] + reserve > BUDGET:
            return error(503, "retention", "the retention budget is full; not accepted"), retry
        rec = {"st": "running", "digest": digest, "not_after": not_after, "request": body["request"], "cancel_requested": False,
               "reserve": reserve, "body": None, "finish": None, "usage": None, "done_at": 0, "acked": False}
        state["recs"][key] = rec
        state["reserved"] += reserve
        state["generated"] += 1
        start_run(key, rec)
        return (202, view(op, attempt, digest, rec, now)), ()

    def verb(self, method, path):
        parts = path[len("/v1/operations/"):].split("/")
        if len(parts) < 2 or len(parts) > 3 or not OP_ID.match(parts[0]) or not parts[1].isdigit():
            return self.reply_pair(error(404, "invalid_request_error", f"no operation route for {path}"))
        op, attempt, what = parts[0], int(parts[1]), (parts[2] if len(parts) == 3 else "")
        if (method == "POST" and what not in ("cancel", "ack")) or (method == "GET" and what not in ("", "result", "stream")):
            return self.reply_pair(error(405, "invalid_request_error", "method not allowed"))
        inc, nb, digest = (self.headers.get(h) for h in ("Halo-Incarnation", "Halo-Not-After", "Halo-Digest"))
        if not inc or not nb or not digest or not re.match(r"^-?\d+$", nb):
            return self.reply_pair(error(400, "invalid_request_error", "every verb carries Halo-Incarnation, Halo-Not-After and Halo-Digest"))
        not_after, now = int(nb), now_ms()
        key = (self.principal(), op, attempt)
        if what == "cancel" and faults.pop("lose_cancel", None):
            return self.drop()
        if inc != state["incarnation"]:
            if what == "":
                return self.reply(200, {"operation_id": op, "attempt": attempt, "digest": digest, "status": "unknown"})
            return self.reply(409, {"error": {"message": f"meant for incarnation {inc}, this is {state['incarnation']}", "type": "stale_incarnation"},
                                    "incarnation": state["incarnation"]})
        rec = state["recs"].get(key)
        if rec is not None and rec["digest"] != digest:
            return self.reply_pair(error(409, "conflict", "the attempt is bound to another request (digest)"))
        if what == "result":
            if rec is not None and rec["st"] == "done":
                return self.reply(200, None, raw=rec["body"].encode())
            return self.reply(409, view(op, attempt, digest, rec, now, not_after))
        if what == "stream":
            if rec is None or rec["st"] not in ("running", "done"):
                return self.reply(410, view(op, attempt, digest, rec, now, not_after))
            return self.reply(200, None, raw=(": events\n\nevent: status\ndata: " + json.dumps(view(op, attempt, digest, rec, now)) + "\n\n").encode())
        if what == "cancel":
            if rec is not None:
                if rec["st"] == "running":
                    rec["cancel_requested"] = True  # finishes at the next unit boundary, with its actual usage
                return self.reply(200, view(op, attempt, digest, rec, now))
            if now > not_after:
                return self.reply(200, view(op, attempt, digest, None, now, not_after))  # already closed: nothing to fence
            if not_after > now + MAX_SUBMIT_AGE_MS:
                return self.reply_pair(error(400, "invalid_request_error", "not_after is too far ahead"))
            if len(state["recs"]) >= MAX_RECORDS:
                return self.reply_pair(error(503, "records", "at the record ceiling; not accepted"), (("Retry-After", str(RETRY_AFTER_S)),))
            rec = {"st": "cancelled", "digest": digest, "not_after": not_after, "request": None, "cancel_requested": True, "reserve": 0,
                   "body": None, "finish": None, "usage": None, "done_at": 0, "acked": False}
            state["recs"][key] = rec
            return self.reply(200, view(op, attempt, digest, rec, now))
        if what == "ack":
            lose = faults.pop("lose_ack_answer", None)
            if rec is not None:
                if rec["st"] == "done":
                    release(rec)
                    rec.update(st="released", acked=True)
                elif rec["st"] == "expired":
                    rec["acked"] = True
            if lose:
                return self.drop()
            return self.reply(200, view(op, attempt, digest, rec, now, not_after))
        return self.reply(200, view(op, attempt, digest, rec, now, not_after))


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True


server = Server(("127.0.0.1", 0), Handler)
with open(port_file + ".tmp", "w") as f:
    f.write(str(server.server_port))
import os

os.replace(port_file + ".tmp", port_file)
server.serve_forever()
