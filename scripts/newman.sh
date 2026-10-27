#!/usr/bin/env bash
# Runs the Postman collection against the local stack. Short-lived JWTs are minted here and passed on the
# command line, so nothing secret lands in the collection or environment files.
set -euo pipefail
cd "$(dirname "$0")/.."
# The JSON export holds request headers (API key and JWTs), so it goes outside the repo by default.
out=${1:-/workspace/tokengate-run/newman}
newman run docs/postman/TokenGate.postman_collection.json \
  -e docs/postman/TokenGate.local.postman_environment.json \
  --env-var "adminToken=$(scripts/admin-token.sh ADMIN 300)" \
  --env-var "viewerToken=$(scripts/admin-token.sh VIEWER 300)" \
  --env-var "billingToken=$(scripts/admin-token.sh BILLING 300)" \
  --delay-request 100 --color off \
  --reporters cli,json --reporter-json-export "$out.json" | tee "$out.txt"
