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

During development from this checkout:

```sh
cd report
jolt -M:test
jolt -M:trace-report INPUT.edn [OUTPUT.html]
jolt -M:case-report CASE-OUTCOME.edn [OUTPUT.html]
```

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
