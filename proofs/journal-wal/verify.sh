#!/usr/bin/env bash
set -euo pipefail

proof_root="$(cd "$(dirname "$0")" && pwd)"

z3 --version

check_status() {
  local expected="$1"
  local model="$2"
  local actual
  actual="$(z3 "$proof_root/$model" | sed -n '1p')"
  if [[ "$actual" != "$expected" ]]; then
    echo "$model: expected $expected, got $actual" >&2
    return 1
  fi
  echo "$model: $actual"
}

check_status unsat crash-cut-reference.smt2
check_status sat crash-cut-buggy-no-trailer.smt2
check_status unsat chain-prefix-reference.smt2
check_status sat chain-prefix-buggy-resync.smt2
check_status sat chain-prefix-buggy-local-crc.smt2
check_status unsat header-validation-reference.smt2
check_status sat header-validation-buggy-crc.smt2
check_status unsat length-safety-reference.smt2
check_status sat length-safety-buggy-trust.smt2
check_status sat length-safety-buggy-overflow.smt2
check_status unsat failure-isolation-reference.smt2
check_status sat failure-isolation-buggy-open.smt2
check_status sat failure-isolation-buggy-mask.smt2
check_status unsat durability-boundary-reference.smt2
check_status sat durability-boundary-buggy-write.smt2
check_status unsat write-loop-reference.smt2
check_status sat write-loop-buggy-partial.smt2
check_status sat write-loop-boundary.smt2
check_status unsat eintr-retry-reference.smt2
check_status unsat eintr-ranking-reference.smt2
check_status sat eintr-retry-buggy-accept.smt2
check_status sat eintr-retry-boundary.smt2
check_status sat boundary-witnesses.smt2
