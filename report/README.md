# jolt-sim static reports

This optional dependency root renders validated jolt-sim trace and
Case/Outcome documents as self-contained, byte-deterministic HTML. It is
separate from the simulator kernel so scheduler-only applications do not
resolve Selmer or its Jolt host-support dependencies.

A trace report describes one cooperative trace and any already-computed
monitor decisions supplied with it. A Case/Outcome report describes one
ordinary-runtime case, its closed outcome, and the monitor decisions recorded
by the parent harness. Neither renderer runs monitor functions or infers
whether a surrounding Hegel campaign was proved, bounded-complete, sampled,
or assumption-backed; those claims must already be represented by the owning
artifact and test lane.

Trace events are projected through `jolt.sim.presentation/default-registry`
before rendering. The built-ins give scheduler choices, task transitions,
virtual-time advances, and each terminal event a specialized, data-only kind,
summary, and ordered fields; the complete event EDN remains available behind
each row. The same projection is suitable for REPL/tap consumers and future
Ripple frontends—it contains no Selmer or HTML values.

Trace reports also include stable event anchors, first/previous/next/last
links, terminal/failure/monitor shortcuts, and indexes by tag, task, canonical
site, and virtual time. These controls use ordinary fragment links, so they
continue to work in static files and inside Ripple's script-disabled report
frame. Site grouping retains the collision-free canonical trace value while
showing its restored readable form.

Libraries and applications can add trusted presenters without changing trace
documents or mutating global dispatch. Compose immutable registries in
default, library, application order and pass the resulting overrides to the
report API:

```clojure
(require '[jolt.sim.presentation :as presentation]
         '[jolt.sim.report :as report])

(def application-presenters
  {(presentation/site-key :orders.worker/retry)
   {:kind :orders.kind/retry
    :present (fn [event]
               {:summary "Order delivery will retry"
                :fields [{:label "Task" :value (nth event 3)}]})}})

(report/trace->html
 trace-document
 {:presentation-registry application-presenters})
```

For task transitions, a site presenter wins over an operation presenter,
which wins over the ordinary `:task/transition` presenter. `site-key`
canonicalizes both keyword and structured sites, so libraries do not depend
on host map ordering or mutable identity. Across
registries, later entries win, so `(presentation/registry defaults library
application)` has explicit application-over-library-over-default precedence.
Other events dispatch by their exact event tag. Presenters receive a validated
event and return only `{:summary string :fields [{:label string :value data}]}`;
the shared projection validates the stable data domain and derives canonical
EDN text itself. For byte-identical forensic output, custom presenters must
also be deterministic and must not consult clocks, randomness, or mutable
state; function purity cannot be inferred at runtime. An absent entry uses a raw-event fallback. Uploaded documents
can only select functions already present in trusted process configuration;
they cannot name vars, load namespaces, evaluate forms, or supply HTML.

Case reports render the exact validated Case coordinate, every top-level
completed-result section, and, for the canonical outbox scenario family, an
ordered evidence journey through the fields that were actually recorded.
Missing fields remain missing and present `nil` remains visible. The journey
is presentation only: it neither infers causal edges nor re-executes HTTP,
SQLite, TCP, monitors, or simulator controllers.

The core `jolt.sim.repl/replay-document!` helper is the execution handoff. It
keeps retained scenario/input/schedule coordinates authoritative and accepts
ambient worker command, project directory, deadline, environment, and
retention settings separately. Case mode remains validated provenance because
the current fresh-process supervisor does not expose a mode override.

During development from this checkout:

```sh
cd report
jolt -M:test
jolt -M:trace-report INPUT.edn [OUTPUT.html]
jolt -M:case-report CASE-OUTCOME.edn [OUTPUT.html]
```

The [`examples`](examples/) directory contains a small cooperative scheduler
trace and a retained whole-application HTTP/SQLite/outbox retry Case/Outcome,
each with the source EDN, generated self-contained HTML, and a viewport
preview. Its README records exact regeneration and byte-determinism checks.

Downstream applications should select this repository with
`:deps/root "report"`. Use this report coordinate as the application's sole
jolt-sim coordinate. If an application must also declare the core root
directly, both declarations must select the exact same reviewed repository
SHA; mixing SHAs can admit duplicate `jolt.sim.*` source roots. Both HTML
templates are read during analysis and compiled into `jolt.sim.report`; a
standalone image does not need dependency source or resource files at runtime.

For example:

```clojure
{io.github.casselc/jolt-sim-report
 {:git/url "https://github.com/casselc/jolt-sim.git"
  :git/sha "<reviewed commit>"
  :deps/root "report"}}
```

`test/consumer` is a standalone downstream build fixture. Its acceptance gate
builds the application, makes the dependency templates unavailable, and then
requires the binary to render complete trace and Case/Outcome reports from the
compiled templates.
Set `JOLT_BIN` to the exact compiler under review and run
`test/downstream-build-smoke.sh`; every run keeps its build and execution logs
under `../target/report-consumer-smoke` for restart-safe failure analysis.
