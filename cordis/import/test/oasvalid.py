#!/usr/bin/env python3
"""openapi-core as a line-oriented validator for the OpenAPI importer's
agreement test (docs/cordis/rfc/0003-types-importers-scaffolds.md,
revision 4, "Testing").

    oasvalid.py DOCUMENT

Each input line is a request, {"request": R}, or a response to one,
{"request": R, "response": {"status": n, "body": text, "media": m}}, where
R is {"method", "target" (path and query, as sent), "headers": [[k, v]],
"body": text or null, "media": m or null}; the target is relative to the
document's first server. Each output line is {"valid": bool, "errors":
[...]}, and for a valid request with a body, "body": the body as
openapi-core read it (a binary part as text).
"""
import json
import sys
from urllib.parse import urlsplit, parse_qsl, unquote

import yaml
from openapi_core import OpenAPI
from openapi_core.testing import MockRequest, MockResponse

doc_path = sys.argv[1]
with open(doc_path) as f:
    doc = yaml.safe_load(f)
api = OpenAPI.from_dict(doc)
server = (doc.get("servers") or [{"url": "/"}])[0]
url = server["url"]
for name, var in (server.get("variables") or {}).items():
    url = url.replace("{" + name + "}", str(var.get("default", "")))
parts = urlsplit(url)
host = f"{parts.scheme}://{parts.netloc}" if parts.netloc else "http://localhost"
base = parts.path.rstrip("/")


def mock_request(r):
    path, _, query = r["target"].partition("?")
    headers = {k: v for k, v in r["headers"]}
    cookies = {}
    if "Cookie" in headers:
        for part in headers.pop("Cookie").split(";"):
            k, _, v = part.strip().partition("=")
            cookies[k] = unquote(v)
    body = r.get("body")
    return MockRequest(
        host,
        r["method"].lower(),
        base + path,
        args=parse_qsl(query, keep_blank_values=True),
        headers=headers,
        cookies=cookies,
        data=body.encode() if body is not None else None,
        content_type=r.get("media") or "application/json",
    )


for line in sys.stdin:
    q = json.loads(line)
    try:
        req = mock_request(q["request"])
        if "response" in q:
            p = q["response"]
            resp = MockResponse(p["body"].encode(), status_code=p["status"], content_type=p.get("media") or "application/json")
            result = api.unmarshal_response(req, resp)
        else:
            result = api.unmarshal_request(req)
        errors = [repr(e) for e in result.errors]
        body = None if "response" in q or errors else result.body
    except Exception as e:  # a request the validator cannot even route
        errors = [repr(e)]
        body = None
    out = {"valid": not errors, "errors": errors}
    if body is not None:
        out["body"] = body
    print(json.dumps(out, default=lambda b: b.decode("utf-8", "replace") if isinstance(b, bytes) else str(b)), flush=True)
