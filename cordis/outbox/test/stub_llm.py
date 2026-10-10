"""A stand-in for an OpenAI-compatible completions server (halo-flash serves
one): it approves a patch once it carries two review notes, counts the
requests it answers, and reports 10 tokens per answer.

    python3 stub_llm.py PORT_FILE COUNT_FILE
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port_file, count_file = sys.argv[1], sys.argv[2]
count = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        global count
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        prompt = body["messages"][-1]["content"]
        context = prompt.split("\n\n")[0]
        text = "LGTM" if prompt.count("\n// ") >= 2 else "needs work (" + context + ")"
        count += 1
        with open(count_file, "w") as f:
            f.write(str(count))
        out = json.dumps({"choices": [{"message": {"role": "assistant", "content": text}}],
                          "usage": {"total_tokens": 10}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


server = HTTPServer(("127.0.0.1", 0), Handler)
with open(port_file + ".tmp", "w") as f:
    f.write(str(server.server_port))
import os
os.replace(port_file + ".tmp", port_file)
server.serve_forever()
