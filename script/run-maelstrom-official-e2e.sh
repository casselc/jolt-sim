#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_dir="${JOLT_SIM_PROJECT_DIR:-$repo_dir}"
jolt_bin="${JOLT_BIN:-${JOLT_SIM_BIN:-}}"
maelstrom_bin="${MAELSTROM_BIN:-}"
maelstrom_jar="${MAELSTROM_JAR:-}"
gate_parent="${JOLT_SIM_GATE_PARENT:-${TMPDIR:-/tmp}}"

if [[ -z "$jolt_bin" || "$jolt_bin" != /* || ! -x "$jolt_bin" ]]; then
  echo "JOLT_BIN or JOLT_SIM_BIN must name an absolute executable" >&2
  exit 2
fi
if [[ -z "$maelstrom_bin" || "$maelstrom_bin" != /* || ! -x "$maelstrom_bin" ]]; then
  echo "MAELSTROM_BIN must name an absolute executable" >&2
  exit 2
fi
if [[ -z "$maelstrom_jar" ]]; then
  maelstrom_jar="$(cd "$(dirname "$maelstrom_bin")" && pwd)/lib/maelstrom.jar"
fi
if [[ "$maelstrom_jar" != /* || ! -f "$maelstrom_jar" ]]; then
  echo "MAELSTROM_JAR must name the absolute Maelstrom jar" >&2
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
for required in python3 java sha256sum readlink; do
  if ! command -v "$required" >/dev/null 2>&1; then
    echo "$required is required for the official Maelstrom gate" >&2
    exit 69
  fi
done

mkdir -p "$gate_parent"
gate_root="$(mktemp -d "$gate_parent/jolt-sim-maelstrom-official.XXXXXX")"
mkdir -p "$gate_root/logs" "$gate_root/metadata" "$gate_root/bin"
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

{
  printf 'maelstrom-release=%s\n' "${MAELSTROM_RELEASE:-unknown}"
  printf 'maelstrom-archive-sha256=%s\n' "${MAELSTROM_ARCHIVE_SHA256:-unknown}"
  printf 'maelstrom-bin=%s\n' "$maelstrom_bin"
  printf 'maelstrom-jar=%s\n' "$maelstrom_jar"
  printf 'jolt-bin=%s\n' "$jolt_bin"
} > "$gate_root/metadata/coordinates.txt"
sha256sum "$maelstrom_bin" "$maelstrom_jar" "$jolt_bin" \
  > "$gate_root/metadata/executable-sha256.txt"
set +e
"$jolt_bin" --version > "$gate_root/metadata/jolt-version.stdout" \
  2> "$gate_root/metadata/jolt-version.stderr"
printf '%s\n' "$?" > "$gate_root/metadata/jolt-version.status"
set -e

cd "$project_dir"
echo "[dependency-preflight] maelstrom-echo"
"$jolt_bin" -P -M:maelstrom-echo
echo "[dependency-preflight] maelstrom-broadcast"
"$jolt_bin" -P -M:maelstrom-broadcast

build_entry() {
  local alias="$1"
  local entry="$2"
  local output="$3"
  local label="$4"
  local status
  local -a command=("$jolt_bin" "-A:$alias" build -m "$entry" -o "$output")

  {
    printf '%q ' "${command[@]}"
    printf '\n'
  } > "$gate_root/logs/$label-build.command"
  echo "[standalone-build] $label"
  set +e
  "${command[@]}" \
    > "$gate_root/logs/$label-build.stdout" \
    2> "$gate_root/logs/$label-build.stderr"
  status=$?
  set -e
  printf '%s\n' "$status" > "$gate_root/logs/$label-build.status"
  if [[ "$status" != "0" || ! -x "$output" ]]; then
    echo "$label standalone build failed with status $status; retained gate root: $gate_root" >&2
    exit 1
  fi
  sha256sum "$output" > "$gate_root/metadata/$label-binary-sha256.txt"
}

# Maelstrom starts every node concurrently and allows only ten seconds for all
# init replies. A source launcher pays dependency resolution and compilation in
# every child and can exhaust that fixed deadline even though the application
# is correct. Build each ordinary process entry point exactly once, serially,
# then exercise the resulting deployment artifacts under the official runner.
echo_bin="$gate_root/bin/jolt-maelstrom-echo"
broadcast_bin="$gate_root/bin/jolt-maelstrom-broadcast"
build_entry maelstrom-echo jolt.maelstrom.echo-main "$echo_bin" echo
build_entry maelstrom-broadcast jolt.maelstrom.broadcast-main "$broadcast_bin" broadcast

verifier="$gate_root/verify-results.clj"
cat > "$verifier" <<'CLOJURE'
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn fail! [profile reason data]
  (binding [*out* *err*]
    (println (pr-str (merge {:profile profile :reason reason} data))))
  (System/exit 1))

(defn ensure! [profile truth reason data]
  (when-not truth
    (fail! profile reason data)))

(defn history-operations [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (loop [found []]
      (let [value (edn/read {:eof ::eof} reader)]
        (if (= ::eof value)
          found
          (recur (if (map? value)
                   (conj found value)
                   found)))))))

(defn successful-count [operations function]
  (count (filter #(and (= :ok (:type %)) (= function (:f %))) operations)))

(let [[profile results-path history-path] *command-line-args*
      results (edn/read-string (slurp results-path))
      workload (:workload results)
      operations (history-operations history-path)]
  (ensure! profile (map? results) :results-not-a-map {})
  (ensure! profile (true? (:valid? results)) :top-level-invalid
           {:valid? (:valid? results)})
  (ensure! profile (map? workload) :missing-workload-result {})
  (ensure! profile (true? (:valid? workload)) :workload-invalid
           {:valid? (:valid? workload)})
  (if (= "echo" profile)
    (do
      (ensure! profile (empty? (:errors workload)) :echo-errors
               {:error-count (count (:errors workload))})
      (ensure! profile (pos? (successful-count operations :echo))
               :vacuous-echo {}))
    (do
      (ensure! profile (pos? (:attempt-count workload 0))
               :vacuous-broadcast {})
      (ensure! profile (pos? (successful-count operations :read))
               :no-successful-reads {})
      (ensure! profile (zero? (:never-read-count workload -1))
               :messages-never-read
               {:never-read-count (:never-read-count workload)})
      (ensure! profile (zero? (:lost-count workload -1))
               :lost-messages {:lost-count (:lost-count workload)})
      (ensure! profile (zero? (:duplicated-count workload -1))
               :duplicated-messages
               {:duplicated-count (:duplicated-count workload)})))
  (when (= "broadcast-partition" profile)
    (ensure! profile
             (some #(and (= :info (:type %))
                         (= :start-partition (:f %))
                         (vector? (:value %))
                         (= :isolated (first (:value %)))
                         (map? (second (:value %))))
                   operations)
             :missing-completed-partition {})
    (ensure! profile
             (some #(and (= :info (:type %))
                         (= :stop-partition (:f %))
                         (= :network-healed (:value %)))
                   operations)
             :missing-completed-heal {}))
  (println (pr-str {:profile profile
                    :valid? true
                    :workload-valid? true
                    :successful-echo-count
                    (successful-count operations :echo)
                    :successful-read-count
                    (successful-count operations :read)
                    :attempt-count (:attempt-count workload)
                    :never-read-count (:never-read-count workload)
                    :lost-count (:lost-count workload)
                    :duplicated-count (:duplicated-count workload)})))
CLOJURE

gate_failed=0

run_case() {
  local profile="$1"
  local deadline="$2"
  shift 2
  local case_dir="$gate_root/$profile"
  local command_path="$case_dir/command.sh"
  local status
  local run_dir
  local -a command=("$maelstrom_bin" "$@")

  mkdir -p "$case_dir"
  : > "$case_dir/stdin"
  {
    printf '%q ' "${command[@]}"
    printf '\n'
  } > "$command_path"

  echo "[official-maelstrom] $profile"
  set +e
  (
    cd "$case_dir"
    python3 "$repo_dir/script/run-bounded-process.py" \
      --deadline-seconds "$deadline" \
      --term-grace-seconds 5 \
      --stdin "$case_dir/stdin" \
      --stdout "$case_dir/runner.stdout" \
      --stderr "$case_dir/runner.stderr" \
      --pid-file "$case_dir/runner.pid" \
      --pgid-file "$case_dir/runner.pgid" \
      --lifecycle-file "$case_dir/runner.lifecycle" \
      --status-file "$case_dir/runner.status" \
      --timeout-file "$case_dir/runner.timeout" \
      --cleanup-failed-file "$case_dir/runner.cleanup-failed" \
      --require-process-group-cleanup \
      -- "${command[@]}"
  )
  set -e

  if [[ ! -f "$case_dir/runner.status" ]]; then
    echo "$profile did not publish a supervisor status" >&2
    gate_failed=1
    return
  fi
  status="$(<"$case_dir/runner.status")"
  if [[ "$status" != "0" ]]; then
    echo "$profile exited with status $status" >&2
    gate_failed=1
    return
  fi
  if [[ ! -L "$case_dir/store/latest" ]]; then
    echo "$profile produced no store/latest result" >&2
    gate_failed=1
    return
  fi
  run_dir="$(readlink -f "$case_dir/store/latest")"
  case "$run_dir" in
    "$case_dir"/store/*) ;;
    *)
      echo "$profile latest result escaped its retained case directory" >&2
      gate_failed=1
      return
      ;;
  esac
  printf '%s\n' "$run_dir" > "$case_dir/resolved-run-dir"
  if [[ ! -f "$run_dir/results.edn" || ! -f "$run_dir/history.edn" ]]; then
    echo "$profile result is missing results.edn or history.edn" >&2
    gate_failed=1
    return
  fi

  set +e
  java -cp "$maelstrom_jar" clojure.main "$verifier" \
    "$profile" "$run_dir/results.edn" "$run_dir/history.edn" \
    > "$case_dir/verification.stdout" \
    2> "$case_dir/verification.stderr"
  status=$?
  set -e
  printf '%s\n' "$status" > "$case_dir/verification.status"
  if [[ "$status" != "0" ]]; then
    echo "$profile retained result verification failed" >&2
    gate_failed=1
  fi
}

run_case echo 120 \
  test -w echo --bin "$echo_bin" --node-count 1 --time-limit 10
run_case broadcast-healthy 180 \
  test -w broadcast --bin "$broadcast_bin" --node-count 5 \
  --time-limit 20 --rate 10 --topology tree4
run_case broadcast-partition 240 \
  test -w broadcast --bin "$broadcast_bin" --node-count 5 \
  --time-limit 20 --rate 10 --topology tree4 \
  --nemesis partition --nemesis-interval 5

if [[ "$gate_failed" != "0" ]]; then
  echo "official Maelstrom gate failed; retained gate root: $gate_root" >&2
  exit 1
fi

echo "official Maelstrom Echo and Broadcast gates passed"
echo "retained gate root: $gate_root"
