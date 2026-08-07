#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jolt_bin="${JOLT_BIN:-${JOLT_SIM_BIN:-}}"
project_dir="${JOLT_SIM_PROJECT_DIR:-$repo_dir}"
gate_parent="${JOLT_SIM_GATE_PARENT:-${TMPDIR:-/tmp}}"

if [[ -z "$jolt_bin" || "$jolt_bin" != /* || ! -x "$jolt_bin" ]]; then
  echo "JOLT_BIN or JOLT_SIM_BIN must name an absolute executable" >&2
  exit 2
fi
if [[ ! -f "$project_dir/deps.edn" ]]; then
  echo "JOLT_SIM_PROJECT_DIR must contain deps.edn: $project_dir" >&2
  exit 2
fi
if [[ "$gate_parent" != /* ]]; then
  echo "JOLT_SIM_GATE_PARENT/TMPDIR must be absolute: $gate_parent" >&2
  exit 2
fi

mkdir -p "$gate_parent"
gate_root="$(mktemp -d "$gate_parent/jolt-sim-maelstrom-json-lines.XXXXXX")"
mkdir -p "$gate_root/logs"
transcript="$gate_root/logs/gate.log"
exec > >(tee -a "$transcript") 2>&1
echo "retained gate root: $gate_root"

original_home="${HOME:-}"
if [[ -n "${JOLT_GITLIBS:-}" ]]; then
  gitlibs_dir="$JOLT_GITLIBS"
elif [[ -n "$original_home" ]]; then
  gitlibs_dir="$original_home/.jolt/gitlibs"
else
  gitlibs_dir="$gate_root/gitlibs"
fi
if [[ "$gitlibs_dir" != /* ]]; then
  echo "JOLT_GITLIBS must be absolute: $gitlibs_dir" >&2
  exit 2
fi

export HOME="$gate_root/home"
export XDG_CACHE_HOME="$gate_root/xdg-cache"
export JOLT_CACHE_DIR="$gate_root/aot-cache"
export JOLT_GITLIBS="$gitlibs_dir"
export JOLT_AOT_CACHE=0
export JOLT_NO_USER_DEPS=1
export TMPDIR="$gate_root/tmp"
mkdir -p "$HOME" "$XDG_CACHE_HOME" "$JOLT_CACHE_DIR" "$JOLT_GITLIBS" "$TMPDIR"

cd "$project_dir"
echo "[dependency-preflight] maelstrom-echo"
"$jolt_bin" -P -M:maelstrom-echo
echo "[dependency-preflight] maelstrom-echo-process-e2e-verify"
"$jolt_bin" -P -M:maelstrom-echo-process-e2e-verify

run_case() {
  local case_name="$1"
  local case_dir="$gate_root/$case_name"
  mkdir -p "$case_dir"
  echo "[process-case] $case_name"
  set +e
  "$jolt_bin" -M:maelstrom-echo \
    < "$case_dir/request.jsonl" \
    > "$case_dir/stdout.jsonl" \
    2> "$case_dir/stderr.log"
  local status=$?
  set -e
  printf '%s\n' "$status" > "$case_dir/status"
}

mkdir -p "$gate_root/success" "$gate_root/malformed"
printf '%s\n' \
  '{"src":"c1","dest":"n1","body":{"type":"init","msg_id":1,"node_id":"n1","node_ids":["n1"]}}' \
  '{"src":"c1","dest":"n1","body":{"type":"echo","msg_id":2,"echo":{"greeting":"héllo, 世界 🌍","false":false,"empty":[],"null":null,"nested":{"values":[0,255,"🚀"]}}}}' \
  > "$gate_root/success/request.jsonl"
printf '%s\n' \
  '{"src":"c1","dest":"n1","body":{"type":"init","msg_id":1,"node_id":"n1","node_ids":["n1"]}}' \
  > "$gate_root/malformed/request.jsonl"
# Retain the complete hostile input as forensic evidence while requiring the
# child diagnostic itself to stay bounded. The open JSON string is malformed
# only at EOF, after the parser has consumed more than the diagnostic prefix.
printf '{"src":"%04096d\n' 0 >> "$gate_root/malformed/request.jsonl"

run_case success
run_case malformed

echo "[verify] retained JSON-lines wire evidence"
"$jolt_bin" -M:maelstrom-echo-process-e2e-verify "$gate_root"
echo "Maelstrom Echo JSON-lines process gate passed"
echo "retained gate root: $gate_root"
