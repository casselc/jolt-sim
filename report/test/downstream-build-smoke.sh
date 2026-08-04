#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
report_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$report_root/.." && pwd)
artifact_parent=${JOLT_REPORT_SMOKE_ARTIFACTS:-"$repo_root/target/report-consumer-smoke"}
jolt_bin=${JOLT_BIN:-jolt}

mkdir -p "$artifact_parent"
artifact_dir=$(mktemp -d "$artifact_parent/run.XXXXXX")
scratch="$artifact_dir/jolt-sim"

on_exit() {
  status=$?
  if [ "$status" -eq 0 ]; then
    echo "downstream report image: PASS"
  else
    echo "downstream report image: FAIL ($status)" >&2
  fi
  echo "artifacts preserved: $artifact_dir" >&2
}
trap on_exit EXIT

# Build from a disposable checkout-shaped copy. After linking, the whole copy
# is moved away, so an accidental runtime io/resource/source lookup cannot pass
# merely because the developer's worktree or dependency cache still exists.
mkdir -p "$scratch/report/test"
cp -R "$repo_root/src" "$scratch/src"
cp "$repo_root/deps.edn" "$scratch/deps.edn"
cp -R "$report_root/src" "$scratch/report/src"
cp -R "$report_root/resources" "$scratch/report/resources"
cp "$report_root/deps.edn" "$scratch/report/deps.edn"
cp -R "$report_root/test/consumer" "$scratch/report/test/consumer"

(
  cd "$scratch/report/test/consumer"
  JOLT_NO_USER_DEPS=1 "$jolt_bin" build \
    -m jolt.sim.report-consumer \
    -o "$artifact_dir/report-consumer"
) >"$artifact_dir/build.stdout" 2>"$artifact_dir/build.stderr"

mv "$scratch" "$artifact_dir/source.unavailable"
mkdir -p "$artifact_dir/run"
(
  cd "$artifact_dir/run"
  "$artifact_dir/report-consumer"
) >"$artifact_dir/run.stdout" 2>"$artifact_dir/run.stderr"

grep -qx "REPORT_CONSUMER_PASS" "$artifact_dir/run.stdout"
