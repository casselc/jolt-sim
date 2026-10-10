#!/usr/bin/env bash
# Fetch SchemaStore's schemas and test instances at the commit the golden
# file was made from, into DIR (default: ./schemastore):
#   fetch_schemastore.sh DIR && SCHEMASTORE=DIR dune build @import/test/schemastore
set -euo pipefail
dir=${1:-schemastore}
commit=3ccab69312e72f3c33f7f967a002ec126c9267b6
if [ ! -d "$dir/.git" ]; then
  git clone -q --filter=blob:none --no-checkout https://github.com/SchemaStore/schemastore.git "$dir"
fi
cd "$dir"
git sparse-checkout set src/schemas/json src/test src/negative_test
git fetch -q --depth 1 origin "$commit"
git checkout -q "$commit"
echo "SchemaStore at $commit in $(pwd)"
