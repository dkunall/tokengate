#!/usr/bin/env bash
# End-to-end walkthrough against a running stack (scripts/start.sh). Every value printed comes from the live
# services; API keys and JWTs are never printed, only an API key's public prefix.
#   scripts/demo.sh | tee /workspace/tokengate-run/demo-output.txt
set -euo pipefail
cd "$(dirname "$0")/.."

GW=${GW:-http://localhost:9080}
METER=${METER:-http://localhost:9081}
MOCK=${MOCK:-http://localhost:9082}
RUN=$(date +%s | tail -c 6)
ADMIN=$(scripts/admin-token.sh ADMIN)
BILLING=$(scripts/admin-token.sh BILLING)
HDRS=$(mktemp)
trap 'rm -f "$HDRS"; curl -s -X PATCH "$MOCK/control/alpha/config" -H "Content-Type: application/json" -d "{\"outage\":false}" >/dev/null || true' EXIT

step() { printf '\n=== %s\n' "$*"; }
show_headers() { tr -d '\r' < "$HDRS" | grep -iE "^(HTTP/|x-tokengate-|ratelimit-|retry-after|content-type)" | sed 's/^/  /'; }

admin() { # method path [body]
  curl -s -X "$1" "$GW$2" -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" ${3:+-d "$3"}
}

new_tenant() { # id json-overrides -> prints the plaintext key (kept in a shell variable only)
  local id=$1 extra=${2:-}
  admin POST /admin/tenants "{\"id\":\"$id\",\"name\":\"Demo $id\"${extra:+,$extra}}" >/dev/null
  admin POST "/admin/tenants/$id/keys" '{"name":"demo"}' | jq -r .key
}

chat() { # key prompt [extra-json]
  curl -s -D "$HDRS" "$GW/v1/chat/completions" -H "Authorization: Bearer $1" -H "Content-Type: application/json" \
    -d "{\"model\":\"gpt-4o-mini\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]${3:+,$3}}"
}

status() { tr -d '\r' < "$HDRS" | awk 'NR==1{print $2}'; }
hdr() { tr -d '\r' < "$HDRS" | awk -v h="$(echo "$1" | tr A-Z a-z)" 'tolower($1)==h":"{print $2}'; }

curl -s -X POST "$MOCK/control/reset" >/dev/null

step "0. Health"
for u in "$GW" "$METER" "$MOCK"; do printf '  %s -> %s\n' "$u" "$(curl -s "$u/actuator/health" | jq -c .)"; done
grep -h "semantic cache embeddings" /workspace/tokengate-run/logs/gateway.log 2>/dev/null | tail -1 | jq -r '"  " + .message' || true

step "1. Tenant and API key (key shown once by the API; only its prefix is printed here)"
T=demo-$RUN
KEY=$(new_tenant "$T" '"cache":{"enabled":true,"semantic_enabled":true,"semantic_threshold":0.85,"ttl_seconds":3600}')
echo "  tenant=$T key_prefix=${KEY:0:11}... (length ${#KEY})"
admin GET "/admin/tenants/$T/keys" | jq -c '[.[] | {id, prefix, name}]' | sed 's/^/  keys: /'

step "2. Normal call"
chat "$KEY" "What is the capital of France? Answer in one word. run $RUN" | jq -c '{model, content: .choices[0].message.content, usage}' | sed 's/^/  /'
show_headers

step "3. Cache: miss, then exact hit, then semantic hit on a paraphrase"
Q="Explain in two sentences what a circuit breaker does in a microservice system. run $RUN"
for prompt in "$Q" "$Q" "In two sentences, explain what a circuit breaker does in a microservices system. run $RUN"; do
  chat "$KEY" "$prompt" >/dev/null
  printf '  %-6s cache=%-14s similarity=%-8s provider=%s\n' "$(status)" "$(hdr X-TokenGate-Cache)" \
    "$(hdr X-TokenGate-Similarity)" "$(hdr X-TokenGate-Provider)"
done

step "4. PII masking: what the client sent vs what the upstream received (reversible mode puts the values back in the reply)"
P="My email is kunal.demo@example.com, phone +91 98765 43210, PAN ABCPE1234F, card 4111 1111 1111 1111, aadhaar 2341 2341 2346. Summarise my details. run $RUN"
echo "  client sent : $P"
chat "$KEY" "$P" | jq -r '"  client got  : " + .choices[0].message.content' | cut -c1-220
echo "  masked count: $(hdr X-TokenGate-Pii-Masked)  provider: $(hdr X-TokenGate-Provider)"
up=$(hdr X-TokenGate-Provider); up=${up#mock-}
curl -s "$MOCK/control/$up/requests" | jq -r --arg r "run $RUN" \
  '[.[] | select(.contents | join(" ") | contains("Summarise"))] | last | "  upstream got: " + (.contents | join(" | "))'

step "5. Rate limit: 3 requests/minute, burst 3, five quick calls"
RL_KEY=$(new_tenant "rl-$RUN" '"requests_per_minute":3,"burst":3')
for i in 1 2 3 4 5; do
  body=$(chat "$RL_KEY" "rate limit probe $i run $RUN")
  printf '  call %d -> %s  RateLimit-Remaining=%s Retry-After=%s\n' "$i" "$(status)" "$(hdr RateLimit-Remaining)" "$(hdr Retry-After)"
done
echo "  last body: $(echo "$body" | jq -c '{type, title, status, detail}')"
show_headers

step "6. Daily token budget: 150 tokens/day"
B_KEY=$(new_tenant "budget-$RUN" '"daily_tokens":150,"cache":{"enabled":false}')
for i in 1 2 3 4 5 6 7 8 9 10; do
  body=$(chat "$B_KEY" "Write a short haiku about Bengaluru traffic, number $i. run $RUN" '"max_tokens":40')
  printf '  call %d -> %s %s\n' "$i" "$(status)" "$(echo "$body" | jq -c 'if .usage then {total_tokens: .usage.total_tokens} else {type, detail} end')"
  [ "$(status)" = 429 ] && break
done
admin GET "/admin/tenants/budget-$RUN/budget" | jq -c . | sed 's/^/  budget: /'

step "7. Outage on mock-alpha: priority routing fails over to mock-beta"
F_KEY=$(new_tenant "failover-$RUN" '"routing":"priority","cache":{"enabled":false}')
chat "$F_KEY" "before the outage run $RUN" >/dev/null
echo "  before: $(status) provider=$(hdr X-TokenGate-Provider) attempts=$(hdr X-TokenGate-Attempts)"
curl -s -X PATCH "$MOCK/control/alpha/config" -H "Content-Type: application/json" -d '{"outage":true}' | jq -c '{name, outage: .behaviour.outage}' | sed 's/^/  mock: /'
for i in 1 2 3 4 5 6 7 8; do
  chat "$F_KEY" "during the outage $i run $RUN" >/dev/null
  printf '  during %d: %s provider=%s attempts=%s\n' "$i" "$(status)" "$(hdr X-TokenGate-Provider)" "$(hdr X-TokenGate-Attempts)"
done
admin GET /admin/providers | jq -c '[.[] | select(.id | startswith("mock")) | {id, breaker: (.breaker_state // .breaker)}]' | sed 's/^/  providers: /'
curl -s -X PATCH "$MOCK/control/alpha/config" -H "Content-Type: application/json" -d '{"outage":false}' >/dev/null
echo "  mock-alpha restored (the breaker closes again after its open window and a successful probe)"

step "8. Streaming (SSE), first and last events"
curl -sN -D "$HDRS" "$GW/v1/chat/completions" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"Stream a short greeting. run $RUN\"}]}" \
  > /tmp/tg-demo-stream.txt
show_headers
n=$(grep -c '^data:' /tmp/tg-demo-stream.txt)
echo "  data events: $n"
grep '^data:' /tmp/tg-demo-stream.txt | head -2 | cut -c1-200 | sed 's/^/  /'
echo "  ..."
grep '^data:' /tmp/tg-demo-stream.txt | tail -2 | cut -c1-200 | sed 's/^/  /'
echo "  reassembled: $(grep '^data: {' /tmp/tg-demo-stream.txt | sed 's/^data: //' | jq -rj '.choices[0].delta.content // empty')"

step "9. Metering: outbox -> Kafka -> daily usage -> invoice"
TODAY=$(date -u +%F); PERIOD=$(date -u +%Y-%m)
for _ in $(seq 1 30); do
  n=$(curl -s "$METER/admin/usage/$T?from=$TODAY&to=$TODAY" -H "Authorization: Bearer $BILLING" | jq '[.days[].requests] | add // 0')
  [ "$n" -ge 6 ] && break
  sleep 1
done
# python instead of jq here so cost_usd keeps its exact decimal text
curl -s "$METER/admin/usage/$T?from=$TODAY&to=$TODAY" -H "Authorization: Bearer $BILLING" \
  | python3 -c 'import json,sys
for d in json.load(sys.stdin, parse_float=str)["days"]: print("  usage:", " ".join(f"{k}={v}" for k, v in d.items()))'
curl -s -X POST "$METER/admin/invoices" -H "Authorization: Bearer $BILLING" -H "Content-Type: application/json" \
  -d "{\"tenant_id\":\"$T\",\"period\":\"$PERIOD\"}" | jq -c '{id, tenant_id, period, status, total_usd, lines: (.lines | length)}' | sed 's/^/  invoice: /'
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$METER/admin/prices" -H "Authorization: Bearer $BILLING" -H "Content-Type: application/json" -d '{}')
echo "  BILLING role trying to change prices -> $code"

step "Done (run $RUN)"
