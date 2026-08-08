#!/usr/bin/env bash
set -euo pipefail

# One retained campaign with a persistent outer Ripple and two sequential,
# genuinely independent inner producers. The outer stays pinned to epoch A
# while A advances and then B replaces A on the same loopback port.

if [[ -z "${JOLT_SIM_BIN:-}" ]]; then
  echo "JOLT_SIM_BIN must name the simulation-enabled jolt launcher" >&2
  exit 2
fi

viewer_dir="$(cd "$(dirname "$0")/.." && pwd)"
artifact_parent="${JOLT_SIM_VIEWER_ARTIFACT_DIR:-$viewer_dir/target/remote-session-artifacts}"
mkdir -p "$artifact_parent"
run_dir="$(mktemp -d "$artifact_parent/remote-session.XXXXXX")"
journal="$run_dir/campaign.journal"
inner_token="ripple-remote-producer-capability-0001"
outer_token="ripple-remote-consumer-capability-0001"
epoch_a="ripple-remote-producer-epoch-A001"
epoch_b="ripple-remote-producer-epoch-B001"
producer_pid=""
outer_pid=""

record() { printf '%s\n' "$1" >>"$journal"; }

stop_pid() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 100); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.05
    done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  stop_pid "$outer_pid"
  stop_pid "$producer_pid"
}

finish() {
  local status=$?
  trap - EXIT
  cleanup
  if [[ "$status" -ne 0 ]]; then
    record "{:phase :campaign-failed :status $status}"
    echo "remote Session process acceptance failed; artifacts: $run_dir" >&2
  fi
  exit "$status"
}
trap finish EXIT

wait_ready() {
  local path="$1" pid="$2" label="$3"
  for _ in $(seq 1 1200); do
    if [[ -s "$path" ]] && [[ "$(tail -c 1 "$path" | wc -l)" -eq 1 ]]; then
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.05
  done
  record "{:phase :readiness-failed :process :$label}"
  echo "$label did not become ready; artifacts: $run_dir" >&2
  return 1
}

