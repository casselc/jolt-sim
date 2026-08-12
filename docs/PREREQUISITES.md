# Prerequisites

Select the Jolt capability and Chez version before you run a workbench or test
lane. These are independent choices.

## Select a Jolt image

The current interactive workbenches require a maintainer-built development
Jolt image. They are not yet installable or runnable from an ordinary public
Jolt release alone. The official
[Jolt v0.7.3 release](https://github.com/jolt-lang/jolt/releases/tag/v0.7.3)
is suitable for an ordinary local Jolt installation, but it does not contain
the fork-only simulation controller ABI pinned by this repository. This
repository does not yet provide a supported workbench installer or a
public-binary setup path.

The guides use two capability names:

| Capability | What it must do | Typical role |
| --- | --- | --- |
| Eval-capable Jolt | Run Ripple's persistent evaluation session and project launcher. | Parent process that serves Ripple and the REPL. |
| Sim-enabled Jolt | Provide the current jolt-sim controller hooks needed by a controlled scenario or worker. | Replay worker, simulation case, or retained child selected by `JOLT_SIM_BIN`. |

The current parent is a development image with persistent evaluation support.
The retained child or replay worker also needs the current simulation
controller hooks. One maintainer-built image can provide both capabilities;
local development can also use separate eval-capable and sim-enabled images.
The two paths in the examples make that split explicit.

Do not infer that an installed or ordinary Jolt image is sim-enabled because
it can start a document viewer. A document-only Ripple process does not by
itself prove that exact replay, evaluation, or a retained child is available.

Use the image required by the selected alias and launcher. Keep the parent and
child on the dependency and controller contract recorded by the checkout. The
authoritative Jolt core revision is `JOLT_CORE_SHA` in
[the CI workflow](../.github/workflows/ci.yml); the checkout's dependency
aliases define the matching consumer graph. Check those files instead of
copying a version or commit from prose. The public v0.7.3 release and the
simulation-enabled development image serve different roles; installing the
former does not replace the latter. This guide does not provide an unverified
development-image build recipe.

## Use Chez Scheme 10.4.1

This project requires Chez Scheme 10.4.1 for local Jolt source builds, compiler
gates, and scripts. Do not let an unqualified system `scheme`, `chez`, or
`chezscheme` command select Chez 10.5.x.

The portable requirement is the exact Chez version and a fail-closed command
environment. The absolute wrapper path shown in this repository's examples:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
```

is local to the maintainer's `/home/chuck/ai-src` workspace. It is not a
general installation path.

In that workspace, run a preflight with no child command:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
```

Then place the command after the wrapper:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  /absolute/path/to/jolt -M:alias
```

For another workspace, provide an equivalent wrapper at a local path. It must
set `CHEZ`, `CHEZSCHEME`, `JOLT_CHEZ`, and `JOLT_CHEZ_CSV` to the pinned
interpreter and fail unless that interpreter reports exactly `10.4.1`. Replace
the workspace-local path in the examples with that wrapper. Do not replace it
with an unqualified interpreter command.

The wrapper preflight selects the compiler. It does not prove that the chosen
Jolt executable has the eval or simulation capability required by a specific
launcher.

## Workbench environment

The retained examples normally require:

- `JOLT_SIM_VIEWER_TOKEN`: at least 32 private characters;
- `JOLT_SIM_BIN`: the child image required by the selected worker alias;
- `JOLT_SIM_PROJECT_DIR`: the absolute path of this checkout; and
- an eval-capable parent executable after the Chez wrapper.

Some launchers accept an explicit artifact directory or `port 0`. Read the
example guide before adding those settings.

Do not publish the capability token. When evaluation is enabled, it grants
code execution in the Ripple parent process even though the listener is
loopback-only.
