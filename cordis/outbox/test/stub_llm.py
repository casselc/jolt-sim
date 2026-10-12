"""A stand-in for an OpenAI-compatible completions server (halo-flash serves
one): it approves a patch once it carries two review notes, counts the
requests it answers, and reports 10 tokens per answer. It streams
(server-sent events) when asked to, as the outbox does.

    python3 stub_llm.py PORT_FILE COUNT_FILE [MODE...]

Modes, for the real-latency test (what the halo-flash run found):
  delay=S     wait S seconds before answering (longer than the runner's
              --timeout: a slow call is not a lost one), streaming nothing
              until then
  long        a failing answer is long and multi-line (over the 500 the
              notes are bounded to)
  length      every answer stops for its length: finish_reason "length"
  no-usage    no usage reported (the outbox must charge the worst case)
  refuse      answer HTTP 400 (a definite failure: nothing to retry)
  keys        keep idempotency keys, as halo-flash's scheduler does: a request
              whose Idempotency-Key has a finished answer is answered from
              it, generating nothing (COUNT_FILE.generated counts what was
              generated); every answer says where it came from, as
              halo-flash's does: Halo-Answer: generated or replayed
  drop-first  generate the first answer, keep it under its key, and close the
              connection without sending it: a lost answer
  forget-first  as drop-first, but keep nothing (a server past its grace
              window, which stopped the generation): the resend generates
              afresh
  report-discarded  with forget-first, every answer under a key with a
              forgotten generation says how many tokens were discarded
              under it (Halo-Discarded-Tokens: 4, the stopped part), a
              replay too
  drop-second  generate the second answer, keep it under its key, and close
              the connection without sending it (with forget-first: the
              third send is a replay of the second generation)
  report-zero  with report-discarded, the count goes on every keyed answer, 0
              too (as halo-flash 87cc596 sends it)
  forget-key  with drop-first: once the first answer is dropped, the server
              forgets the key (halo-flash past replay_ttl_s, or evicted): its
              kept answer, its discarded count and the sends it admitted
  report-admitted  every keyed answer says how many sends the server admitted
              under the key since it last forgot it (Halo-Admitted-Sends)
  approve     approve every patch
  verdict-last  approve as a model that reasons first does: the reasons,
              then LGTM on the last line
  fenced      a writer's revised patch comes in code fences
  busy-first=S  answer the first request HTTP 429 with Retry-After: S
              (COUNT_FILE.times holds each request's arrival time)
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

port_file, count_file = sys.argv[1], sys.argv[2]
# what each request said of thinking (chat_template_kwargs.enable_thinking), one line per request
thinking_file = count_file + ".thinking"
modes = sys.argv[3:]
delay = next((float(m.split("=", 1)[1]) for m in modes if m.startswith("delay=")), 0.0)
count = 0
generated = 0
kept = {}  # idempotency key -> the answer (text, finish, usage)
dropped = False
forgot = False
discarded = {}  # idempotency key -> tokens decoded by generations stopped under it
admitted = {}  # idempotency key -> sends admitted under it since the server last forgot it


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        global count
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        count += 1
        with open(count_file, "w") as f:
            f.write(str(count))
        with open(count_file + ".times", "a") as f:
            f.write("%f\n" % time.time())
        busy = next((m.split("=", 1)[1] for m in modes if m.startswith("busy-first=")), None)
        if busy is not None and count == 1:
            out = b'{"error": "busy"}'
            self.send_response(429)
            self.send_header("Retry-After", busy)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(out)
            return
        thinks = body.get("chat_template_kwargs", {}).get("enable_thinking", True)
        with open(count_file + ".temperature", "a") as f:
            f.write(json.dumps(body.get("temperature")) + "\n")
        with open(thinking_file, "a") as f:
            f.write(json.dumps(body.get("chat_template_kwargs", {}).get("enable_thinking")) + "\n")
        if "refuse" in modes:
            out = b'{"error": "bad request"}'
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(out)
            return
        prompt = body["messages"][-1]["content"]
        parts = prompt.split("\n\n")
        # the file's context is the part before the patch
        context = parts[-2] if len(parts) >= 2 else parts[0]
        writer = prompt.startswith("Apply the review notes")
        approve = (prompt.count("\n// ") >= 2 or "(fixed)" in prompt or "approve" in modes) and "length" not in modes
        if writer:
            # review_fix.cg's writer: [instruction, patch, notes]; with notes, the patch fixed
            _, patch, notes = (prompt.split("\n\n", 2) + ["", ""])[:3]
            text = patch if not notes.strip() else patch + "\n(fixed)"
            if "fenced" in modes:
                text = "```ocaml\n" + text + "\n```"
        elif approve and "verdict-last" in modes:
            text = "The patch is correct and complete as it stands.\n\nLGTM"
        elif approve:
            text = "LGTM"
        elif "long" in modes:
            text = "needs work (" + context + ")\n" + "\n".join("line %d of a long review: " % i + "x" * 60 for i in range(12))
        else:
            text = "needs work (" + context + ")"
        finish = "length" if "length" in modes else "stop"
        # a model that thinks (by default) reports what it spent reasoning
        usage = None if "no-usage" in modes else {"total_tokens": 10, "completion_tokens_details": {"reasoning_tokens": 7 if thinks else 0}}
        global generated, dropped, forgot
        key = self.headers.get("Idempotency-Key")
        answer = None
        if "keys" in modes and key is not None:
            admitted[key] = admitted.get(key, 0) + 1
        if "keys" in modes and key in kept:
            text, finish, usage = kept[key]
            answer = "replayed"
        else:
            generated += 1
            with open(count_file + ".generated", "w") as f:
                f.write(str(generated))
            if key is not None and not ("forget-first" in modes and not forgot):
                kept[key] = (text, finish, usage)
            answer = "generated"
            if delay:
                time.sleep(delay)
        if "forget-first" in modes and not forgot:
            forgot = True
            if key is not None:
                discarded[key] = discarded.get(key, 0) + 4
            self.close_connection = True
            self.connection.shutdown(2)
            return
        answer = [("Halo-Answer", answer)] if "keys" in modes else []
        if "report-discarded" in modes and answer and (discarded.get(key) or "report-zero" in modes):
            answer.append(("Halo-Discarded-Tokens", str(discarded.get(key, 0))))
        if "report-admitted" in modes and answer:
            answer.append(("Halo-Admitted-Sends", str(admitted.get(key, 0))))
        if ("drop-first" in modes and not dropped) or ("drop-second" in modes and count == 2):
            dropped = True
            if "forget-key" in modes and key is not None:
                kept.pop(key, None)
                discarded.pop(key, None)
                admitted.pop(key, None)
            self.close_connection = True
            self.connection.shutdown(2)
            return
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            for h in answer:
                self.send_header(*h)
            self.send_header("Connection", "close")
            self.end_headers()
            for line in text.splitlines(keepends=True):
                chunk = {"choices": [{"index": 0, "delta": {"content": line}, "finish_reason": None}]}
                self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
            self.wfile.write(("data: " + json.dumps({"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]}) + "\n\n").encode())
            if usage:
                self.wfile.write(("data: " + json.dumps({"choices": [], "usage": usage}) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.close_connection = True
            return
        reply = {"choices": [{"message": {"role": "assistant", "content": text}, "finish_reason": finish}]}
        if usage:
            reply["usage"] = usage
        out = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        for h in answer:
            self.send_header(*h)
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(out)


class Server(ThreadingMixIn, HTTPServer):
    daemon_threads = True


server = Server(("127.0.0.1", 0), Handler)
with open(port_file + ".tmp", "w") as f:
    f.write(str(server.server_port))
import os
os.replace(port_file + ".tmp", port_file)
server.serve_forever()
