# Outbox workbench

This workbench lets you inspect the canonical outbox application through
Ripple. The application handles a JSON command, stores it in SQLite, delivers
it through TCP/bencode, and marks the row delivered only after a matching
acknowledgement.

It runs the existing application code. The workbench does not replace its HTTP,
database, network, codec, or delivery rules.

> **Screenshot gap:** the repository has no committed capture of this real
> workbench. Capture the eval-only or retained-worker flow before adding an
> image here. Do not use a mock or a hand-drawn substitute.

```text
JSON command -> HTTP -> SQLite outbox -> TCP/bencode receiver -> acknowledgement
                            |                                    |
                            +-------- pending -> delivered -------+
```

## Start with the simple workbench

Run this from this directory. Use an eval-capable Jolt image and keep the token
private.

```sh
cd examples/outbox-workbench
JOLT_SIM_VIEWER_TOKEN='use-at-least-32-private-characters' \
  /absolute/path/to/eval-capable-jolt -M:workbench
```

Open the printed URL, enter the token, and use the REPL pane. The default
configuration is eval-only. It can inspect documents and evaluate forms, but it
cannot start or replay a fresh worker.

Run these forms in order:

```clojure
(require 'jolt.sim.fixtures.outbox-json-delivery)

(def run
  (jolt.sim.fixtures.outbox-json-delivery/exercise-outbox-json-delivery))

[(get-in run [:http :status])
 (get-in run [:application :store-state :outbox 0 :status])
 (get-in run [:application :delivery :replies 0 "type"])]
```

The last form returns:

```clojure
[201 :delivered "outbox_delivery_ok"]
```

This is one complete run. It does not pause during a database operation or
search schedules.

## Inspect the honest pause boundary

The live lifecycle keeps the real servers and SQLite connection open. It gives
you one explicit boundary: after the durable commit and before delivery.

```clojure
(require '[jolt.sim.fixtures.outbox-json-delivery-live :as live]
         '[jolt.sim.fixtures.outbox-json-delivery :as fixture])

(def app (live/start!))
(live/submit-command! app fixture/default-command) ; row is :pending
(live/snapshot! app)
(live/deliver-next! app)                           ; row becomes :delivered
(live/snapshot! app)
(live/stop! app)
```

Use this mode to inspect a committed pending row or to test one explicit
delivery. A bad acknowledgement leaves the row pending.

## Use a retained worker or the flow view

Use these modes when you need the application in a separate process. Set
`JOLT_SIM_BIN` to a sim-enabled child image and `JOLT_SIM_PROJECT_DIR` to the
absolute path of this jolt-sim checkout. The parent image must support
evaluation.

| Mode | Command | Use it when |
| --- | --- | --- |
| Retained worker | `-M:retained-workbench` | You want explicit inspect, submit, deliver, reconcile, and stop commands. |
| Flow view | `-M:flow-ripple` | You want Ripple and a REPL to share one revision-scoped submit/deliver flow. |

For either mode, start from this directory with the required environment:

```sh
JOLT_SIM_VIEWER_TOKEN='use-at-least-32-private-characters' \
JOLT_SIM_BIN='/absolute/path/to/sim-enabled-jolt' \
JOLT_SIM_PROJECT_DIR='/absolute/path/to/jolt-sim' \
  /absolute/path/to/eval-capable-jolt -M:retained-workbench
```

Replace `-M:retained-workbench` with `-M:flow-ripple` for the flow view. Both
modes retain their process artifacts. If publication is uncertain, reconcile
the recorded command; do not automatically send it again.

## Limits and verification

The simple workbench runs real loopback HTTP, SQLite, and TCP/bencode. It is
not a general scheduler, a rewind debugger, or a crash-safe journal. The
focused aliases are `:eval-flow-test`, `:live-lifecycle-test`,
`:retained-workbench-test`, and `:flow-ripple-test`.

Read [the detailed workbench notes](../../docs/research/OUTBOX-WORKBENCH-DETAILS.md)
for exact test commands, retained artifact rules, and the process boundary.
