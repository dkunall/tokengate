#!/usr/bin/env bash
# Runs a short check against the real Gemini API through a running TokenGate stack (scripts/start.sh).
# Needs GEMINI_API_KEY in the environment of the gateway process; this script only checks that it is set and
# never reads, prints or passes the value. Skips cleanly when it is unset.
#
# For the outbound-prompt (PII) proof and the failover step, start the gateway with
#   SPRING_CONFIG_ADDITIONAL_LOCATION=file:<extra providers yml>   (adds disabled gemini-badmodel / gemini-badurl)
#   LOGGING_LEVEL_TOKENGATE_PROVIDER_WIRE=TRACE                      (logs outbound bodies, never headers)
#   LOGGING_LEVEL_COM_TOKENGATE_GATEWAY_APPLICATION_CHAT=DEBUG       (estimate vs reported usage)
# About 10 real Gemini requests per run.
#   scripts/real-provider-check.sh | tee /workspace/tokengate-run/real-gemini-output.txt
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "GEMINI_API_KEY is not set, skipping the real provider check."
  exit 0
fi

GW=${GW:-http://localhost:9080}
METER=${METER:-http://localhost:9081}
LOG=${GATEWAY_LOG:-/workspace/tokengate-run/logs/gateway.log}
RUN=$(date +%s | tail -c 6)
ADMIN=$(scripts/admin-token.sh ADMIN)
BILLING=$(scripts/admin-token.sh BILLING)
HDRS=$(mktemp)
BODY=$(mktemp)

step() { printf '\n=== %s\n' "$*"; }
admin() { curl -s -X "$1" "$GW$2" -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" ${3:+-d "$3"}; }
hdr() { tr -d '\r' < "$HDRS" | awk -v h="$(echo "$1" | tr A-Z a-z)" 'tolower($1)==h":"{print $2}'; }
status() { tr -d '\r' < "$HDRS" | awk 'NR==1{print $2}'; }
new_tenant() { # id extra-json (the cache is off unless the extra json sets it)
  local extra=${2:-}
  case $extra in *'"cache"'*) ;; *) extra="\"cache\":{\"enabled\":false}${extra:+,$extra}" ;; esac
  admin POST /admin/tenants "{\"id\":\"$1\",\"name\":\"Real check $1\",$extra}" >/dev/null
  admin POST "/admin/tenants/$1/keys" '{"name":"real-check"}' | jq -r .key
}
# path key body -> writes $BODY and $HDRS, latency in $t. Remembers where the gateway log was, so wire()
# can show what this call sent upstream (stream events are logged off the request thread, without its id).
post() { MARK=$(wc -l < "$LOG"); t=$(curl -s -o "$BODY" -D "$HDRS" -w '%{time_total}' "$GW$1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" -d "$3"); }
chat() { post /v1/chat/completions "$1" "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]${3:+,$3}}"; }
summary() { printf '  %s %ss provider=%s attempts=%s cache=%s pii=%s\n' "$(status)" "$1" "$(hdr X-TokenGate-Provider)" \
  "$(hdr X-TokenGate-Attempts)" "$(hdr X-TokenGate-Cache)" "$(hdr X-TokenGate-Pii-Masked)"; }
# Wire log lines for the last request (bodies only; the logger never writes headers). Long vectors and
# thought signatures are cut so the output stays readable.
wire() {
  sleep 0.3
  tail -n +"$((MARK + 1))" "$LOG" | jq -rR 'fromjson? // empty' | jq -r 'select(.logger=="tokengate.provider.wire" or (.message|startswith("usage") or startswith("stream usage"))) | .message' \
    | sed -E 's/"thoughtSignature": ?"[^"]*"/"thoughtSignature":"..."/g; s/( *-?[0-9]\.[0-9]+, *){8,}/ ...numbers... /g; s/ {2,}/ /g' \
    | cut -c1-900 | sed 's/^/  wire: /'
}

# provider settings are changed for the failover steps; put them back however the script exits
declare -A ORIG
for p in gemini gemini-badmodel gemini-badurl mock-alpha mock-beta; do
  ORIG[$p]=$(admin GET /admin/providers | jq -c --arg p "$p" '.[] | select(.id==$p) | {priority, enabled}')
done
restore() {
  for p in "${!ORIG[@]}"; do [ -n "${ORIG[$p]}" ] && admin PATCH "/admin/providers/$p" "${ORIG[$p]}" >/dev/null; done
  rm -f "$HDRS" "$BODY"
}
trap restore EXIT
set_provider() { admin PATCH "/admin/providers/$1" "$2" >/dev/null; }

echo "Real Gemini check, run $RUN, $(date -u +'%F %T') UTC"

step "1. Providers (gemini is registered only when the key is set)"
admin GET /admin/providers | jq -c '.[] | {id, priority, enabled, models, breaker}' | sed 's/^/  /'
if ! admin GET /admin/providers | jq -e '.[] | select(.id=="gemini")' >/dev/null; then
  echo "  gemini is not registered: restart the gateway with GEMINI_API_KEY in its environment."; exit 0
fi
set_provider gemini '{"priority":0,"enabled":true}'
KEY=$(new_tenant "gem-$RUN" '"routing":"priority"')
echo "  tenant gem-$RUN, priority routing, gemini moved to priority 0 for this run"

step "2. Non-streaming chat"
post /v1/chat/completions "$KEY" '{"model":"gpt-4o-mini","messages":[{"role":"system","content":"Answer briefly."},{"role":"user","content":"What is the capital of France? One word."}]}'
summary "$t"; jq -c '{model, content: .choices[0].message.content, finish: .choices[0].finish_reason, usage}' "$BODY" | sed 's/^/  /'; wire

step "3. Streaming"
MARK=$(wc -l < "$LOG"); t=$(curl -sN -o "$BODY" -D "$HDRS" -w '%{time_total}' "$GW/v1/chat/completions" -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Count from 1 to 5, separated by commas."}]}')
summary "$t"; echo "  content-type: $(hdr Content-Type)  events: $(grep -c '^data:' "$BODY")"
grep '^data:' "$BODY" | cut -c1-260 | sed 's/^/  /'
echo "  reassembled: $(grep '^data: {' "$BODY" | sed 's/^data: //' | jq -rj '.choices[0].delta.content // empty')"; wire

step "4. Semantic cache: question, then a paraphrase (temperature 0, or the cache skips it as non-deterministic)"
C_KEY=$(new_tenant "gem-cache-$RUN" '"routing":"priority","cache":{"enabled":true,"semantic_enabled":true,"semantic_threshold":0.85,"ttl_seconds":3600}')
chat "$C_KEY" "In one sentence, what does a circuit breaker do in a microservice system? run $RUN" '"temperature":0'; summary "$t"
jq -r '"  answer: " + .choices[0].message.content' "$BODY" | cut -c1-240
chat "$C_KEY" "In one sentence, what does a circuit breaker do in a microservices system? run $RUN" '"temperature":0'; summary "$t"
echo "  similarity=$(hdr X-TokenGate-Similarity)"
n=$(tail -n +"$((MARK + 1))" "$LOG" | grep -c 'gemini -> POST' || true)
echo "  gemini requests made for the paraphrase: $n"

step "5. PII: email, +91 phone, PAN, test card (Luhn), test Aadhaar (Verhoeff)"
P="Repeat these back exactly, one per line: kunal.demo@example.com, +91 98765 43210, ABCPE1234F, 4111 1111 1111 1111, 2341 2341 2346"
echo "  client sent: $P"
chat "$KEY" "$P"; summary "$t"
jq -r '.choices[0].message.content' "$BODY" | sed 's/^/  client got: /'
wire

step "6a. Failover, normal path: gemini first, mock-beta as fallback (mock-alpha off for this step)"
set_provider mock-alpha '{"enabled":false}'
chat "$KEY" "Say OK."; summary "$t"; jq -c '{content: .choices[0].message.content}' "$BODY" | sed 's/^/  /'

if admin GET /admin/providers | jq -e '.[] | select(.id=="gemini-badmodel")' >/dev/null; then
  step "6b. Failover: a gemini entry with a model Google doesn't have, then mock-beta"
  set_provider gemini '{"priority":5}'
  set_provider gemini-badmodel '{"priority":0,"enabled":true}'
  chat "$KEY" "Say OK."; summary "$t"; jq -c '{content: .choices[0].message.content}' "$BODY" | cut -c1-200 | sed 's/^/  /'
  wire | grep -v mock-beta || true

  step "6c. Failover: a gemini entry with a dead base URL (no request reaches Google), then mock-beta"
  set_provider gemini-badmodel '{"enabled":false}'
  set_provider gemini-badurl '{"priority":0,"enabled":true}'
  chat "$KEY" "Say OK."; summary "$t"
  set_provider gemini-badurl '{"enabled":false}'

  step "7a. Error mapping: only the bad-model entry left"
  for p in gemini mock-beta; do set_provider $p '{"enabled":false}'; done
  set_provider gemini-badmodel '{"priority":0,"enabled":true}'
  chat "$KEY" "Say OK."; echo "  $(status) $(hdr Content-Type) ${t}s"; jq -c . "$BODY" | cut -c1-700 | sed 's/^/  /'
  set_provider gemini-badmodel '{"enabled":false}'
  for p in gemini mock-beta; do set_provider $p '{"enabled":true}'; done
  set_provider gemini '{"priority":0}'
else
  echo "  (gemini-badmodel / gemini-badurl not configured, skipping 6b, 6c and 7a)"
fi
set_provider mock-alpha '{"enabled":true}'

step "7b. Error mapping: model unknown to the gateway"
post /v1/chat/completions "$KEY" '{"model":"gemini-9-ultra","messages":[{"role":"user","content":"hi"}]}'
echo "  $(status) $(hdr Content-Type)"; jq -c . "$BODY" | sed 's/^/  /'

step "7c. max_tokens 1 (cut-off reply should finish with length)"
chat "$KEY" "Write a long poem about the sea." '"max_tokens":1'; summary "$t"
jq -c '{content: .choices[0].message.content, finish: .choices[0].finish_reason, usage}' "$BODY" | sed 's/^/  /'; wire

step "8. Embeddings through gemini-embedding-2"
post /v1/embeddings "$KEY" '{"model":"gemini-embedding-2","input":["hello world","circuit breakers"]}'; echo "  $(status) ${t}s"
jq -c '{model, usage, vectors: (.data | length), dims: [.data[].embedding | length], first: .data[0].embedding[0:3]}' "$BODY" | sed 's/^/  /'
wire | grep -- '->' || true

step "9. Metering: daily usage and an invoice for the gemini tenant"
TODAY=$(date -u +%F); PERIOD=$(date -u +%Y-%m)
for _ in $(seq 1 30); do
  n=$(curl -s "$METER/admin/usage/gem-$RUN?from=$TODAY&to=$TODAY" -H "Authorization: Bearer $BILLING" | jq '[.days[].requests] | add // 0')
  [ "$n" -ge 8 ] && break
  sleep 1
done
curl -s "$METER/admin/usage/gem-$RUN?from=$TODAY&to=$TODAY" -H "Authorization: Bearer $BILLING" \
  | python3 -c 'import json,sys
for d in json.load(sys.stdin, parse_float=str)["days"]: print("  usage:", " ".join(f"{k}={v}" for k, v in d.items()))'
curl -s -X POST "$METER/admin/invoices" -H "Authorization: Bearer $BILLING" -H "Content-Type: application/json" \
  -d "{\"tenant_id\":\"gem-$RUN\",\"period\":\"$PERIOD\"}" | python3 -c 'import json,sys
inv = json.load(sys.stdin, parse_float=str)
print("  invoice:", inv.get("id"), inv.get("status"), "total_usd=" + str(inv.get("total_usd")))
for l in inv.get("lines", []): print("   ", " ".join(f"{k}={v}" for k, v in l.items()))'

step "Done (run $RUN)"
