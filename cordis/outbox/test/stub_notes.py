#!/usr/bin/env python3
"""A stand-in for the Notes API of notes.yaml, for openapi.sh.

    stub_notes.py PORT_FILE STATS_FILE DROP [ignore-key]

It writes the port it listens on to PORT_FILE. A create needs the bearer
token "secret" and an Idempotency-Key, and is applied once per key: a
create with a key it has seen answers the stored note and applies nothing
(the deduplication the keyed class relies on). The first DROP creates are
applied and then the connection is closed with no answer, a lost receipt.
After every request STATS_FILE holds {"applied": n, "creates": m, "reads":
r, "unauthorised": u}. With ignore-key it applies every create, as a server
that does not keep its idempotency promise would: the planted fault
observe.qnt's keyIgnored predicts.
"""
import json
import socketserver
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port_file, stats_file, drop = sys.argv[1], sys.argv[2], int(sys.argv[3])
ignore_key = len(sys.argv) > 4 and sys.argv[4] == "ignore-key"
notes, by_key = {}, {}
stats = {"applied": 0, "creates": 0, "reads": 0, "unauthorised": 0}


def save_stats():
    with open(stats_file, "w") as f:
        json.dump(stats, f)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorised(self):
        if self.headers.get("Authorization") != "Bearer secret":
            stats["unauthorised"] += 1
            save_stats()
            self.reply(401, {"error": "unauthorised"})
            return False
        return True

    def do_POST(self):
        if self.path != "/notes" or not self.authorised():
            return
        stats["creates"] += 1
        key = self.headers.get("Idempotency-Key")
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if key in by_key and not ignore_key:
            note = by_key[key]
        else:
            note = {"id": f"n{len(notes) + 1}", "text": body["text"]}
            notes[note["id"]] = note
            by_key[key] = note
            stats["applied"] += 1
        save_stats()
        global drop
        if drop > 0:
            drop -= 1
            self.close_connection = True
            self.connection.shutdown(2)
            return
        self.reply(201, note)

    def do_GET(self):
        if not self.authorised():
            return
        stats["reads"] += 1
        save_stats()
        note = notes.get(self.path.rsplit("/", 1)[-1])
        if note is None:
            self.reply(404)
        else:
            self.reply(200, note)


server = HTTPServer(("127.0.0.1", 0), Handler)
save_stats()
with open(port_file, "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
