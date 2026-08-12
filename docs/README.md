# jolt-sim documentation

Start with the guide that matches your task.

| Task | Guide |
| --- | --- |
| Understand the project | [Project README](../README.md) |
| Inspect evidence, use a REPL, or attach a worker | [Ripple](../viewer/README.md) |
| Debug durable Outbox delivery | [Outbox workbench](../examples/outbox-workbench/README.md) |
| Direct a Broadcast partition and retry | [Broadcast workbench](../examples/maelstrom-broadcast-workbench/README.md) |
| Create an offline HTML view | [Static reports](../report/README.md) |
| Select a Jolt image and Chez version | [Prerequisites](PREREQUISITES.md) |
| Find exact gates and implementation limits | [Implementation and verification](reference/IMPLEMENTATION-AND-VERIFICATION.md) |
| Trace a checked screenshot to its real browser spec | [Capture manifest](CAPTURE-MANIFEST.md) |
| Read the former long capability record | [Archived implementation snapshot](../ARCHIVED-IMPLEMENTATION-SNAPSHOT.md) |
| Review planned work | [Roadmap](ROADMAP.md) |

## Design and research

These documents explore options or record proof scope. They are not quick
start guides and do not promise a stable public API.

- [Experiment workbench design](research/EXPERIMENT-WORKBENCH-DESIGN.md)
- [Ripple and REPL debugger integration](research/RIPPLE-REPL-DEBUGGER-INTEGRATION.md)
- [Mycelium flow gaps](research/MYCELIUM-FLOW-V1-GAPS.md)
- [Archived Ripple README snapshot](../viewer/ARCHIVED-README-SNAPSHOT.md)
- [Archived Outbox README snapshot](../examples/outbox-workbench/ARCHIVED-README-SNAPSHOT.md)
- [Archived Broadcast README snapshot](../examples/maelstrom-broadcast-workbench/ARCHIVED-README-SNAPSHOT.md)
- [Outbox terminal boundary invariants](proofs/outbox-terminal-boundary-invariants.md)
- [SQLite begin recovery proof](proofs/sqlite-begin-recovery.md)
- [Virtual clock alarm invariants](proofs/virtual-clock-alarm-invariants.md)

Archived README snapshots preserve history and can contain stale paths or
version claims. The code, focused test lanes, and retained artifacts are the
evidence for current behavior. A research note can describe a future design
that does not exist yet.
