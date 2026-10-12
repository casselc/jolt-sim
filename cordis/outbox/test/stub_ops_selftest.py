"""Self-test of stub_ops.py: every status answer and fault of the operation
contract (docs/cordis/rfc/0007-boundaries.md), so the adapter is tested
against a server that behaves as the contract says.

    python3 stub_ops_selftest.py
"""
import hashlib
import http.client
import json
import os
import subprocess
import sys
import tempfile
import time

here = os.path.dirname(os.path.abspath(__file__))


class Server:
    def __init__(self, *modes):
        self.dir = tempfile.mkdtemp()
        port_file = os.path.join(self.dir, "port")
        self.inc = ""
        self.proc = subprocess.Popen([sys.executable, os.path.join(here, "stub_ops.py"), port_file, os.path.join(self.dir, "count"), *modes],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(200):
            if os.path.exists(port_file):
                break
            time.sleep(0.02)
        try:
            self.port = int(open(port_file).read())
            self.inc = self.get("/v1/halo/contract")[1]["incarnation"]
        except Exception:
            self.close()
            raise

    def close(self):
        self.proc.kill()
        self.proc.wait()

    def call(self, method, path, body=None, inc="current", not_after=None, digest=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"Content-Type": "application/json", "Halo-Incarnation": self.inc if inc == "current" else inc}
        if not_after is not None:
            h["Halo-Not-After"] = str(not_after)
        if digest is not None:
            h["Halo-Digest"] = digest
        try:
            c.request(method, path, json.dumps(body) if body is not None else None, h)
            r = c.getresponse()
            data = r.read()
            return r.status, (json.loads(data) if data else {}), dict(r.getheaders())
        except (ConnectionError, http.client.RemoteDisconnected):
            return None, None, None
        finally:
            c.close()

    def get(self, path, **kw):
        s, o, h = self.call("GET", path, **kw)
        return s, o

    def fault(self, **kw):
        self.call("POST", "/admin/fault", kw)

    def state(self):
        return self.get("/admin/state")[1]

    def now(self):
        return int(self.call("GET", "/v1/halo/contract")[2]["Halo-Time"])


REQ = {"messages": [{"role": "user", "content": "review this patch\n\ncontext\n\npatch"}]}


def digest(not_after, mode="relaxed", api="chat"):
    return hashlib.sha256(json.dumps([api, REQ, not_after, mode], sort_keys=True).encode()).hexdigest()


def submit(s, op, attempt=1, ahead=60000, **kw):
    na = s.now() + ahead
    body = {"operation_id": op, "attempt": attempt, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
    code, obj, _ = s.call("POST", "/v1/operations", body, not_after=na, digest=digest(na), **kw)
    return code, obj, na


def status(s, op, na, attempt=1, **kw):
    return s.get(f"/v1/operations/{op}/{attempt}", not_after=na, digest=digest(na), **kw)


def wait_done(s, op, na, attempt=1, want="done"):
    for _ in range(300):
        st = status(s, op, na, attempt)[1]
        if st.get("status") == want:
            return st
        time.sleep(0.02)
    raise AssertionError(f"{op} never reached {want}: {st}")


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def t_contract(s):
    code, c = s.get("/v1/halo/contract")
    check(code == 200 and len(c["incarnation"]) == 32 and c["time_ms"] >= 0 and c["records"]["max"] > 0, c)


def t_lifecycle(s):
    code, obj, na = submit(s, "life")
    check(code == 202 and obj["status"] == "accepted", (code, obj))
    st = wait_done(s, "life", na)
    check(st["usage"]["completion"] == 10 and st["finish"] == "stop" and st["expires_at"] > 0, st)
    code, body = s.get("/v1/operations/life/1/result", not_after=na, digest=digest(na))
    check(code == 200 and body["body"], body)
    code, obj = s.call("POST", "/v1/operations/life/1/ack", not_after=na, digest=digest(na))[:2]
    check(code == 200 and obj["status"] == "released", (code, obj))
    check(status(s, "life", na)[1]["status"] == "released", "released after ack")
    check(s.get("/v1/operations/life/1/result", not_after=na, digest=digest(na))[0] == 410, "no body after ack")


def t_never_accepted_and_closed(s):
    now = s.now()
    check(status(s, "ghost", now + 60000)[1]["status"] == "never_accepted", "inside the window")
    time.sleep(0.05)
    check(status(s, "ghost", now)[1]["status"] == "closed", "past the carried deadline, no record: closed")


def t_duplicate_and_conflict(s):
    code, _, na = submit(s, "dup")
    wait_done(s, "dup", na)
    body = {"operation_id": "dup", "attempt": 1, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
    code, obj, _ = s.call("POST", "/v1/operations", body, not_after=na, digest=digest(na))
    check(code == 200 and obj["status"] == "done", ("a duplicate gets the current status", code, obj))
    check(s.state()["generated"] == 1, "a duplicate runs nothing")
    other = dict(body, mode="exact")
    code, obj, _ = s.call("POST", "/v1/operations", other, not_after=na, digest=digest(na, "exact"))
    check(code == 409 and obj["error"] == "conflict", ("another digest is a conflict", code, obj))


def t_incarnation(s):
    code, _, na = submit(s, "inc")
    wait_done(s, "inc", na)
    check(status(s, "inc", na, inc="0" * 32)[1]["status"] == "unknown", "another incarnation: unknown")
    body = {"operation_id": "inc2", "attempt": 1, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
    code, obj, _ = s.call("POST", "/v1/operations", body, inc="0" * 32, not_after=na, digest=digest(na))
    check(code == 409 and obj["error"] == "stale_incarnation", (code, obj))
    check(s.call("POST", "/v1/operations/inc/1/cancel", inc="0" * 32, not_after=na, digest=digest(na))[0] == 409, "stale cancel")
    check(s.call("POST", "/v1/operations/inc/1/ack", inc="0" * 32, not_after=na, digest=digest(na))[0] == 409, "stale ack")


def t_restart(s):
    code, _, na = submit(s, "rs")
    wait_done(s, "rs", na)
    old = s.inc
    s.call("POST", "/admin/restart")
    new = s.get("/v1/halo/contract")[1]["incarnation"]
    check(new != old, "a new incarnation")
    check(status(s, "rs", na, inc=old)[1]["status"] == "unknown", "an old query after a restart is unknown, never never_accepted")
    s.inc = new
    check(s.state()["records"] == 0, "everything held is gone")


def t_cancel_before_accept(s):
    na = s.now() + 60000
    code, obj = s.call("POST", "/v1/operations/c1/1/cancel", not_after=na, digest=digest(na))[:2]
    check(code == 200 and obj["status"] == "cancelled_before_accept", (code, obj))
    # a late copy of the submit is a no-op: the id is tombstoned
    body = {"operation_id": "c1", "attempt": 1, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
    code, obj, _ = s.call("POST", "/v1/operations", body, not_after=na, digest=digest(na))
    check(obj["status"] == "cancelled_before_accept" and s.state()["generated"] == 0, ("the late copy runs nothing", code, obj))


def t_cancel_running(s):
    code, _, na = submit(s, "c2")
    code, obj = s.call("POST", "/v1/operations/c2/1/cancel", not_after=na, digest=digest(na))[:2]
    check(obj["status"] == "stopping", obj)
    st = wait_done(s, "c2", na)
    check(st["finish"] == "cancelled" and st["usage"]["completion"] >= 1, ("a cancelled attempt reports its usage", st))


def t_past_not_after(s):
    na = s.now() - 1
    body = {"operation_id": "late", "attempt": 1, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
    code, obj, _ = s.call("POST", "/v1/operations", body, not_after=na, digest=digest(na))
    check(code == 410, (code, obj))


def t_lost_submit(s):
    s.fault(lose_submit=True)
    code, _, na = submit(s, "lost")
    check(code is None, "the submit was lost")
    check(status(s, "lost", na)[1]["status"] == "never_accepted", "nothing recorded: not accepted yet")
    code, obj, _ = (lambda: submit(s, "lost"))()  # a resend is a new submit with its own deadline; the platform sends it as first sent
    check(code in (202, 200), (code, obj))


def t_lost_submit_answer(s):
    s.fault(lose_submit_answer=True)
    code, _, na = submit(s, "lsa")
    check(code is None, "the answer was lost")
    st = wait_done(s, "lsa", na)
    check(st["status"] == "done", "the attempt was accepted and ran")


def t_dup_submit(s):
    s.fault(dup_submit=True)
    code, _, na = submit(s, "dp")
    wait_done(s, "dp", na)
    check(s.state()["generated"] == 1, "a duplicated submit runs once")


def t_late_copy_after_cancel(s):
    s.fault(delay_submit=0.3)
    import threading

    result = {}
    na = s.now() + 60000

    def go():
        body = {"operation_id": "lc", "attempt": 1, "not_after": na, "api": "chat", "request": REQ, "mode": "relaxed"}
        result["r"] = s.call("POST", "/v1/operations", body, not_after=na, digest=digest(na))

    th = threading.Thread(target=go)
    th.start()
    time.sleep(0.05)
    check(status(s, "lc", na)[1]["status"] == "never_accepted", "the copy has not arrived")
    check(s.call("POST", "/v1/operations/lc/1/cancel", not_after=na, digest=digest(na))[1]["status"] == "cancelled_before_accept", "cancelled")
    th.join()
    check(result["r"][1]["status"] == "cancelled_before_accept" and s.state()["generated"] == 0, ("the delayed copy found the tombstone", result))


def t_expiry(s):
    code, _, na = submit(s, "ex")
    wait_done(s, "ex", na)
    time.sleep(1.2)
    st = status(s, "ex", na)[1]
    check(st["status"] == "expired" and st["usage"]["completion"] == 10, ("the body went, the outcome and usage stay", st))
    check(s.get("/v1/operations/ex/1/result", not_after=na, digest=digest(na))[0] == 410, "no body")
    code, obj = s.call("POST", "/v1/operations/ex/1/ack", not_after=na, digest=digest(na))[:2]
    check(obj["status"] == "released", "an expired outcome is acknowledged")


def t_records_ceiling(s):
    check(submit(s, "r1")[0] == 202 and submit(s, "r2")[0] == 202, "within the ceiling")
    code, obj, _ = submit(s, "r3")
    check(code == 503 and obj["reason"] == "records", ("refused at the ceiling, never erasing", code, obj))
    check(s.state()["records"] == 2, "nothing was erased to make room")


def t_lost_ack_answer(s):
    code, _, na = submit(s, "la")
    wait_done(s, "la", na)
    s.fault(lose_ack_answer=True)
    check(s.call("POST", "/v1/operations/la/1/ack", not_after=na, digest=digest(na))[0] is None, "the ack's answer was lost")
    check(status(s, "la", na)[1]["status"] == "released", "but it was applied")
    check(s.call("POST", "/v1/operations/la/1/ack", not_after=na, digest=digest(na))[1]["status"] == "released", "a repeated ack is harmless")


TESTS = [
    (t_contract, []), (t_lifecycle, []), (t_never_accepted_and_closed, []), (t_duplicate_and_conflict, []), (t_incarnation, []),
    (t_restart, []), (t_cancel_before_accept, []), (t_cancel_running, ["delay=0.3"]), (t_past_not_after, []), (t_lost_submit, []),
    (t_lost_submit_answer, []), (t_dup_submit, []), (t_late_copy_after_cancel, []), (t_expiry, ["capture=1"]),
    (t_records_ceiling, ["records=2"]), (t_lost_ack_answer, []),
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
