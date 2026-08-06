#!/usr/bin/env bash

# Run one crash-prone gate while retaining a small, explicitly bounded forensic
# record. Only closed, non-secret metadata is retained; raw core images are
# moved outside the upload tree or removed after diagnostics.

set -uo pipefail

usage() {
  echo "usage: $0 ARTIFACT-PARENT LABEL -- COMMAND [ARG ...]" >&2
  exit 64
}

if [[ $# -lt 4 || "$3" != "--" ]]; then
  usage
fi

artifact_root=$1
label=$2
shift 3

if [[ ! "$label" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "forensic gate label contains unsupported characters: $label" >&2
  exit 64
fi

deadline_seconds=${JOLT_SIM_FORENSIC_TIMEOUT_SECONDS:-300}
if [[ ! "$deadline_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "JOLT_SIM_FORENSIC_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 64
fi

command_argv=("$@")
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
invocation="$label-$timestamp-${GITHUB_RUN_ATTEMPT:-0}-$$"
platform=$(uname -s)
command_pid=
command_started=0
command_finished=0
command_rc=0
command_group_survived=0
finalized=0

umask 077
if ! mkdir -p "$artifact_root"; then
  echo "could not create forensic artifact root: $artifact_root" >&2
  exit 73
fi
artifact_root=$(cd "$artifact_root" && pwd -P)
artifact_parent="$artifact_root/raw"
safe_parent="$artifact_root/upload-safe"
mkdir -p "$artifact_parent" "$safe_parent"
artifact_dir="$artifact_parent/$invocation"
safe_dir="$safe_parent/$invocation"
if ! mkdir -p "$artifact_dir"; then
  echo "could not create forensic artifact directory: $artifact_dir" >&2
  exit 73
fi
: > "$artifact_dir/status.log"
touch "$artifact_dir/started.marker"

status() {
  printf '%s\tevent=%s' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" \
    >> "$artifact_dir/status.log"
  shift
  if [[ $# -gt 0 ]]; then
    printf '\t%s' "$*" >> "$artifact_dir/status.log"
  fi
  printf '\n' >> "$artifact_dir/status.log"
}

write_command() {
  local arg
  : > "$artifact_dir/command.txt"
  for arg in "${command_argv[@]}"; do
    printf '%q ' "$arg" >> "$artifact_dir/command.txt"
  done
  printf '\n' >> "$artifact_dir/command.txt"
  printf '%q\n' "$PWD" > "$artifact_dir/cwd.txt"
}

write_environment_metadata() {
  local name
  local value
  : > "$artifact_dir/environment.txt"
  # Keep this list deliberately closed. Do not record tokens, the complete
  # environment, or GitHub's event payload path.
  for name in \
    CI GITHUB_ACTION GITHUB_ACTIONS GITHUB_JOB GITHUB_REF_NAME \
    GITHUB_RUN_ATTEMPT GITHUB_RUN_ID RUNNER_ARCH RUNNER_OS \
    JOLT_AOT_CACHE JOLT_CORE_SHA JOLT_SIM_BIN JOLT_SIM_PROJECT_DIR \
    JOLT_SIM_RUNNER_HOME; do
    # Avoid Bash 4's [[ -v ]]; hosted macOS may select system Bash 3.2.
    if value=$(printenv "$name" 2>/dev/null); then
      printf '%s=%q\n' "$name" "$value" >> "$artifact_dir/environment.txt"
    fi
  done
}

resource_snapshot() {
  local output=$1
  {
    printf 'captured_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'uname='; uname -a 2>&1 || true
    printf '\nlimits:\n'
    ulimit -a 2>&1 || true
    printf '\nfilesystem:\n'
    df -Pk "$PWD" "$artifact_root" 2>&1 | head -20 || true
    if [[ -r /proc/meminfo ]]; then
      printf '\nlinux_memory_kib:\n'
      awk '/^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapTotal|SwapFree):/ {print}' \
        /proc/meminfo
    elif command -v vm_stat >/dev/null 2>&1; then
      printf '\nmacos_vm_stat:\n'
      vm_stat 2>&1 | head -40 || true
      printf '\nmacos_hardware:\n'
      sysctl hw.memsize hw.ncpu 2>&1 || true
    fi
    if [[ -r /proc/sys/kernel/core_pattern ]]; then
      printf '\nlinux_core_pattern='
      head -c 1024 /proc/sys/kernel/core_pattern
      printf '\n'
    fi
  } > "$output"
}

bound_log() {
  local path=$1
  local limit=$((16 * 1024 * 1024))
  local half=$((limit / 2))
  local size
  local tmp

  [[ -f "$path" ]] || return 0
  size=$(wc -c < "$path")
  if (( size <= limit )); then
    return 0
  fi
  tmp="$path.bounded"
  head -c "$half" "$path" > "$tmp"
  printf '\n--- forensic wrapper omitted %s middle bytes ---\n' "$((size - limit))" \
    >> "$tmp"
  tail -c "$half" "$path" >> "$tmp"
  mv "$tmp" "$path"
  status output-bounded \
    "file=$(basename "$path") original_bytes=$size limit_bytes=$limit"
}

canonical_directory() {
  (cd "$1" 2>/dev/null && pwd -P)
}

collect_linux_backtrace() {
  local core=$1
  local ordinal=$2
  local raw="$artifact_dir/gdb-backtrace-$ordinal.raw"
  local bounded="$artifact_dir/gdb-backtrace-$ordinal.txt"
  local gdb_rc

  if ! command -v gdb >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
    status gdb-skipped "core_ordinal=$ordinal reason=missing-gdb-or-timeout"
    return 0
  fi

  status gdb-started "core_ordinal=$ordinal timeout_seconds=30"
  timeout --signal=TERM --kill-after=5s 30s \
    gdb -q -nx -batch \
      -ex 'set pagination off' \
      -ex 'info threads' \
      -ex 'thread apply all bt 80' \
      "${command_argv[0]}" "$core" > "$raw" 2>&1
  gdb_rc=$?
  head -c $((8 * 1024 * 1024)) "$raw" > "$bounded"
  rm -f "$raw"
  status gdb-finished \
    "core_ordinal=$ordinal exit_code=$gdb_rc max_bytes=$((8 * 1024 * 1024))"
}

quarantine_core() {
  local core=$1
  local ordinal=$2
  local detailed=$3
  local quarantine_root
  local canonical_quarantine=
  local destination

  quarantine_root="${RUNNER_TEMP:-/tmp}/jolt-sim-raw-cores"
  if mkdir -p "$quarantine_root"; then
    canonical_quarantine=$(canonical_directory "$quarantine_root")
  fi
  case "$canonical_quarantine" in
    "$artifact_root"|"$artifact_root"/*) canonical_quarantine= ;;
  esac
  if [[ -z "$canonical_quarantine" ]] && mkdir -p /tmp/jolt-sim-raw-cores; then
    canonical_quarantine=$(canonical_directory /tmp/jolt-sim-raw-cores)
    case "$canonical_quarantine" in
      "$artifact_root"|"$artifact_root"/*) canonical_quarantine= ;;
    esac
  fi

  if [[ -n "$canonical_quarantine" ]]; then
    destination="$canonical_quarantine/$invocation-$ordinal-$(basename "$core")"
    if mv "$core" "$destination"; then
      core_quarantined_count=$((core_quarantined_count + 1))
      if [[ "$detailed" -eq 1 ]]; then
        status core-quarantined \
          "core_ordinal=$ordinal raw_core_not_uploaded=true"
      fi
      return 0
    fi
  fi

  # This exact file has already been canonicalized, confined to a searched
  # root, timestamp-checked, and identified as an ELF core.
  if rm -f "$core"; then
    core_removed_count=$((core_removed_count + 1))
    if [[ "$detailed" -eq 1 ]]; then
      status core-removed \
        "core_ordinal=$ordinal reason=quarantine-failed raw_core_not_uploaded=true"
    fi
  else
    core_removal_failed_count=$((core_removal_failed_count + 1))
    if [[ "$detailed" -eq 1 ]]; then
      status core-removal-failed \
        "core_ordinal=$ordinal raw_core_upload_must_be_refused=true"
    fi
  fi
}

inspect_and_quarantine_core_candidate() {
  local candidate=$1
  local canonical_root=$2
  local candidate_dir
  local canonical_candidate
  local escaped_path
  local detailed=0

  [[ -f "$candidate" && "$candidate" -nt "$artifact_dir/started.marker" ]] || return 0
  candidate_dir=$(canonical_directory "$(dirname "$candidate")") || return 0
  canonical_candidate="$candidate_dir/$(basename "$candidate")"
  case "$canonical_candidate" in
    "$canonical_root"/*) ;;
    *) status core-refused "reason=escaped-search-root"; return 0 ;;
  esac
  if ! file -b "$canonical_candidate" 2>/dev/null | grep -qi 'elf.*core file'; then
    return 0
  fi

  core_count=$((core_count + 1))
  if [[ "$core_count" -le "$core_metadata_limit" ]]; then
    detailed=1
    escaped_path=$(printf '%q' "$canonical_candidate")
    printf 'core_%s_path=%.2048s\n' "$core_count" "$escaped_path" \
      >> "$artifact_dir/native-core-metadata.txt"
    printf 'core_%s_bytes=%s\n' "$core_count" "$(wc -c < "$canonical_candidate")" \
      >> "$artifact_dir/native-core-metadata.txt"
    status core-found "core_ordinal=$core_count raw_core_not_copied=true"
  else
    core_metadata_omitted=$((core_metadata_omitted + 1))
  fi

  # Diagnostics are count- and byte-bounded, but do not depend on the parent
  # command status: a descendant core remains evidence even if its leader
  # returned zero.
  if [[ "$core_diagnostic_count" -lt "$core_diagnostic_limit" ]]; then
    core_diagnostic_count=$((core_diagnostic_count + 1))
    collect_linux_backtrace "$canonical_candidate" "$core_count"
  else
    core_diagnostic_omitted=$((core_diagnostic_omitted + 1))
  fi
  quarantine_core "$canonical_candidate" "$core_count" "$detailed"
}

scan_core_root() {
  local root=$1
  local exclude_one=${2:-}
  local exclude_two=${3:-}
  local canonical_root
  local candidate
  [[ -d "$root" ]] || return 0
  canonical_root=$(canonical_directory "$root") || return 0

  if [[ -n "$exclude_one" && -n "$exclude_two" ]]; then
    while IFS= read -r candidate; do
      inspect_and_quarantine_core_candidate "$candidate" "$canonical_root"
    done < <(find "$canonical_root" -xdev \
      \( -path "$exclude_one" -o -path "$exclude_two" \) -prune -o \
      -type f -newer "$artifact_dir/started.marker" -size +0c -print 2>/dev/null)
  elif [[ -n "$exclude_one" ]]; then
    while IFS= read -r candidate; do
      inspect_and_quarantine_core_candidate "$candidate" "$canonical_root"
    done < <(find "$canonical_root" -xdev -path "$exclude_one" -prune -o \
      -type f -newer "$artifact_dir/started.marker" -size +0c -print 2>/dev/null)
  else
    while IFS= read -r candidate; do
      inspect_and_quarantine_core_candidate "$candidate" "$canonical_root"
    done < <(find "$canonical_root" -xdev -type f \
      -newer "$artifact_dir/started.marker" -size +0c -print 2>/dev/null)
  fi
}

scan_and_quarantine_linux_cores() {
  local canonical_pwd
  local canonical_target=
  local canonical_artifacts

  core_count=0
  core_metadata_omitted=0
  core_diagnostic_count=0
  core_diagnostic_omitted=0
  core_quarantined_count=0
  core_removed_count=0
  core_removal_failed_count=0
  core_metadata_limit=32
  core_diagnostic_limit=3
  : > "$artifact_dir/native-core-metadata.txt"

  canonical_pwd=$(canonical_directory "$PWD")
  [[ -d "$PWD/target" ]] && canonical_target=$(canonical_directory "$PWD/target")
  canonical_artifacts=$(canonical_directory "$artifact_root")

  # Scan each subtree once: artifacts first, the remainder of target second,
  # and the remainder of the project (excluding .git) last.
  scan_core_root "$canonical_artifacts"
  if [[ "$canonical_artifacts" == "$canonical_target" ]]; then
    :
  elif [[ -n "$canonical_target" && "$canonical_artifacts" == "$canonical_target"/* ]]; then
    scan_core_root "$canonical_target" "$canonical_artifacts"
  elif [[ -n "$canonical_target" ]]; then
    scan_core_root "$canonical_target"
  fi
  if [[ -n "$canonical_target" ]]; then
    scan_core_root "$canonical_pwd" "$canonical_target" "$canonical_pwd/.git"
  else
    scan_core_root "$canonical_pwd" "$canonical_artifacts" "$canonical_pwd/.git"
  fi

  status core-scan-finished \
    "matches=$core_count diagnostics=$core_diagnostic_count diagnostic_omitted=$core_diagnostic_omitted metadata_omitted=$core_metadata_omitted quarantined=$core_quarantined_count removed=$core_removed_count removal_failed=$core_removal_failed_count"
}

collect_macos_crash_reports() {
  local report_root
  local canonical_root
  local report
  local report_dir
  local canonical_report
  local copied=0
  local executable
  local size
  local destination
  local poll
  local seen="$artifact_dir/macos-crash-paths.txt"
  executable=$(basename "${command_argv[0]}")
  : > "$artifact_dir/macos-crash-reports.txt"
  : > "$seen"

  for poll in 1 2 3 4 5; do
    for report_root in \
      "${JOLT_SIM_RUNNER_HOME:-}/Library/Logs/DiagnosticReports" \
      /Library/Logs/DiagnosticReports; do
      [[ -d "$report_root" ]] || continue
      canonical_root=$(canonical_directory "$report_root") || continue
      for report in \
        "$canonical_root/$executable"*.crash \
        "$canonical_root/$executable"*.ips; do
        [[ -f "$report" && "$report" -nt "$artifact_dir/started.marker" ]] || continue
        report_dir=$(canonical_directory "$(dirname "$report")") || continue
        canonical_report="$report_dir/$(basename "$report")"
        case "$canonical_report" in
          "$canonical_root"/*) ;;
          *) continue ;;
        esac
        if grep -Fqx -- "$canonical_report" "$seen"; then
          continue
        fi
        printf '%s\n' "$canonical_report" >> "$seen"
        size=$(wc -c < "$canonical_report")
        destination="$artifact_dir/macos-crash-$copied-$(basename "$canonical_report")"
        head -c $((8 * 1024 * 1024)) "$canonical_report" > "$destination"
        printf 'source=%q bytes=%s retained=%q retained_bytes=%s\n' \
          "$canonical_report" "$size" "$(basename "$destination")" \
          "$(wc -c < "$destination")" >> "$artifact_dir/macos-crash-reports.txt"
        copied=$((copied + 1))
        [[ $copied -ge 2 ]] && break 3
      done
    done
    [[ $poll -lt 5 ]] && sleep 1
  done
  status macos-crash-scan-finished "matches=$copied polls=$poll"
}

copy_safe_artifact() {
  local source=$1
  local max_bytes=$2
  local required=${3:-0}
  local source_dir
  local canonical_source
  local destination

  if [[ ! -f "$source" || -L "$source" ]]; then
    if [[ "$required" -eq 1 ]]; then
      staging_errors=$((staging_errors + 1))
      status artifact-staging-failed "file=$(basename "$source") reason=missing-or-symlink"
      return 1
    fi
    return 0
  fi
  source_dir=$(canonical_directory "$(dirname "$source")") || {
    staging_errors=$((staging_errors + 1))
    return 1
  }
  canonical_source="$source_dir/$(basename "$source")"
  case "$canonical_source" in
    "$artifact_dir"/*) ;;
    *)
      staging_errors=$((staging_errors + 1))
      status artifact-staging-refused "reason=escaped-invocation"
      return 1
      ;;
  esac
  if file -b "$canonical_source" 2>/dev/null | grep -qi 'core file'; then
    staging_errors=$((staging_errors + 1))
    status artifact-staging-refused "reason=raw-core"
    return 1
  fi
  destination="$safe_dir/$(basename "$canonical_source")"
  head -c "$max_bytes" "$canonical_source" > "$destination" || {
    staging_errors=$((staging_errors + 1))
    status artifact-staging-failed "file=$(basename "$canonical_source")"
    return 1
  }
  return 0
}

stage_safe_artifacts() {
  local path
  staging_errors=0
  if ! mkdir -p "$safe_dir"; then
    staging_errors=1
    status artifact-staging-failed "reason=cannot-create-safe-directory"
    return 1
  fi

  # This is the complete publication allowlist. Raw discovery files remain in
  # raw/, and the workflow uploads only upload-safe/.
  copy_safe_artifact "$artifact_dir/command.txt" $((64 * 1024)) 1
  copy_safe_artifact "$artifact_dir/cwd.txt" $((8 * 1024)) 1
  copy_safe_artifact "$artifact_dir/environment.txt" $((64 * 1024)) 1
  copy_safe_artifact "$artifact_dir/resource-pre.txt" $((1024 * 1024)) 1
  copy_safe_artifact "$artifact_dir/resource-post.txt" $((1024 * 1024)) 1
  copy_safe_artifact "$artifact_dir/stdout.log" $((16 * 1024 * 1024)) 1
  copy_safe_artifact "$artifact_dir/stderr.log" $((16 * 1024 * 1024)) 1
  copy_safe_artifact "$artifact_dir/time.txt" $((1024 * 1024))
  copy_safe_artifact "$artifact_dir/started.marker" 1024 1
  copy_safe_artifact "$artifact_dir/deadline.marker" 1024
  copy_safe_artifact "$artifact_dir/gate-process-group.txt" 1024 1
  copy_safe_artifact "$artifact_dir/supervisor-status.txt" 1024 1
  copy_safe_artifact "$artifact_dir/native-core-metadata.txt" $((256 * 1024))
  copy_safe_artifact "$artifact_dir/macos-crash-reports.txt" $((64 * 1024))
  copy_safe_artifact "$artifact_dir/macos-crash-paths.txt" $((64 * 1024))
  for path in "$artifact_dir"/gdb-backtrace-*.txt; do
    copy_safe_artifact "$path" $((8 * 1024 * 1024))
  done
  for path in "$artifact_dir"/macos-crash-*; do
    case "$path" in
      *.txt) ;;
      *) copy_safe_artifact "$path" $((8 * 1024 * 1024)) ;;
    esac
  done
}

terminate_command_group() {
  local poll
  local gate_pid=
  local group_alive=0
  [[ -n "$command_pid" && "$command_finished" -eq 0 ]] || return 0
  status command-termination-requested "pid=$command_pid"
  # A signal can arrive between supervisor fork and pgid publication. Give the
  # signal-masked supervisor one bounded second to publish the independently
  # killable session id before falling back to its own relay handler.
  if [[ ! -s "$artifact_dir/gate-process-group.txt" ]]; then
    for poll in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$command_pid" 2>/dev/null || break
      [[ -s "$artifact_dir/gate-process-group.txt" ]] && break
      sleep 0.1
    done
  fi
  if [[ -r "$artifact_dir/gate-process-group.txt" ]]; then
    gate_pid=$(head -1 "$artifact_dir/gate-process-group.txt")
  fi
  if [[ "$gate_pid" =~ ^[1-9][0-9]*$ ]]; then
    # The Python supervisor created this process as a new session leader, so
    # the negative id cannot refer to the wrapper or its caller.
    kill -TERM -- "-$gate_pid" 2>/dev/null || true
  fi
  kill -TERM "$command_pid" 2>/dev/null || true
  for poll in 1 2 3 4 5; do
    group_alive=0
    if [[ "$gate_pid" =~ ^[1-9][0-9]*$ ]] \
       && kill -0 -- "-$gate_pid" 2>/dev/null; then
      group_alive=1
    fi
    if ! kill -0 "$command_pid" 2>/dev/null && [[ "$group_alive" -eq 0 ]]; then
      break
    fi
    sleep 1
  done
  if [[ "$gate_pid" =~ ^[1-9][0-9]*$ ]] \
     && kill -0 -- "-$gate_pid" 2>/dev/null; then
    kill -KILL -- "-$gate_pid" 2>/dev/null || true
  fi
  if kill -0 "$command_pid" 2>/dev/null; then
    kill -KILL "$command_pid" 2>/dev/null || true
  fi
  wait "$command_pid" 2>/dev/null || true
  if [[ "$gate_pid" =~ ^[1-9][0-9]*$ ]]; then
    for poll in 1 2 3 4 5; do
      kill -0 -- "-$gate_pid" 2>/dev/null || break
      sleep 1
    done
  fi
  if [[ "$gate_pid" =~ ^[1-9][0-9]*$ ]] \
     && kill -0 -- "-$gate_pid" 2>/dev/null; then
    status command-group-survived "pgid=$gate_pid"
    command_group_survived=1
  else
    status command-group-dead "pgid=${gate_pid:-unknown}"
  fi
  command_finished=1
}

finalize() {
  local exit_code=$1
  [[ "$finalized" -eq 0 ]] || return 0
  finalized=1
  # Once finalization begins, keep later cooperative cancellation signals from
  # re-entering or truncating the bounded forensic pass. The CI job-level hard
  # deadline remains the final bound.
  trap '' TERM INT HUP
  if [[ "$command_started" -eq 1 ]]; then
    bound_log "$artifact_dir/stdout.log"
    bound_log "$artifact_dir/stderr.log"
    resource_snapshot "$artifact_dir/resource-post.txt" || true
    if [[ "$platform" == Linux ]]; then
      scan_and_quarantine_linux_cores
    elif [[ "$platform" == Darwin && "$exit_code" -ne 0 ]]; then
      collect_macos_crash_reports
    fi
  fi
  stage_safe_artifacts
  if [[ "$staging_errors" -ne 0 && "$command_rc" -eq 0 ]]; then
    command_rc=74
    exit_code=$command_rc
  fi
  status wrapper-finished "exit_code=$exit_code staging_errors=$staging_errors"
  if ! copy_safe_artifact "$artifact_dir/status.log" $((1024 * 1024)) 1; then
    [[ "$command_rc" -ne 0 ]] || command_rc=74
  fi
  printf 'raw forensic artifacts: %s\n' "$artifact_dir" >&2
  printf 'upload-safe forensic artifacts: %s\n' "$safe_dir" >&2
}

handle_signal() {
  trap '' TERM INT HUP
  local signal_rc=$1
  local signal_name=$2
  # Prevent a second signal from re-entering termination/finalization.
  if [[ -f "$artifact_dir/deadline.marker" ]]; then
    signal_rc=124
    status deadline-exceeded "seconds=$deadline_seconds"
  else
    status wrapper-signal "signal=$signal_name exit_code=$signal_rc"
  fi
  terminate_command_group
  command_rc=$signal_rc
  [[ "$command_group_survived" -eq 0 ]] || command_rc=125
  finalize "$command_rc"
  trap - EXIT TERM INT HUP
  exit "$command_rc"
}

handle_exit() {
  trap '' TERM INT HUP
  local shell_rc=$1
  if [[ "$command_started" -eq 1 && "$command_finished" -eq 0 ]]; then
    terminate_command_group
    command_rc=$shell_rc
    if [[ "$command_rc" -eq 0 ]]; then
      command_rc=125
    fi
    [[ "$command_group_survived" -eq 0 ]] || command_rc=125
  fi
  if [[ "$command_started" -eq 0 ]]; then
    command_rc=$shell_rc
  elif [[ "$shell_rc" -ne 0 && "$command_rc" -eq 0 ]]; then
    command_rc=$shell_rc
  fi
  finalize "$command_rc"
}

trap 'handle_exit $?' EXIT
trap 'handle_signal 143 TERM' TERM
trap 'handle_signal 130 INT' INT
trap 'handle_signal 129 HUP' HUP

write_command
write_environment_metadata
resource_snapshot "$artifact_dir/resource-pre.txt"
status command-started "artifact_dir=$artifact_dir deadline_seconds=$deadline_seconds"

if [[ "$platform" == Linux ]]; then
  core_limit_before=$(ulimit -c 2>/dev/null || printf unknown)
  if ulimit -c unlimited 2>/dev/null; then
    status core-limit \
      "before=$core_limit_before after=$(ulimit -c 2>/dev/null || printf unknown)"
  else
    status core-limit-unavailable "before=$core_limit_before"
  fi
fi

timed_argv=()
if [[ "$platform" == Linux && -x /usr/bin/time ]]; then
  timed_argv=(/usr/bin/time -v -o "$artifact_dir/time.txt" "${command_argv[@]}")
elif [[ "$platform" == Darwin && -x /usr/bin/time ]]; then
  timed_argv=(/usr/bin/time -l -o "$artifact_dir/time.txt" "${command_argv[@]}")
else
  status time-unavailable
  timed_argv=("${command_argv[@]}")
fi

command_started=1
# Python is present on hosted Linux and macOS. It gives the gate a fresh session
# and supplies one portable deadline implementation; the shell remains the
# lifecycle owner that records and finalizes artifacts.
if ! command -v python3 >/dev/null 2>&1; then
  status supervisor-unavailable "command=python3"
  command_rc=69
  command_finished=1
  finalize "$command_rc"
  trap - EXIT TERM INT HUP
  exit "$command_rc"
fi
python3 -c '
import os
import signal
import subprocess
import sys
import time

deadline = int(sys.argv[1])
marker = sys.argv[2]
pid_file = sys.argv[3]
supervisor_status = sys.argv[4]
command = sys.argv[5:]
caught = [None]
watched = (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)
old_mask = None
p = None
rc = 125
group_dead = False

if hasattr(signal, "pthread_sigmask"):
    old_mask = signal.pthread_sigmask(signal.SIG_BLOCK, watched)

def child_restore_mask():
    if old_mask is not None:
        signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)

def signal_group(sig):
    try:
        os.killpg(p.pid, sig)
    except ProcessLookupError:
        pass

def group_exists():
    try:
        os.killpg(p.pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True

def group_has_live_members():
    if not group_exists():
        return False
    try:
        listing = subprocess.check_output(
            ["ps", "-axo", "pgid=,stat="],
            text=True,
            stderr=subprocess.DEVNULL,
        )
        for line in listing.splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[0] == str(p.pid):
                if not fields[1].startswith("Z"):
                    return True
        return False
    except (OSError, subprocess.SubprocessError):
        return group_exists()

def await_no_live_members(seconds):
    until = time.monotonic() + seconds
    while group_has_live_members() and time.monotonic() < until:
        time.sleep(0.05)
    return not group_has_live_members()

def stop_group():
    if not group_has_live_members():
        return True
    signal_group(signal.SIGTERM)
    if await_no_live_members(5):
        return True
    # Kill the pgid even if /usr/bin/time or the original leader has already
    # exited; a TERM-ignoring descendant still keeps the group alive.
    signal_group(signal.SIGKILL)
    return await_no_live_members(5)

def relay(signum, _frame):
    if caught[0] is None:
        caught[0] = signum
    if p is not None:
        signal_group(signal.SIGTERM)

try:
    p = subprocess.Popen(
        command,
        start_new_session=True,
        preexec_fn=child_restore_mask if old_mask is not None else None,
    )
    with open(pid_file, "x", encoding="ascii") as handle:
        handle.write(str(p.pid) + "\n")
    for watched_signal in watched:
        signal.signal(watched_signal, relay)
    if old_mask is not None:
        signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)
        old_mask = None
    try:
        rc = p.wait(timeout=deadline)
    except subprocess.TimeoutExpired:
        with open(marker, "x", encoding="ascii"):
            pass
        rc = 124
finally:
    if old_mask is not None:
        signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)
    if p is not None:
        group_dead = stop_group()
        try:
            p.wait(timeout=1)
        except subprocess.TimeoutExpired:
            signal_group(signal.SIGKILL)
            p.wait()

if caught[0] is not None:
    rc = 128 + caught[0]
elif rc < 0:
    rc = 128 - rc
if not group_dead:
    rc = 125
with open(supervisor_status, "x", encoding="ascii") as handle:
    handle.write("group_dead=" + ("true" if group_dead else "false") + "\n")
    handle.write("exit_code=" + str(rc) + "\n")
sys.exit(rc)
' "$deadline_seconds" "$artifact_dir/deadline.marker" \
  "$artifact_dir/gate-process-group.txt" "$artifact_dir/supervisor-status.txt" \
  "${timed_argv[@]}" \
  > "$artifact_dir/stdout.log" 2> "$artifact_dir/stderr.log" &
command_pid=$!

wait "$command_pid"
command_rc=$?
command_finished=1
if [[ -f "$artifact_dir/deadline.marker" ]]; then
  status deadline-exceeded "seconds=$deadline_seconds"
fi
status command-finished "exit_code=$command_rc"
finalize "$command_rc"
trap - EXIT TERM INT HUP
exit "$command_rc"
