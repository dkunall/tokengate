#!/usr/bin/env bash
# Single-machine benchmark: k6, the gateway, the mock provider, Postgres, Redis and Kafka all share one box.
# Creates a tenant with limits high enough not to interfere, then runs the three scenarios in docs/benchmark.md.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:-/workspace/tokengate-run/bench}
mkdir -p "$OUT"
GW=http://localhost:9080 MOCK=http://localhost:9082
ADMIN=$(scripts/admin-token.sh ADMIN)
tenant() { # $1 id, $2 semantic threshold; prints a fresh API key
  curl -sf -X POST "$GW/admin/tenants" -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
    -d "{\"id\":\"$1\",\"name\":\"bench\",\"requests_per_minute\":600000,\"burst\":20000,\"daily_tokens\":1000000000,\"monthly_cost_cap_usd\":\"100000\",\"cache\":{\"semantic_threshold\":$2}}" >/dev/null
  curl -sf -X POST "$GW/admin/tenants/$1/keys" -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d '{"name":"k6"}' | jq -r .key
}
suffix=$(date +%s | tail -c 6)
KEY=$(tenant "bench-$suffix" 0.9)
STRICT_KEY=$(tenant "bench-strict-$suffix" 0.999)

restore() {
  curl -s -X PATCH "$MOCK/control/alpha/config" -H 'Content-Type: application/json' -d '{"latencyMs":120,"jitterMs":30}' >/dev/null
  curl -s -X PATCH "$MOCK/control/beta/config" -H 'Content-Type: application/json' -d '{"latencyMs":40,"jitterMs":10}' >/dev/null
}
trap restore EXIT
run() { # $1 label, $2 key, $3 mode
  echo "== $1"
  k6 run -e API_KEY="$2" -e MODE="$3" bench/chat.js 2>&1 | tee "$OUT/k6-$1.txt" \
    | grep -E "http_req_duration|http_reqs|checks|http_req_failed|✗" || true
}

# zero upstream latency so the first three numbers are the gateway's own cost
for up in alpha beta; do
  curl -s -X PATCH "$MOCK/control/$up/config" -H 'Content-Type: application/json' -d '{"latencyMs":0,"jitterMs":0}' >/dev/null
done
k6 run -q -e API_KEY="$KEY" -e MODE=hit --duration 10s bench/chat.js >/dev/null 2>&1  # JIT warm-up, not reported
run l1-hit "$KEY" hit
run semantic-hit "$KEY" semantic
run miss "$STRICT_KEY" miss
restore
run miss-mock-latency "$STRICT_KEY" miss
