"""Self-test of stub_ops.py: every status answer and fault of the operation
contract (docs/cordis/rfc/0007-boundaries.md), on the wire halo-flash serves
(main c4a39ff), so the adapter is tested against a server that behaves as that
contract says.

    python3 stub_ops_selftest.py
"""
import hashlib
import http.client
import json
import os
import subprocess
import sys
import tempfile
import threading
import time

here = os.path.dirname(os.path.abspath(__file__))


class Server:
    def __init__(self, *modes):
        self.dir = tempfile.mkdtemp()
        port_file = os.path.join(self.dir, "port")
        self.inc = ""
        self.proc = subprocess.Popen([sys.executable, os.path.join(here, "stub_ops.py"), port_file, os.path.join(self.dir, "count"), *modes],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(200):
                if os.path.exists(port_file):
                    break
                time.sleep(0.02)
            self.port = int(open(port_file).read())
            self.inc = self.call("GET", "/v1/halo/contract")[1]["incarnation"]
        except Exception:
            self.close()
            raise

    def close(self):
        self.proc.kill()
        self.proc.wait()

    def call(self, method, path, body=None, headers=None):
        """body: bytes, sent as they are. Returns (status, json, headers); None for a lost connection."""
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"Content-Type": "application/json", "Halo-Incarnation": self.inc}
        h.update(headers or {})
        try:
            c.request(method, path, body, h)
            r = c.getresponse()
            data = r.read()
            try:
                obj = json.loads(data) if data else {}
            except ValueError:
                obj = data.decode()
            return r.status, obj, dict(r.getheaders())
        except (ConnectionError, http.client.RemoteDisconnected):
            return None, None, None
        finally:
            c.close()

    def fault(self, **kw):
        self.call("POST", "/admin/fault", json.dumps(kw).encode())

    def state(self):
        return self.call("GET", "/admin/state")[1]

    def now(self):
        return int(self.call("GET", "/v1/halo/contract")[2]["Halo-Time"])


REQ = {"messages": [{"role": "user", "content": "review this patch\n\ncontext\n\npatch"}], "max_tokens": 16}


class Attempt:
    """An attempt as the outbox holds it: the exact bytes of its submit, and so its digest."""

    def __init__(self, s, op, attempt=1, ahead=60000, mode="relaxed", request=REQ, principal="p1", not_after=None):
        self.s, self.op, self.attempt, self.principal = s, op, attempt, principal
        self.na = s.now() + ahead if not_after is None else not_after
        self.body = json.dumps({"operation_id": op, "attempt": attempt, "not_after": self.na, "api": "chat", "request": request, "mode": mode}).encode()
        self.digest = hashlib.sha256(self.body).hexdigest()

    def carried(self, inc=None):
        return {"Halo-Incarnation": inc or self.s.inc, "Halo-Not-After": str(self.na), "Halo-Digest": self.digest, "X-Halo-Principal": self.principal}

    def submit(self, headers=None, inc=None):
        h = {"Halo-Incarnation": inc or self.s.inc, "X-Halo-Principal": self.principal}
        h.update(headers or {})
        return self.s.call("POST", "/v1/operations", self.body, h)

    def path(self, what=""):
        return f"/v1/operations/{self.op}/{self.attempt}" + (f"/{what}" if what else "")

    def status(self, inc=None):
        return self.s.call("GET", self.path(), None, self.carried(inc))

    def verb(self, what, method="POST", inc=None):
        return self.s.call(method, self.path(what), b"" if method == "POST" else None, self.carried(inc))

    def wait(self, want="done"):
        for _ in range(300):
            st = self.status()[1]
            if st.get("status") == want:
                return st
            time.sleep(0.02)
        raise AssertionError(f"{self.op} never reached {want}: {st}")


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def t_contract(s):
    code, c, _ = s.call("GET", "/v1/halo/contract")
    check(code == 200 and len(c["incarnation"]) == 32 and c["time_ms"] >= 0, c)
    check(c["inference"]["mode"] in ("relaxed", "exact") and c["operations"]["records"]["max"] > 0, c)
    check(c["operations"]["owner"] == "X-Halo-Principal" and c["operations"]["disconnect"] == "never cancels", c)
    # every answer names the incarnation and the clock, an error answer too
    code, _, h = s.call("GET", "/nowhere")
    check(code == 404 and h["Halo-Incarnation"] == c["incarnation"] and int(h["Halo-Time"]) >= c["time_ms"], (code, h))


