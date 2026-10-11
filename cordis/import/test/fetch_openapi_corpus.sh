#!/usr/bin/env bash
# Fetch the OpenAPI corpus: a sample of the APIs.guru OpenAPI directory
# (https://github.com/APIs-guru/openapi-directory), every sixth OpenAPI 3
# document in path order at a pinned commit, the list in
# golden/openapi_corpus.list, into DIR (default: ./openapi-directory):
#   fetch_openapi_corpus.sh DIR && OPENAPI_CORPUS=DIR dune build @import/test/openapi_corpus
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
dir=${1:-openapi-directory}
commit=f04b8d0bcd39c52e1cf3ad7a5fe744709832ae49
if [ ! -d "$dir/.git" ]; then
  git clone -q --filter=blob:none --no-checkout https://github.com/APIs-guru/openapi-directory.git "$dir"
fi
cd "$dir"
git fetch -q --depth 1 origin "$commit"
git sparse-checkout init --no-cone
sed 's|^|/|' "$here/golden/openapi_corpus.list" | git sparse-checkout set --no-cone --stdin
git checkout -q "$commit"
echo "openapi-directory at $commit in $(pwd): $(wc -l < "$here/golden/openapi_corpus.list") documents"
