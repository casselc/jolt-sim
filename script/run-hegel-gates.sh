#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sim_bin="${JOLT_SIM_BIN:-}"
project_dir="${JOLT_SIM_PROJECT_DIR:-$repo_dir}"

if [[ -z "$sim_bin" ]]; then
  echo "JOLT_SIM_BIN must name the sim-enabled Jolt executable" >&2
  exit 2
fi
if [[ "$sim_bin" != /* || ! -x "$sim_bin" ]]; then
  echo "JOLT_SIM_BIN must be an absolute executable path: $sim_bin" >&2
  exit 2
fi
if [[ ! -f "$project_dir/deps.edn" ]]; then
  echo "JOLT_SIM_PROJECT_DIR must contain deps.edn: $project_dir" >&2
  exit 2
fi
project_dir="$(cd "$project_dir" && pwd)"

original_home="${HOME:-}"
original_tmp="${TMPDIR:-/tmp}"
gate_parent="${JOLT_SIM_GATE_PARENT:-$original_tmp}"

if [[ "$gate_parent" != /* ]]; then
  echo "JOLT_SIM_GATE_PARENT/TMPDIR must resolve to an absolute path: $gate_parent" >&2
  exit 2
fi
mkdir -p "$gate_parent"
gate_parent="$(cd "$gate_parent" && pwd)"
gate_root="$(mktemp -d "$gate_parent/jolt-sim-hegel.XXXXXX")"

# Once a gate root exists, attach its forensic transcript before any remaining
# caller-supplied path can fail. Directory setup below is a recorded phase.
if ! mkdir -p "$gate_root/logs"; then
  echo "retained gate root: $gate_root" >&2
  exit 2
fi
transcript="$gate_root/logs/hegel-gates.log"
status_file="$gate_root/status.log"
exec > >(tee -a "$transcript") 2>&1

record_status() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$status_file"
}

run_step() {
  local phase="$1"
  local name="$2"
  shift 2
  record_status "$phase" "$name" start
  echo "[$phase] $name"
  if "$@"; then
    record_status "$phase" "$name" pass
  else
    local exit_code=$?
    record_status "$phase" "$name" "fail:$exit_code"
    echo "[$phase] $name failed with exit $exit_code" >&2
    echo "retained gate root: $gate_root" >&2
    exit "$exit_code"
  fi
}

require_absolute_path() {
  local name="$1"
  local value="$2"
  if [[ "$value" != /* ]]; then
    echo "$name must be an absolute path: $value" >&2
    return 2
  fi
}

echo "retained gate root: $gate_root"

# Dependency source state is intentionally separate from the fresh process
# state. Capture the caller's complete Git cache before replacing HOME. In CI
# that cache starts empty and preflight fills it; locally it normally avoids a
# sandboxed network fetch without copying a stale subset of pinned commits.
if [[ -n "${JOLT_GITLIBS:-}" ]]; then
  gitlibs_dir="$JOLT_GITLIBS"
elif [[ -n "$original_home" ]]; then
  gitlibs_dir="$original_home/.jolt/gitlibs"
else
  gitlibs_dir="$gate_root/gitlibs"
fi

case_temp_dir="${JOLT_SIM_CASE_TEMP_DIR:-$gate_root/case-runs}"
case_artifact_dir="${JOLT_SIM_CASE_ARTIFACT_DIR:-$gate_root/case-artifacts}"

run_step environment shared-path-contract require_absolute_path \
  JOLT_GITLIBS "$gitlibs_dir"
run_step environment case-temp-contract require_absolute_path \
  JOLT_SIM_CASE_TEMP_DIR "$case_temp_dir"
run_step environment case-artifact-contract require_absolute_path \
  JOLT_SIM_CASE_ARTIFACT_DIR "$case_artifact_dir"

export HOME="$gate_root/home"
export XDG_CACHE_HOME="$gate_root/xdg-cache"
export JOLT_CACHE_DIR="$gate_root/aot-cache"
export JOLT_GITLIBS="$gitlibs_dir"
export JOLT_AOT_CACHE=0
export JOLT_NO_USER_DEPS=1
export TMPDIR="$gate_root/tmp"
export JOLT_SIM_BIN="$sim_bin"
export JOLT_SIM_PROJECT_DIR="$project_dir"
export JOLT_SIM_CASE_TEMP_DIR="$case_temp_dir"
export JOLT_SIM_CASE_ARTIFACT_DIR="$case_artifact_dir"

run_step environment directory-setup mkdir -p \
  "$HOME" \
  "$XDG_CACHE_HOME" \
  "$JOLT_CACHE_DIR" \
  "$JOLT_GITLIBS" \
  "$TMPDIR" \
  "$JOLT_SIM_CASE_TEMP_DIR" \
  "$JOLT_SIM_CASE_ARTIFACT_DIR" \
  "$gate_root/logs"

if [[ $# -eq 0 ]]; then
  set -- \
    hegel-explore-test \
    flow-effect-stateful-hegel-test \
    maelstrom-echo-hegel-test \
    maelstrom-broadcast-hegel-test \
    tcp-bencode-hegel-test \
    outbox-delivery-hegel-test
fi

declare -a gates=()
declare -a preflight_aliases=()
for gate in "$@"; do
  parent_alias="$gate"
  # Reset every iteration so a process-backed gate's worker alias cannot
  # leak into a later in-process gate's preflight list.
  worker_alias=""
  case "$gate" in
    hegel-explore-test)
      worker_alias=explore-worker-test
      ;;
    http-sqlite-hegel-test)
      worker_alias=http-sqlite-explore-worker
      ;;
    tcp-bencode-hegel-test)
      worker_alias=tcp-bencode-explore-worker
      ;;
    outbox-delivery-hegel-test)
      worker_alias=outbox-delivery-explore-worker
      ;;
    outbox-delivery-cancel-hegel-test)
      parent_alias=outbox-delivery-hegel-test
      worker_alias=outbox-delivery-explore-worker
      ;;
    outbox-http-webhook-hegel-test)
      worker_alias=outbox-http-webhook-explore-worker
      ;;
    maelstrom-echo-hegel-test)
      # In-process lane: the Echo round trip runs under the sim controller in
      # the gate process itself, so there is no nested worker alias to
      # preflight.
      ;;
    maelstrom-broadcast-hegel-test)
      # In-process lane: each message runs both Broadcast regimes under the sim
      # controller in this gate process, with no nested worker to preflight.
      ;;
    flow-effect-stateful-hegel-test)
      # In-process lane: the actual bridge/view APIs use a scripted retained
      # worker service and start no child process or native application stack.
      ;;
    *)
      echo "unknown Hegel gate: $gate" >&2
      echo "allowed: hegel-explore-test http-sqlite-hegel-test tcp-bencode-hegel-test outbox-delivery-hegel-test outbox-delivery-cancel-hegel-test outbox-http-webhook-hegel-test maelstrom-echo-hegel-test maelstrom-broadcast-hegel-test flow-effect-stateful-hegel-test" >&2
      echo "retained gate root: $gate_root" >&2
      exit 2
      ;;
  esac
  gates+=("$gate")
  preflight_aliases+=("$parent_alias")
  # Only process-backed gates have a nested worker alias to resolve.
  if [[ -n "$worker_alias" ]]; then
    preflight_aliases+=("$worker_alias")
  fi
done

echo "Git dependency cache: $JOLT_GITLIBS"
cd "$project_dir"

# Resolve every parent and nested-worker dependency before Hegel starts. A
# missing pin therefore fails once as infrastructure instead of becoming
# hundreds of generated failures with the same clone error.
for alias in "${preflight_aliases[@]}"; do
  run_step dependency-preflight "$alias" \
    "$sim_bin" -P "-M:$alias"
done

run_step native-preflight hegel.install \
  "$sim_bin" -A:hegel-explore-test -m hegel.install

for gate in "${gates[@]}"; do
  if [[ "$gate" == outbox-delivery-cancel-hegel-test ]]; then
    run_step hegel-gate "$gate" \
      "$sim_bin" -M:outbox-delivery-hegel-test --cancel-only
  else
    run_step hegel-gate "$gate" \
      "$sim_bin" "-M:$gate"
  fi
done

echo "all requested Hegel gates passed"
echo "retained gate root: $gate_root"