def t_lifecycle(s):
    a = Attempt(s, "life")
    code, v, _ = a.submit()
    check(code == 202 and v["status"] == "running" and v["digest"] == a.digest and v["operation_id"] == "life" and v["attempt"] == 1, (code, v))
    st = a.wait()
    check(st["usage"]["completion_tokens"] == 10 and set(st["usage"]) == {"prompt_tokens", "cached_tokens", "completion_tokens", "engine_ms"}, st)
    check(st["finish"] == "stop" and st["expires_at"] > 0, st)
    code, body, _ = a.verb("result", "GET")
    check(code == 200 and body["choices"][0]["message"]["content"], (code, body))
    code, v, _ = a.verb("ack")
    check(code == 200 and v["status"] == "released", (code, v))
    check(a.status()[1]["status"] == "released", "released after ack")
    code, v, _ = a.verb("result", "GET")
    check(code == 409 and v["status"] == "released", ("no body after ack: the status, with a 409", code, v))


def t_never_accepted_and_closed(s):
    a = Attempt(s, "ghost")
    check(a.status()[1]["status"] == "never_accepted", "inside the window")
    b = Attempt(s, "ghost2", not_after=s.now() + 1)
    time.sleep(0.05)
    check(b.status()[1]["status"] == "closed", "past the carried deadline, no record: closed")


def t_duplicate_conflict_digest(s):
    a = Attempt(s, "dup")
    a.submit()
    a.wait()
    code, v, _ = a.submit()
    check(code == 200 and v["status"] == "done", ("a duplicate gets the current status", code, v))
    check(s.state()["generated"] == 1, "a duplicate runs nothing")
    other = Attempt(s, "dup", not_after=a.na, request=dict(REQ, max_tokens=17))
    code, v, _ = other.submit()
    check(code == 409 and v["error"]["type"] == "conflict", ("another body for the same attempt is a conflict", code, v))
    code, v, _ = a.submit(headers={"Halo-Digest": "0" * 64})
    check(code == 400 and "Halo-Digest" in v["error"]["message"], ("a digest that is not the body's", code, v))
    check(a.submit(headers={"Halo-Digest": a.digest})[0] == 200, "a matching digest header is allowed")
    check(other.verb("cancel")[0] == 409, "a verb naming another digest than the attempt's is a conflict")


def t_mode_unavailable(s):
    code, v, _ = Attempt(s, "mode", mode="exact").submit()
    check(code == 422 and v["error"]["type"] == "mode_unavailable", (code, v))


def t_incarnation(s):
    a = Attempt(s, "inc")
    a.submit()
    a.wait()
    stale = "0" * 32
    check(a.status(stale)[1]["status"] == "unknown", "another incarnation: unknown, never never_accepted")
    code, v, _ = Attempt(s, "inc2").submit(inc=stale)
    check(code == 409 and v["error"]["type"] == "stale_incarnation" and v["incarnation"] == s.inc, (code, v))
    check(a.verb("cancel", inc=stale)[0] == 409, "stale cancel")
    check(a.verb("ack", inc=stale)[0] == 409, "stale ack")
    check(a.verb("result", "GET", inc=stale)[0] == 409, "stale fetch")
    code, v, _ = s.call("GET", a.path(), None, {"Halo-Incarnation": s.inc})
    check(code == 400, "every verb carries the deadline and the digest")


def t_restart(s):
    a = Attempt(s, "rs")
    a.submit()
    a.wait()
    old = s.inc
    s.call("POST", "/admin/restart")
    new = s.call("GET", "/v1/halo/contract")[1]["incarnation"]
    check(new != old, "a new incarnation")
    check(a.status(old)[1]["status"] == "unknown", "an old query after a restart is unknown")
    s.inc = new
    check(s.state()["records"] == 0, "everything held is gone")


def t_cancel_before_accept(s):
    a = Attempt(s, "c1")
    code, v, _ = a.verb("cancel")
    check(code == 200 and v["status"] == "cancelled_before_accept", (code, v))
    # a late copy of the submit finds the tombstone: the current status, and nothing runs
    code, v, _ = a.submit()
    check(code == 200 and v["status"] == "cancelled_before_accept" and s.state()["generated"] == 0, ("the late copy runs nothing", code, v))
    other = Attempt(s, "c1", not_after=a.na, request=dict(REQ, max_tokens=17))
    check(other.submit()[0] == 409, "a copy naming another body is a conflict")


def t_cancel_running(s):
    a = Attempt(s, "c2")
    a.submit()
    code, v, _ = a.verb("cancel")
    check(code == 200 and v["status"] == "running" and v["cancel_requested"] is True, ("a cancel is requested, the status stays running", code, v))
    st = a.wait()
    check(st["finish"] == "cancelled" and st["usage"]["completion_tokens"] >= 1, ("a cancelled attempt reports its usage", st))


def t_past_not_after(s):
    code, v, _ = Attempt(s, "late", not_after=s.now() - 1).submit()
    check(code == 410 and v["error"]["type"] == "late", (code, v))
    code, v, _ = Attempt(s, "far", ahead=700000).submit()
    check(code == 400, ("not_after too far ahead", code, v))


