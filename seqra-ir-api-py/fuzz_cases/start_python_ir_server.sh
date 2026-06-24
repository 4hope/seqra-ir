#!/usr/bin/env bash
set -euo pipefail

ROOT="/mnt/c/MKN/project2/my-seqra-ir/seqra-ir-api-py/src/main/kotlin/org/seqra/ir/api/py/grpc"
cd "$ROOT"
exec ./venv/bin/python ./python_server.py --host 127.0.0.1 --port 50051
