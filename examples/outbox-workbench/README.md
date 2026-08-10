# Outbox workbench: Ripple as the REPL for the canonical outbox application

This is an isolated, eval-only Ripple workbench for the **unchanged canonical
outbox application**: the ordinary routed-JSON HTTP facade in
`jolt.example.outbox.http-json` over the durable SQLite adapter in
`jolt.example.outbox.sqlite`, delivered through the ordinary framed
TCP/bencode path — exactly the whole-application flow the root repository's
`:outbox-json-delivery-test` alias proves. Nothing here reimplements or
patches application, HTTP, database, networking, codec, or evaluation logic;
`deps.edn` only composes `../../viewer` (Ripple), adds `../../test` as a
source path solely so the existing `jolt.sim.fixtures.outbox-json-delivery`
fixture namespace resolves, and pins the remaining exact dependencies of that
alias (the complete `io.github.casselc/db` coordinate with its native SQLite
declaration, plus bencode/bytes/router/reitit/meta-merge).

Launching this workbench gives you Ripple's persistent Jolt REPL (`--eval`)
with the canonical fixture already loaded, so the browser pane can run one
real HTTP → SQLite → TCP/bencode delivery by evaluating ordinary forms.

The workbench also statically loads the app-owned live lifecycle. Unlike the
original one-shot witness, it keeps the real SQLite connection and real HTTP
and TCP listeners alive across REPL evaluations, so COMMIT and delivery are
separate explicit operations.

## Launch

From this directory (`examples/outbox-workbench`), with a capability token of
at least 32 characters and the sim-capable `jolt` executable:

```sh
cd examples/outbox-workbench
JOLT_SIM_VIEWER_TOKEN='replace-with-at-least-32-random-characters' \
  /absolute/path/to/jolt -M:workbench
```

The token is always read from `JOLT_SIM_VIEWER_TOKEN`, never from the config
file. The checked-in [`config/ripple-eval.edn`](config/ripple-eval.edn) is
eval-only (no `:allowed-scenarios`/`:runtime-config` replay pair, so
`/api/run`, `/api/replay`, `/api/replay-progress`, and `/api/run-presets`
fail closed as unavailable) and listens on the default port **8788**. To use a
different config — for example `:port 0` for an ephemeral loopback port — pass
its path:

```sh
JOLT_SIM_VIEWER_TOKEN='replace-with-at-least-32-random-characters' \
  /absolute/path/to/jolt -M:workbench /path/to/alternate-config.edn
```

The startup line prints the actual URL (`Ripple: http://127.0.0.1:8788`).
Open it in a browser, submit the token once, and use the REPL pane. Ctrl+C in
the foreground process stops the listener cleanly.

## The four workbench forms

Submit these forms through the REPL pane (or any loopback `/api/eval` client
carrying the capability token), once each, in order:

```clojure
(require 'jolt.sim.fixtures.outbox-json-delivery)
```

```clojure
(def canonical-run (jolt.sim.fixtures.outbox-json-delivery/exercise-outbox-json-delivery))
```

```clojure
[(get-in canonical-run [:http :status])
 (get-in canonical-run [:application :store-state :outbox 0 :status])
 (get-in canonical-run [:application :delivery :replies 0 "type"])]
```

```clojure
*1
```

The second form runs the whole unchanged application once: one real JSON HTTP
command (201) committed to a real in-memory SQLite outbox, one framed
TCP/bencode delivery to a real loopback receiver, the exact correlated
`outbox_delivery_ok` acknowledgement, and the ack-gated durable
`mark-delivered!` with its final reload. The third form projects exactly

```clojure
[201 :delivered "outbox_delivery_ok"]
```

— HTTP 201, the durable outbox row `:delivered`, ack type
`outbox_delivery_ok` — and the fourth recalls it through `*1`.

## What this is not

The original canonical witness remains **one-shot**: each
`exercise-outbox-json-delivery` evaluation runs the complete HTTP → SQLite →
TCP/bencode flow to completion inside a single `/api/eval` request and
returns its immutable evidence. The live lifecycle below adds one honest
pause boundary—after durable COMMIT and before delivery—without pretending it
can suspend an arbitrary native call or transaction mid-instruction. It also
does not add simulation scheduling, rewind, or fresh-worker interruption;
those remain separate jolt-sim controller responsibilities.

The capability token grants arbitrary code execution in the workbench
process. Keep it private even though the listener is loopback-only.

## Live post-COMMIT inspection and explicit delivery

The live lifecycle is UI-neutral; the same forms work in Ripple, nREPL, or an
ordinary persistent Jolt evaluation session:

```clojure
(require '[jolt.sim.fixtures.outbox-json-delivery-live :as live]
         '[jolt.sim.fixtures.outbox-json-delivery :as fixture])
(def app (live/start!))
(live/submit-command! app fixture/default-command) ; real HTTP, returns 201
(live/snapshot! app)                               ; durable row is :pending
(live/deliver-next! app)                           ; real TCP/bencode + ack
(live/snapshot! app)                               ; durable row is :delivered
(live/stop! app)                                   ; true, then false
```

`datafy` returns a bounded immutable summary. `nav` expands retained
submission, delivery, receiver-request, and error observations without
exposing connections, threads, native handles, or mutable storage. The exact
durable `:store-state` remains directly inspectable. SQLite access is
single-owner serialized; client-side HTTP I/O does not hold the SQLite lock,
while `deliver-next!` holds it from the pending reload through acknowledgement
validation and the durable delivered mark. A mismatched acknowledgement fails
before marking and leaves the row pending.

## Focused fresh-process test

`:eval-flow-test` is a self-contained gate: in one fresh process it starts an
eval-only Ripple server programmatically on port 0 over one real
`EvalSession`, submits exactly the four forms above through the real loopback
`/api/eval` endpoint with the capability token, and verifies monotonic
sequence receipts `0..3`, the exact `[201 :delivered "outbox_delivery_ok"]`
projection on the wire and in the session's retained raw envelopes, `*1`
recall, empty HTTP/receiver server errors, and idempotent `[true false]`
connection closes on both the HTTP command connection and the TCP delivery
connection. It always stops Ripple and closes the session after the completed
evaluations, bounds the whole scenario with a 180-second watchdog, appends
start/finish/timeout/error breadcrumbs to a retained progress file, and exits
with `System/exit` because core.async threads linger.

```sh
cd examples/outbox-workbench
/absolute/path/to/jolt -M:eval-flow-test
```

The progress file defaults to
`$TMPDIR/jolt-sim-outbox-workbench-<uuid>.edn` and can be pinned with
`JOLT_SIM_OUTBOX_WORKBENCH_PROGRESS_FILE`. This gate was developed and run
with the prebuilt evaluator
`/home/chuck/ai-src/worktrees/jolt-eval-engine-v064-deepseek/target/debug/jolt`
(jolt v0.6.4-21-g79c926aa-dirty); no Jolt compilation is involved or
required.

`:live-lifecycle-test` is the focused gate for the persistent form. It checks
empty start, the real HTTP post-COMMIT boundary, exact replay and conflict
non-mutation, explicit real TCP delivery, hostile-ack fail-closed behavior,
datafy/nav, idempotent stop, post-stop rejection, and the same workflow through
a persistent EvalSession:

```sh
cd examples/outbox-workbench
/absolute/path/to/jolt -M:live-lifecycle-test
```

Its append-only progress path can be pinned with
`JOLT_SIM_OUTBOX_LIVE_PROGRESS_FILE`; failures and timeouts are never deleted.