def t_lost_submit(s):
    s.fault(lose_submit=True)
    a = Attempt(s, "lost")
    check(a.submit()[0] is None, "the submit was lost")
    check(a.status()[1]["status"] == "never_accepted", "nothing recorded: not accepted yet")
    code, v, _ = a.submit()
    check(code == 202, ("resent exactly as first sent", code, v))


def t_lost_submit_answer(s):
    s.fault(lose_submit_answer=True)
    a = Attempt(s, "lsa")
    check(a.submit()[0] is None, "the answer was lost")
    check(a.wait()["status"] == "done", "the attempt was accepted and ran")


def t_dup_submit(s):
    s.fault(dup_submit=True)
    a = Attempt(s, "dp")
    a.submit()
    a.wait()
    check(s.state()["generated"] == 1, "a duplicated submit runs once")


def t_late_copy_after_cancel(s):
    s.fault(delay_submit=0.3)
    a = Attempt(s, "lc")
    result = {}
    th = threading.Thread(target=lambda: result.update(r=a.submit()))
    th.start()
    time.sleep(0.05)
    check(a.status()[1]["status"] == "never_accepted", "the copy has not arrived")
    check(a.verb("cancel")[1]["status"] == "cancelled_before_accept", "cancelled")
    th.join()
    check(result["r"][1]["status"] == "cancelled_before_accept" and s.state()["generated"] == 0, ("the delayed copy found the tombstone", result))


def t_expiry(s):
    a = Attempt(s, "ex")
    a.submit()
    a.wait()
    time.sleep(1.2)
    st = a.status()[1]
    check(st["status"] == "expired" and st["usage"]["completion_tokens"] == 10, ("the body went, the outcome and usage stay", st))
    check(a.verb("result", "GET")[0] == 409, "no body")
    code, v, _ = a.verb("ack")
    check(code == 200 and v["status"] == "expired", ("an expired outcome is acknowledged, and stays until not_after", code, v))


def t_records_ceiling(s):
    check(Attempt(s, "r1").submit()[0] == 202 and Attempt(s, "r2").submit()[0] == 202, "within the ceiling")
    code, v, h = Attempt(s, "r3").submit()
    check(code == 503 and v["error"]["type"] == "records" and int(h["Retry-After"]) >= 1, ("refused at the ceiling, never erasing", code, v))
    check(s.state()["records"] == 2, "nothing was erased to make room")
    # a cancel is a record too: refused at the ceiling, leaving the outbox to wait for closed
    code, v, _ = Attempt(s, "r4").verb("cancel")
    check(code == 503, ("a cancel at the ceiling is refused", code, v))


def t_retention_budget(s):
    first = Attempt(s, "b1")
    check(first.submit()[0] == 202, "within the budget")
    code, v, h = Attempt(s, "b2").submit()
    check(code == 503 and v["error"]["type"] == "retention" and "Retry-After" in h, ("refused when the reservation would not fit", code, v))
    first.wait()
    first.verb("ack")
    check(Attempt(s, "b3").submit()[0] == 202, "the ack released its reservation")


def t_lost_ack_answer(s):
    a = Attempt(s, "la")
    a.submit()
    a.wait()
    s.fault(lose_ack_answer=True)
    check(a.verb("ack")[0] is None, "the ack's answer was lost")
    check(a.status()[1]["status"] == "released", "but it was applied")
    check(a.verb("ack")[1]["status"] == "released", "a repeated ack is harmless")


def t_owner(s):
    a, b = Attempt(s, "own", principal="p1"), Attempt(s, "own", principal="p2")
    check(a.submit()[0] == 202 and b.submit()[0] == 202, "the same operation and attempt under two owners are two attempts")
    check(s.state()["generated"] == 2, "each ran")


TESTS = [
    (t_contract, []), (t_lifecycle, []), (t_never_accepted_and_closed, []), (t_duplicate_conflict_digest, []), (t_mode_unavailable, []),
    (t_incarnation, []), (t_restart, []), (t_cancel_before_accept, []), (t_cancel_running, ["delay=0.3"]), (t_past_not_after, []),
    (t_lost_submit, []), (t_lost_submit_answer, []), (t_dup_submit, []), (t_late_copy_after_cancel, []), (t_expiry, ["capture=1"]),
    (t_records_ceiling, ["records=2"]), (t_retention_budget, ["budget=1500"]), (t_lost_ack_answer, []), (t_owner, []),
]

failed = 0
for fn, modes in TESTS:
    s = Server(*modes)
    try:
        fn(s)
        print("ok  ", fn.__name__)
    except Exception as e:  # noqa: BLE001
        failed += 1
        print("FAIL", fn.__name__, repr(e))
    finally:
        s.close()
print(f"{len(TESTS) - failed} of {len(TESTS)} passed")
sys.exit(1 if failed else 0)
