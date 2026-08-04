# Static report examples

These files are committed outputs of the same validated report pipeline used
by the command-line tools. They are review fixtures, not hand-written HTML and
not a second evidence schema.

| Example | Source document | Generated report | Preview | What it shows |
| --- | --- | --- | --- | --- |
| Cooperative countdown | [`cooperative-countdown-trace.edn`](cooperative-countdown-trace.edn) | [`cooperative-countdown-trace.html`](cooperative-countdown-trace.html) | [`cooperative-countdown-trace.png`](cooperative-countdown-trace.png) | A real scheduler-kernel trace with three task transitions and a completed terminal event. |
| HTTP/SQLite outbox retry | [`outbox-retry-case-outcome.edn`](outbox-retry-case-outcome.edn) | [`outbox-retry-case-outcome.html`](outbox-retry-case-outcome.html) | [`outbox-retry-case-outcome.png`](outbox-retry-case-outcome.png) | A real exported Hegel boundary Case/Outcome for the unchanged HTTP-to-SQLite-to-TCP/bencode application. Its first receive is reset before marking, the pending row survives, attempt 2's correlated acknowledgement gates one guarded `pending` to `delivered` transition, all 27 SQLite plans are consumed, and the recorded invariant monitor passes. |

The Case/Outcome file is deliberately richer than a synthetic presentation
fixture: it retains application, HTTP, receiver, SQLite, POSIX-capacity, fault,
route, scheduling, pre-mark pending-state, exact marking, and final delivered
state evidence from one hermetic whole-application run. It is still only one
retained witness; viewing it does not strengthen the owning test lane's proof
or coverage claims.

## Regenerate and verify

Run from this `report` dependency root with the reviewed `jolt` compiler:

```sh
jolt -M:trace-report \
  examples/cooperative-countdown-trace.edn \
  examples/cooperative-countdown-trace.html
jolt -M:case-report \
  examples/outbox-retry-case-outcome.edn \
  examples/outbox-retry-case-outcome.html
jolt -M:test
sha256sum examples/*.edn examples/*.html examples/*.png
```

The focused test renders both source documents again through
`trace->html`/`case-outcome->html` and requires byte equality with the
committed HTML. It also requires the whole-application source to retain the
current ack-gated witness shape: pending before marking, exact delivered-row
evidence afterward, a consumed 27-plan SQLite transcript, and the passing
retry monitor. The reports contain no wall-clock generation time or host
metadata, so a renderer change appears as an ordinary reviewable diff.

The PNGs are viewport previews captured from the generated self-contained
pages, not separately designed mockups. On WSL with the installed Windows Edge:

```sh
edge="/mnt/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
for name in cooperative-countdown-trace outbox-retry-case-outcome; do
  "$edge" --headless=new --disable-gpu --hide-scrollbars \
    --window-size=1440,900 --force-device-scale-factor=1 \
    --screenshot="$(wslpath -w "$PWD/examples/$name.png")" \
    "file:$(wslpath -m "$PWD/examples/$name.html")"
done
```

Screenshot pixels can vary with the installed browser and fonts; the HTML
byte-equality gate is the deterministic source of truth.
