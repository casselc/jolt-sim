#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runner="$repo_dir/script/run-hegel-gates.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/jolt-sim-hegel-runner-test.XXXXXX")"

cleanup() {
  local status=$?
  if [[ "$status" -eq 0 ]]; then
    rm -rf "$test_root"
  else
    echo "failed runner-test evidence retained at $test_root" >&2
  fi
  return "$status"
}
trap cleanup EXIT

project="$test_root/project"
mkdir -p "$project" "$test_root/gitlibs"
printf '{}\n' > "$project/deps.edn"

fake_sim="$test_root/fake-jolt"
cat > "$fake_sim" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "-P" ]]; then
  echo "fake dependency preflight: $*"
  exit 0
fi

case " $* " in
  *" -M:hegel-explore-test "*)
    case "${FAKE_HEGEL_MODE:-success}" in
      success)
        echo "fake Hegel gate passed"
        ;;
      fail)
        echo "fake Hegel gate failed" >&2
        exit 7
        ;;
      inherited-writer)
        (sleep 1; echo "inherited writer drained") &
        echo "fake direct gate returned"
        ;;
      *)
        exit 91
        ;;
    esac
    ;;
  *)
    echo "fake preflight: $*"
    ;;
esac
EOF
chmod +x "$fake_sim"

run_case() {
  local name="$1"
  local mode="$2"
  local expected="$3"
  local case_root="$test_root/$name"
  local status
  mkdir -p "$case_root"
  set +e
  FAKE_HEGEL_MODE="$mode" \
  JOLT_SIM_BIN="$fake_sim" \
  JOLT_SIM_PROJECT_DIR="$project" \
  JOLT_SIM_GATE_PARENT="$case_root" \
  JOLT_GITLIBS="$test_root/gitlibs" \
    "$runner" hegel-explore-test > "$case_root/console.log" 2>&1
  status=$?
  set -e
  if [[ "$status" -ne "$expected" ]]; then
    echo "$name: expected exit $expected, got $status" >&2
    exit 1
  fi
  find "$case_root" -type f -name status.log -print -quit
}

success_status="$(run_case success success 0)"
grep -q $'hegel-gate\thegel-explore-test\tpass' "$success_status"
grep -q 'all requested Hegel gates passed' "${success_status%/status.log}/logs/hegel-gates.log"

failure_status="$(run_case failure fail 7)"
grep -q $'hegel-gate\thegel-explore-test\tfail:7' "$failure_status"
grep -q 'command=7 transcript=0' "${failure_status%/status.log}/logs/hegel-gates.log"

started="$(date +%s)"
writer_status="$(run_case inherited-writer inherited-writer 0)"
elapsed=$(( $(date +%s) - started ))
if [[ "$elapsed" -lt 1 ]]; then
  echo "inherited writer: pass was recorded before the output pipe drained" >&2
  exit 1
fi
grep -q 'inherited writer drained' "${writer_status%/status.log}/logs/hegel-gates.log"
grep -q $'hegel-gate\thegel-explore-test\tpass' "$writer_status"
writer_line="$(grep -n -m1 'inherited writer drained' \
  "${writer_status%/status.log}/logs/hegel-gates.log" | cut -d: -f1)"
success_line="$(grep -n -m1 'all requested Hegel gates passed' \
  "${writer_status%/status.log}/logs/hegel-gates.log" | cut -d: -f1)"
if [[ "$writer_line" -ge "$success_line" ]]; then
  echo "inherited writer: final success preceded output-pipe drain" >&2
  exit 1
fi

echo "run-hegel-gates lifecycle tests passed"
