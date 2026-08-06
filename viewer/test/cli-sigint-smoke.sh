#!/usr/bin/env bash
set -euo pipefail

# Exercise the real Linux SIGINT path instead of only mocking the host
# lifecycle functions. Every run is retained so an intermittent shutdown
# failure leaves its exact config and process output behind.

if [[ ! -r /proc/self/stat ]]; then
  echo "cli-sigint-smoke.sh is a Linux-only host gate" >&2
  exit 2
fi

if [[ -z "${JOLT_SIM_BIN:-}" ]]; then
  echo "JOLT_SIM_BIN must name the simulation-enabled jolt launcher" >&2
  exit 2
fi

viewer_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_dir="${JOLT_SIM_PROJECT_DIR:-$(cd "$viewer_dir/.." && pwd)}"
artifact_parent="${JOLT_SIM_VIEWER_ARTIFACT_DIR:-$repo_dir/target/viewer-sigint-artifacts}"
mkdir -p "$artifact_parent"
run_dir="$(mktemp -d "$artifact_parent/cli-sigint.XXXXXX")"
config="$run_dir/config.edn"
stdout="$run_dir/stdout.log"
stderr="$run_dir/stderr.log"
token="ripple-cli-sigint-smoke-token-0001"

printf '%s\n' \
  '{:port 0' \
  ' :allowed-scenarios #{jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset}' \
  ' :runtime-config' \
  " {:worker-command [\"$JOLT_SIM_BIN\" \"-M:outbox-delivery-explore-worker\"]" \
  "  :dir \"$repo_dir\"" \
  '  :timeout-ms 30000}}' >"$config"

pid=""
child_done() {
  if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
    return 0
  fi
  if [[ -r "/proc/$pid/stat" ]]; then
    local stat_pid stat_command stat_state stat_rest
    read -r stat_pid stat_command stat_state stat_rest <"/proc/$pid/stat"
    [[ "$stat_state" == Z ]]
    return
  fi
  return 1
}

wait_for_child_exit() {
  local attempts="$1"
  for _ in $(seq 1 "$attempts"); do
    if child_done; then
      return 0
    fi
    sleep 0.05
  done
  return 1
}

cleanup_child() {
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    if ! wait_for_child_exit 100; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
  fi
}
trap cleanup_child EXIT

(
  cd "$viewer_dir"
  export JOLT_SIM_VIEWER_TOKEN="$token"
  exec "$JOLT_SIM_BIN" -M:viewer "$config"
) >"$stdout" 2>"$stderr" &
pid=$!

ready=false
for _ in $(seq 1 1200); do
  if grep -q '^Ripple: http://127\.0\.0\.1:[0-9][0-9]*$' "$stdout"; then
    ready=true
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  sleep 0.05
done

if [[ "$ready" != true ]]; then
  echo "Ripple did not become ready; artifacts: $run_dir" >&2
  wait "$pid" 2>/dev/null || true
  pid=""
  exit 1
fi

port="$(sed -n 's/^Ripple: http:\/\/127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' "$stdout" | tail -1)"
if ! curl --noproxy '*' --fail --silent --show-error --max-time 2 \
     "http://127.0.0.1:$port/" >/dev/null; then
  echo "Ripple announced port $port but did not serve; artifacts: $run_dir" >&2
  exit 1
fi

kill -INT "$pid"
if ! wait_for_child_exit 200; then
  echo "Ripple did not exit within 10 seconds after SIGINT; artifacts: $run_dir" >&2
  exit 1
fi
set +e
wait "$pid"
status=$?
set -e
pid=""

if [[ "$status" -ne 0 ]]; then
  echo "Ripple exited $status after SIGINT; artifacts: $run_dir" >&2
  exit 1
fi
if grep -Fq 'thread does not own mutex' "$stderr" ||
   grep -Fq 'Unhandled exception' "$stderr"; then
  echo "Ripple emitted an unhandled shutdown diagnostic; artifacts: $run_dir" >&2
  exit 1
fi
if curl --noproxy '*' --fail --silent --max-time 1 \
     "http://127.0.0.1:$port/" >/dev/null 2>&1; then
  echo "Ripple listener still served after SIGINT; artifacts: $run_dir" >&2
  exit 1
fi

printf 'Ripple SIGINT smoke passed; artifacts: %s\n' "$run_dir"
