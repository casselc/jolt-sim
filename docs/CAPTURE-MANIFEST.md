# Documentation capture manifest

The checked screenshots below come from real Playwright stories that install
no route mocks. The test is the behavioral source. The checked image is a
curated documentation asset, not the complete run artifact.

The root README uses compact state diagrams as its main explanation. The
checked captures remain linked from the example guides and this manifest as
evidence. Keep that arrangement until close, consistent real storyboard frames
are generated, reviewed, and promoted.

## Outbox story

Source spec:
[`viewer/test-browser-real/outbox-flow-real.spec.mjs`](../viewer/test-browser-real/outbox-flow-real.spec.mjs)

Playwright config:
[`viewer/playwright.outbox-flow.config.mjs`](../viewer/playwright.outbox-flow.config.mjs)

| Checked asset | Generated artifact basename | State shown |
| --- | --- | --- |
| [`ripple-outbox-command-cell-pending.png`](../examples/outbox-workbench/docs/ripple-outbox-command-cell-pending.png) | `ripple-real-outbox-command-cell-pending.png` | Submit committed with HTTP 201, durable row pending, and receiver request count zero. |
| [`ripple-outbox-command-cell-delivered.png`](../examples/outbox-workbench/docs/ripple-outbox-command-cell-delivered.png) | `ripple-real-outbox-command-cell-delivered.png` | Deliver committed, row delivered, and receiver request count one. |

The real run writes under `viewer/target/ripple-playwright/outbox-flow`. The
checked assets are promoted copies of the named test outputs.

## Broadcast story

Source spec:
[`viewer/test-browser-real/broadcast-retained-real.spec.mjs`](../viewer/test-browser-real/broadcast-retained-real.spec.mjs)

Playwright config:
[`viewer/playwright.broadcast-retained.config.mjs`](../viewer/playwright.broadcast-retained.config.mjs)

| Checked asset | Generated artifact basename | State shown |
| --- | --- | --- |
| [`ripple-broadcast-command-cell-prepared.png`](../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-command-cell-prepared.png) | `ripple-real-broadcast-command-cell-prepared.png` | Bootstrap prepared; the retained child has not consumed sequence 1. |
| [`ripple-broadcast-command-cell-running.png`](../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-command-cell-running.png) | `ripple-real-broadcast-command-cell-running.png` | Bootstrap committed and the child reports the running phase. |
| [`ripple-broadcast-topology.png`](../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-topology.png) | `ripple-real-broadcast-partition.png` | Curated topology crop from the partitioned run: `n1` and `n2` contain `42`, `n3` does not. |
| [`ripple-broadcast-edge-actions.png`](../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-edge-actions.png) | `ripple-real-broadcast-edge-actions.png` | Curated `n2--n3` edge detail with the partition state and restore action. |

The real run writes under `viewer/target/ripple-playwright/broadcast-retained`.
It also generates `ripple-real-broadcast-converged.png`, but that frame has not
been promoted into the checked documentation.

## Promotion rule

Regenerate and review the documentation assets when the source spec changes
the scenario, expected state, labels, layout, or generated artifact name. Do
not copy an old screenshot forward only because the test still has the same
filename. Record any crop or resize as a curated derivative of the named test
output.

The repository does not yet automate screenshot promotion. A reviewer must
confirm that the visible state matches the assertions near the screenshot call
before replacing a checked asset.

## Current readability limits

- The Command Cell screenshots are tall and contain more surrounding UI than a
  short README story needs. Their small text is hard to read when embedded at
  page width.
- The Broadcast topology and edge images are useful crops, but they do not show
  the whole cause-and-effect sequence on one page.
- The checked Outbox images show the two final states but not the pure prepared
  state before a child command is published.
- The general images under `viewer/docs` are useful context, but their current
  provenance is not tied by artifact name to either real no-mock spec. Do not
  cite them as proof of those application stories.

## Desired storyboard frames

A future capture pass should produce a small, consistent sequence:

1. Outbox Submit prepared, with the child sequence unchanged.
2. Outbox committed and pending, with receiver count zero.
3. Outbox delivered, with receiver count one.
4. Broadcast Bootstrap prepared, then running.
5. Broadcast partitioned, with `n3` missing `42` and one dropped envelope.
6. Broadcast restored but not replayed, before the application retry.
7. Broadcast converged after retry, with all three nodes containing `42`.

Use close crops or a numbered story strip for the README. Keep the full-page
screenshot, trace, video, and child artifacts in the Playwright output for
forensic review.
