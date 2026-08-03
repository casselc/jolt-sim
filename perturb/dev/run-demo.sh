#!/bin/sh
# Start a real jolt nREPL server, run the perturb demo against it, stop it.
#
#   JOLT=/path/to/jolt JOLT_CHEZ=/usr/local/bin/chez dev/run-demo.sh [port]
#
# CHEZ=chez fails on newer Makefiles; pass the full path.
set -e

PORT="${1:-7899}"
JOLT="${JOLT:-../../../jolt/bin/jolt}"
export JOLT_CHEZ="${JOLT_CHEZ:-/usr/local/bin/chez}"

here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
root="$(CDPATH= cd -- "$here/.." && pwd)"

if [ ! -x "$JOLT" ]; then
  echo "set JOLT to the jolt launcher (currently: $JOLT)" >&2
  exit 2
fi
JOLT="$(CDPATH= cd -- "$(dirname -- "$JOLT")" && pwd)/$(basename -- "$JOLT")"

# The server writes .nrepl-port into its own project dir; keep that out of the
# perturb tree.
mkdir -p "$here/server"
[ -f "$here/server/deps.edn" ] || echo '{:paths []}' > "$here/server/deps.edn"

echo "--- starting jolt nREPL server on $PORT"
( cd "$here/server" && "$JOLT" nrepl-server "$PORT" ) &
server_pid=$!
trap 'kill $server_pid 2>/dev/null || true' EXIT INT TERM

# wait for the port
i=0
while [ $i -lt 90 ]; do
  if (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then break; fi
  i=$((i+1)); sleep 1
done

echo "--- selftest"
( cd "$root" && "$JOLT" -M:selftest )
echo "--- refinement decision procedure (its boundary, as a table)"
( cd "$root" && "$JOLT" -M:refine )
echo "--- capability checker (two corpora; every ACCEPT is also executed)"
( cd "$root" && "$JOLT" -M:check )
echo "--- oracle"
( cd "$root" && "$JOLT" -M:oracle )
echo "--- demo"
( cd "$root" && "$JOLT" -M:demo "$PORT" )

# perturb's SECOND protocol. Needs no server: it is both ends of a loopback
# socket in one process, on its own port.
echo "--- http (second protocol; both handlers, one driver)"
( cd "$root" && "$JOLT" -M:http "$((PORT+1))" )

echo "--- no-I/O verifier (INHERITED I11)"
if command -v strace >/dev/null 2>&1; then
  JOLT="$JOLT" "$here/verify-noio.sh"
else
  echo "strace not found — running the in-process half only"
  ( cd "$root" && "$JOLT" -M:noio )
fi
