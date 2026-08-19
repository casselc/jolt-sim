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
if [[ -n "${JOLT_GITLIBS_DIR:-}" ]]; then
  gitlibs_dir="$JOLT_GITLIBS_DIR"
elif [[ -n "$original_home" ]]; then
  gitlibs_dir="$original_home/.jolt/gitlibs"
else
  gitlibs_dir="$gate_root/gitlibs"
fi
if [[ -n "${JOLT_MAVEN_REPOSITORY:-}" ]]; then
  maven_repository="$JOLT_MAVEN_REPOSITORY"
elif [[ -n "$original_home" ]]; then
  maven_repository="$original_home/.m2/repository"
else
  maven_repository="$gate_root/m2/repository"
fi
if [[ "$gitlibs_dir" != /* ]]; then
  echo "JOLT_GITLIBS_DIR must be absolute: $gitlibs_dir" >&2
  exit 2
fi
if [[ "$maven_repository" != /* ]]; then
  echo "JOLT_MAVEN_REPOSITORY must be absolute: $maven_repository" >&2
  exit 2
fi

export HOME="$gate_root/home"
export XDG_CACHE_HOME="$gate_root/xdg-cache"
export JOLT_CACHE_DIR="$gate_root/aot-cache"
export JOLT_GITLIBS_DIR="$gitlibs_dir"
export JOLT_MAVEN_REPOSITORY="$maven_repository"
export JOLT_AOT_CACHE=0
export JOLT_NO_USER_DEPS=1
export TMPDIR="$gate_root/tmp"
mkdir -p "$HOME" "$XDG_CACHE_HOME" "$JOLT_CACHE_DIR" "$JOLT_GITLIBS_DIR" \
  "$JOLT_MAVEN_REPOSITORY" "$TMPDIR"

cd "$project_dir"
echo "[dependency-preflight] maelstrom-echo"
"$jolt_bin" -P -M:maelstrom-echo
echo "[dependency-preflight] maelstrom-echo-process-e2e-verify"
"$jolt_bin" -P -M:maelstrom-echo-process-e2e-verify
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required for bounded process supervision" >&2
  exit 69
fi

start_case_process() {
  local case_dir="$1"
  local input_path="$2"
  local deadline_seconds="$3"
  python3 "$repo_dir/script/run-bounded-process.py" \
    --deadline-seconds "$deadline_seconds" \
    --term-grace-seconds 2 \
    --stdin "$input_path" \
    --stdout "$case_dir/stdout.jsonl" \
    --stderr "$case_dir/stderr.log" \
    --pid-file "$case_dir/child-pid" \
    --status-file "$case_dir/status" \
    --timeout-file "$case_dir/exit-timeout" \
    --cleanup-failed-file "$case_dir/cleanup-failed" \
    -- "$jolt_bin" -M:maelstrom-echo 3>&- &
  case_supervisor_pid=$!
}

wait_for_child() {
  local supervisor_pid="$1"
  set +e
  wait "$supervisor_pid"
  set -e
}

run_case() {
  local case_name="$1"
  local case_dir="$gate_root/$case_name"
  mkdir -p "$case_dir"
  echo "[process-case] $case_name"
  start_case_process "$case_dir" "$case_dir/request.jsonl" 30
  wait_for_child "$case_supervisor_pid"
}

run_interactive_success_case() {
  local case_dir="$gate_root/success"
  local fifo="$case_dir/stdin.fifo"
  local observed="$case_dir/init-observed-before-echo"
  mkfifo "$fifo"
  exec 3<>"$fifo"
  start_case_process "$case_dir" "$fifo" 45
  local supervisor_pid="$case_supervisor_pid"
  printf '%s\n' "$(sed -n '1p' "$case_dir/request.jsonl")" >&3

  local observed_reply=0
  for _attempt in $(seq 1 600); do
    if [[ -f "$case_dir/stdout.jsonl" ]] &&
       [[ "$(wc -l < "$case_dir/stdout.jsonl")" -ge 1 ]]; then
      observed_reply=1
      printf '%s\n' "init_ok newline observed while stdin remained open" > "$observed"
      break
    fi
    if [[ -f "$case_dir/status" ]]; then
      break
    fi
    sleep 0.05
  done

  if [[ "$observed_reply" -eq 1 ]]; then
    printf '%s\n' "$(sed -n '2p' "$case_dir/request.jsonl")" >&3
  fi
  exec 3>&-

  wait_for_child "$supervisor_pid"
}

mkdir -p "$gate_root/success" "$gate_root/malformed" "$gate_root/semantic-invalid"
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
printf '%s\n' \
  '{"src":"c1","dest":"n1","body":{"type":"init","msg_id":1,"node_id":"n1","node_ids":["n1"]}}' \
  > "$gate_root/semantic-invalid/request.jsonl"
# Valid JSON that the unchanged node rejects structurally. Its large sentinel
# discriminates the entry point's coordinate-only diagnostic from Jolt's
# default uncaught-exception rendering of the node's complete ex-data.
printf '{"src":"c1","dest":"n1","body":{"type":7,"msg_id":2,"sentinel":"%04096d"}}\n' \
  0 >> "$gate_root/semantic-invalid/request.jsonl"

run_interactive_success_case
run_case malformed
run_case semantic-invalid

echo "[verify] retained JSON-lines wire evidence"
"$jolt_bin" -M:maelstrom-echo-process-e2e-verify "$gate_root"
echo "Maelstrom Echo JSON-lines process gate passed"
echo "retained gate root: $gate_root"
