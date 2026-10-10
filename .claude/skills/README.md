# Agent skills

Vendored, unmodified, from their upstream repositories; refresh by copying
the same directories again at a newer commit.

| Skill | Upstream | Commit | License |
| --- | --- | --- | --- |
| `hegel`, `hegel-review` | [hegeldev/hegel-skill](https://github.com/hegeldev/hegel-skill) `skills/` | `a60b28243199b24aeebb2c90aece34082ee4997c` | MIT |
| `quint-lang`, `quint-modeling`, `quint-execute-spec` | [quint-co/quint-llm-kit](https://github.com/quint-co/quint-llm-kit) `quint-llm-kit-plugin/skills/` | `cc75369f741af7d490936f82002c2d28e3b3d78d` | Apache-2.0 |

Notes for this repository:

- **Quint here runs through `quintgo`** (`cordis/quint/check.sh`, the model
  gate: design instances, mutants and witnesses). The skills' `quint`
  commands (`typecheck`, `run`, `test`, `verify`) need the Quint CLI
  (`npm i -g @informalsystems/quint`); the gate does not.
- **Hegel here is the OCaml library** (`hegel` on opam). Swarm testing is
  built into its stateful tests (`Hegel.Stateful`); for plain generators
  the cordis tests apply it themselves, enabling a random subset of
  features per test case.