wait_status=0
child_done() {
  local pid="$1"
  if ! kill -0 "$pid" 2>/dev/null; then
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

wait_for_exit() {
  local pid="$1" label="$2"
  for _ in $(seq 1 200); do
    child_done "$pid" && break
    sleep 0.05
  done
  if ! child_done "$pid"; then
    record "{:phase :process-exit-timeout :process :$label}"
    stop_pid "$pid"
    wait_status=124
    return 0
  fi
  if wait "$pid"; then
    wait_status=0
  else
    wait_status=$?
  fi
}

edn_value() {
  local path="$1" key="$2"
  sed -n "s/.*:$key \([^,}]*\).*/\1/p" "$path" | tr -d '"'
}

http_get() {
  local label="$1" port="$2" token="$3" cursor="$4" accept="$5"
  local request="$run_dir/$label.request.txt"
  local headers="$run_dir/$label.response.headers"
  local body="$run_dir/$label.response.body"
  printf 'GET /api/session-frame HTTP/1.1\nHost: 127.0.0.1:%s\nAccept: %s\nX-Jolt-Sim-Capability: %s\nX-Jolt-Sim-Journal-Cursor: %s\n' \
    "$port" "$accept" "$token" "$cursor" >"$request"
  curl --noproxy '*' --silent --show-error --max-time 5 \
    -D "$headers" -o "$body" -w '%{http_code}' \
    -H "Accept: $accept" \
    -H "X-Jolt-Sim-Capability: $token" \
    -H "X-Jolt-Sim-Journal-Cursor: $cursor" \
    "http://127.0.0.1:$port/api/session-frame" >"$run_dir/$label.status"
}

http_step() {
  local label="$1" port="$2" token="$3" body="$4"
  printf '%s\n' "$body" >"$run_dir/$label.request.body"
  printf 'POST /api/session-step HTTP/1.1\nHost: 127.0.0.1:%s\nContent-Type: application/json\nAccept: application/edn\nX-Jolt-Sim-Capability: %s\n\n%s\n' \
    "$port" "$token" "$body" >"$run_dir/$label.request.txt"
  curl --noproxy '*' --silent --show-error --max-time 5 \
    -D "$run_dir/$label.response.headers" \
    -o "$run_dir/$label.response.body" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/edn' \
    -H "X-Jolt-Sim-Capability: $token" \
    --data-binary "@$run_dir/$label.request.body" \
    "http://127.0.0.1:$port/api/session-step" \
    >"$run_dir/$label.status"
}

expect_status() {
  local label="$1" expected="$2"
  local actual
  actual="$(cat "$run_dir/$label.status")"
  if [[ "$actual" != "$expected" ]]; then
    record "{:phase :unexpected-http-status :request :$label :expected $expected :actual $actual}"
    echo "$label returned HTTP $actual, expected $expected; artifacts: $run_dir" >&2
    exit 1
  fi
}

record '{:phase :campaign-started}'

a_ready="$run_dir/producer-a-ready.edn"
a_step="$run_dir/producer-a-step.edn"
a_stepped="$run_dir/producer-a-stepped.edn"
a_release="$run_dir/producer-a-release.edn"
(
  cd "$viewer_dir"
  exec "$JOLT_SIM_BIN" -M:remote-session-producer \
    "$a_ready" "$a_step" "$a_stepped" "$a_release" 0 "$epoch_a"
) >"$run_dir/producer-a.stdout.log" 2>"$run_dir/producer-a.stderr.log" &
producer_pid=$!
record "{:phase :producer-a-launched :pid $producer_pid}"
wait_ready "$a_ready" "$producer_pid" producer-a
inner_port="$(edn_value "$a_ready" port)"
record "{:phase :producer-a-ready :port $inner_port :epoch \"$epoch_a\"}"

outer_ready="$run_dir/outer-ready.edn"
outer_reconcile="$run_dir/outer-reconcile.edn"
outer_reconciled="$run_dir/outer-reconciled.edn"
outer_release="$run_dir/outer-release.edn"
(
  cd "$viewer_dir"
  exec "$JOLT_SIM_BIN" -M:remote-session-consumer \
    "$a_ready" "$outer_ready" "$outer_reconcile" "$outer_reconciled" \
    "$outer_release"
) >"$run_dir/outer.stdout.log" 2>"$run_dir/outer.stderr.log" &
outer_pid=$!
record "{:phase :outer-launched :pid $outer_pid}"
wait_ready "$outer_ready" "$outer_pid" outer
outer_port="$(edn_value "$outer_ready" port)"
steppable_port="$(edn_value "$outer_ready" steppable-port)"
record "{:phase :outer-ready :port $outer_port :steppable-port $steppable_port :pinned-epoch \"$epoch_a\"}"

# Direct A and relayed outer EDN must be byte-for-byte canonical frame equals.
http_get direct-a0 "$inner_port" "$inner_token" 0 application/edn
http_get outer-a0 "$outer_port" "$outer_token" 0 application/edn
http_get outer-steppable-a0 "$steppable_port" "$outer_token" 0 application/edn
expect_status direct-a0 200
expect_status outer-a0 200
expect_status outer-steppable-a0 200
cmp "$run_dir/direct-a0.response.body" "$run_dir/outer-a0.response.body"
cmp "$run_dir/direct-a0.response.body" \
    "$run_dir/outer-steppable-a0.response.body"
record '{:phase :initial-canonical-frames-equal}'

# The JSON projection is explicitly read-only.
http_get outer-a0-json "$outer_port" "$outer_token" 0 application/json
expect_status outer-a0-json 200
grep -Fq '"stepEnabled":false' "$run_dir/outer-a0-json.response.body"
record '{:phase :outer-json-read-only}'

http_get outer-steppable-a0-json "$steppable_port" "$outer_token" 0 application/json
expect_status outer-steppable-a0-json 200
grep -Fq '"stepEnabled":true' "$run_dir/outer-steppable-a0-json.response.body"
record '{:phase :steppable-relay-explicit}'

# A step POST to the outer process is absent and cannot mutate A.
step_body='{"version":1,"cursor":"0","branch":{"revision":"0","kind":"run","value":"0"}}'
printf '%s\n' "$step_body" >"$run_dir/outer-step.request.body"
printf 'POST /api/session-step HTTP/1.1\nHost: 127.0.0.1:%s\nContent-Type: application/json\nX-Jolt-Sim-Capability: %s\n\n%s\n' \
  "$outer_port" "$outer_token" "$step_body" >"$run_dir/outer-step.request.txt"
curl --noproxy '*' --silent --show-error --max-time 5 \
  -D "$run_dir/outer-step.response.headers" \
  -o "$run_dir/outer-step.response.body" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "X-Jolt-Sim-Capability: $outer_token" \
  --data-binary "@$run_dir/outer-step.request.body" \
  "http://127.0.0.1:$outer_port/api/session-step" \
  >"$run_dir/outer-step.status"
expect_status outer-step 404
grep -Fq ':session-step-unavailable' "$run_dir/outer-step.response.body"
http_get direct-a0-after-rejected-step "$inner_port" "$inner_token" 0 application/edn
expect_status direct-a0-after-rejected-step 200
cmp "$run_dir/direct-a0.response.body" \
    "$run_dir/direct-a0-after-rejected-step.response.body"
record '{:phase :outer-step-rejected-without-inner-mutation}'

# The explicitly steppable relay sends one exact branch once. Repeating the
# byte-identical command is stale and cannot append a duplicate journal entry.
remote_step_body='{"version":1,"cursor":"0","branch":{"revision":"0","kind":"run","value":"0"}}'
http_step outer-remote-step "$steppable_port" "$outer_token" "$remote_step_body"
expect_status outer-remote-step 200
grep -Fq ':status :committed' "$run_dir/outer-remote-step.response.body"
grep -Fq ':committed? true' "$run_dir/outer-remote-step.response.body"
record '{:phase :remote-step-committed}'

http_step outer-remote-step-retry "$steppable_port" "$outer_token" "$remote_step_body"
expect_status outer-remote-step-retry 409
grep -Fq ':status :stale' "$run_dir/outer-remote-step-retry.response.body"
grep -Fq ':committed? false' "$run_dir/outer-remote-step-retry.response.body"
record '{:phase :identical-retry-stale}'

# Reconciliation is an explicit read-only operation from the original cursor.
printf '%s\n' '{:branch {:revision 0, :action [:run 0]}, :cursor 0}' \
  >"$outer_reconcile"
wait_ready "$outer_reconciled" "$outer_pid" outer-reconciliation
grep -Fq ':status :committed' "$outer_reconciled"
grep -Fq ':seq 1' "$outer_reconciled"
record '{:phase :journal-reconciliation-committed}'

# Cursor 1 exposes exactly the remotely appended journal record, identically
# direct and relayed, on repeats.
http_get direct-a1 "$inner_port" "$inner_token" 1 application/edn
http_get outer-a1 "$outer_port" "$outer_token" 1 application/edn
http_get outer-a1-repeat "$outer_port" "$outer_token" 1 application/edn
expect_status direct-a1 200
expect_status outer-a1 200
expect_status outer-a1-repeat 200
cmp "$run_dir/direct-a1.response.body" "$run_dir/outer-a1.response.body"
cmp "$run_dir/outer-a1.response.body" "$run_dir/outer-a1-repeat.response.body"
grep -Fq ':revision 1' "$run_dir/outer-a1.response.body"
grep -Fq ':count 2' "$run_dir/outer-a1.response.body"
grep -Fq ':cursor 1' "$run_dir/outer-a1.response.body"
grep -Fq ':next-cursor 2' "$run_dir/outer-a1.response.body"
grep -Fq ':page-size 1' "$run_dir/outer-a1.response.body"
[[ "$(grep -o ':command :step' "$run_dir/outer-a1.response.body" | wc -l)" -eq 1 ]]
record '{:phase :advanced-frame-equal-with-one-new-journal-entry}'

# Retire A cleanly, then bind epoch B at the exact same port. Outer remains
# alive and pinned to A throughout.
printf '%s\n' '{:release true}' >"$a_release"
wait_for_exit "$producer_pid" producer-a
a_status=$wait_status
producer_pid=""
record "{:phase :producer-a-exited :status $a_status}"
[[ "$a_status" -eq 0 ]]

b_ready="$run_dir/producer-b-ready.edn"
b_step="$run_dir/producer-b-step.edn"
b_stepped="$run_dir/producer-b-stepped.edn"
b_release="$run_dir/producer-b-release.edn"
(
  cd "$viewer_dir"
  exec "$JOLT_SIM_BIN" -M:remote-session-producer \
    "$b_ready" "$b_step" "$b_stepped" "$b_release" "$inner_port" "$epoch_b"
) >"$run_dir/producer-b.stdout.log" 2>"$run_dir/producer-b.stderr.log" &
producer_pid=$!
record "{:phase :producer-b-launched :pid $producer_pid :port $inner_port}"
wait_ready "$b_ready" "$producer_pid" producer-b
record "{:phase :producer-b-ready :port $inner_port :epoch \"$epoch_b\"}"

http_get direct-b0 "$inner_port" "$inner_token" 0 application/edn
expect_status direct-b0 200
grep -Fq ':revision 0' "$run_dir/direct-b0.response.body"
http_step outer-step-after-b "$steppable_port" "$outer_token" "$remote_step_body"
expect_status outer-step-after-b 409
grep -Fq ':session-source-restarted' "$run_dir/outer-step-after-b.response.body"
http_get direct-b0-after-rejected-step "$inner_port" "$inner_token" 0 application/edn
expect_status direct-b0-after-rejected-step 200
cmp "$run_dir/direct-b0.response.body" \
    "$run_dir/direct-b0-after-rejected-step.response.body"
record '{:phase :replacement-step-rejected-without-mutation}'
http_get outer-after-b "$outer_port" "$outer_token" 1 application/edn
expect_status outer-after-b 409
grep -Fq ':session-source-restarted' "$run_dir/outer-after-b.response.body"
if grep -Fq ':jolt.sim.session-view/type' "$run_dir/outer-after-b.response.body" ||
   cmp -s "$run_dir/outer-after-b.response.body" "$run_dir/direct-b0.response.body"; then
  record '{:phase :replacement-frame-leaked}'
  echo "outer Ripple leaked producer B frame; artifacts: $run_dir" >&2
  exit 1
fi
record '{:phase :replacement-rejected-without-frame-leak}'

printf '%s\n' '{:release true}' >"$b_release"
wait_for_exit "$producer_pid" producer-b
b_status=$wait_status
producer_pid=""
printf '%s\n' '{:release true}' >"$outer_release"
wait_for_exit "$outer_pid" outer
outer_status=$wait_status
outer_pid=""
record "{:phase :processes-exited :producer-b-status $b_status :outer-status $outer_status}"
[[ "$b_status" -eq 0 && "$outer_status" -eq 0 ]]

record '{:phase :campaign-passed}'
printf 'Ripple remote Session process acceptance passed; artifacts: %s\n' "$run_dir"
