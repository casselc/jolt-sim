# Jolt development gates

Use the `jolt-runtime` skill before running or changing Jolt code in this
repository.

Run at most one local Jolt compiler, test process, or nested-worker campaign at
a time. Preserve every failure directory and transcript; do not delete a gate
root merely because a later rerun passes.

Do not construct an isolated HOME by copying selected entries from `.jolt`.
Git dependency pins change between branches, and a partial copy can make every
fresh exploration worker attempt a network fetch. Keep `JOLT_GITLIBS` pointed
at one complete cache and pre-resolve the exact parent and worker aliases.

For Hegel/process-explorer campaigns, use the checked-in runner rather than
invoking the aliases individually:

```sh
export JOLT_SIM_BIN=/absolute/path/to/the/sim-enabled/jolt
script/run-hegel-gates.sh [hegel-explore-test|http-sqlite-hegel-test|tcp-bencode-hegel-test|outbox-delivery-hegel-test ...]
```

The runner performs dependency preflight before generation, installs the
pinned Hegel native library once, and prints the retained gate root. A
dependency or worker bootstrap error is infrastructure evidence, not a Hegel
counterexample.
