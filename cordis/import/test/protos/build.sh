#!/usr/bin/env bash
# Compile the corpus protos to a descriptor set (with imports), for the
# importer's tests. Needs grpcio-tools (pip install grpcio-tools).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
inc=$(python3 -c 'import grpc_tools, os; print(os.path.join(os.path.dirname(grpc_tools.__file__), "_proto"))')
cd "$here"
python3 -m grpc_tools.protoc -I. -I"$inc" --include_imports --descriptor_set_out=corpus.binpb shop.proto legacy.proto
echo "wrote $here/corpus.binpb"
