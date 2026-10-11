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
              generated)
  drop-first  generate the first answer, keep it under its key, and close the
              connection without sending it: a lost answer
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
        approve = prompt.count("\n// ") >= 2 and "length" not in modes
        if approve:
            text = "LGTM"
        elif "long" in modes:
            text = "needs work (" + context + ")\n" + "\n".join("line %d of a long review: " % i + "x" * 60 for i in range(12))
        else:
            text = "needs work (" + context + ")"
        finish = "length" if "length" in modes else "stop"
        # a model that thinks (by default) reports what it spent reasoning
        usage = None if "no-usage" in modes else {"total_tokens": 10, "completion_tokens_details": {"reasoning_tokens": 7 if thinks else 0}}
        global generated, dropped
        key = self.headers.get("Idempotency-Key")
        if "keys" in modes and key in kept:
            text, finish, usage = kept[key]
        else:
            generated += 1
            with open(count_file + ".generated", "w") as f:
                f.write(str(generated))
            if key is not None:
                kept[key] = (text, finish, usage)
            if delay:
                time.sleep(delay)
        if "drop-first" in modes and not dropped:
            dropped = True
            self.close_connection = True
            self.connection.shutdown(2)
            return
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
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
