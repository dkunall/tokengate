# One request, end to end

This is a real request I sent to the gateway running locally on 2026-10-27, followed through every table and topic
it touched. All the values below are copied from the actual output. I trimmed the API key to its lookup prefix and
left the admin JWT out. Times in the database rows are UTC; that run was at about 22:16 IST.

## 1. Tenant and key

```
POST /admin/tenants            (Bearer <ADMIN JWT>)
{"id":"journey-9603","name":"Request journey","routing":"priority"}
```

Everything I didn't set came back with its default:

```json
{"id":"journey-9603","name":"Request journey","requests_per_minute":60,"burst":60,"daily_tokens":1000000,
 "monthly_cost_cap_usd":50.000000,"routing":"PRIORITY",
 "cache":{"enabled":true,"semantic_enabled":true,"semantic_threshold":0.9,"ttl_seconds":3600,
          "cache_streamed":false,"cache_non_deterministic":false},
 "pii":{"mode":"TOKENIZE","custom_term_count":0},"active":true,"created_at":"2026-10-27T16:46:43.353761673Z"}
```

```
POST /admin/tenants/journey-9603/keys   {"name":"journey"}
```

```json
{
  "id": "8f94dfdf-a3f9-4cc1-8f46-a62415eb60f1",
  "tenant_id": "journey-9603",
  "prefix": "tLALH9S1",
  "name": "journey",
  "created_at": "2026-10-27T16:46:43.367904917Z",
  "key": "tg_tLALH9S1_…trimmed",
  "note": "Store this key now. It is not shown again."
}
```

Only the prefix and an HMAC-SHA256 of the secret are written to `api_keys`. When a request comes in, the gateway
looks the key up by its prefix and compares the hash in constant time.

## 2. The request

```
POST /v1/chat/completions
Authorization: Bearer tg_tLALH9S1_…
X-Request-Id: req_journey_01
```

```json
{
  "model": "gpt-4o-mini",
  "temperature": 0,
  "messages": [
    {"role": "system", "content": "You are a support assistant for a payments app."},
    {"role": "user", "content": "Hi, I am Kunal, reach me at kunal.journey@example.com. Why was my card 4111 1111 1111 1111 charged twice?"}
  ]
}
```

## 3. What happened inside the gateway, in order

1. `ApiKeyAuthenticationFilter` resolves the key to tenant `journey-9603`. The prefix lookup is cached for 30 s;
   the hash check runs on every request.
2. `Admission.admit`: the Redis Lua token bucket takes one token, leaving 59 of 60.
3. `PromptGuard` masks the prompt. The scanner found an email and a card number; the card passed Luhn. Each one is
   replaced with an HMAC token scoped to this tenant, and a per-request `MaskingSession` keeps the reverse mapping.
4. jtokkit (cl100k) counts the masked prompt's tokens. That count is used for the budget reservation.
5. The cache lookup runs on the masked prompt: L1 missed, Redis exact missed, then MiniLM embedded the prompt and
   pgvector found nothing at or above 0.9. Result: `miss`.
6. Routing: the tenant uses `PRIORITY`. Both mock providers had closed breakers, so the plan was
   `[mock-alpha, mock-beta]`. The budget ledger in Redis reserved the prompt tokens plus the default completion
   allowance.
7. `FailoverExecutor` called mock-alpha, which succeeded on the first attempt.
8. The reservation was settled to the actual usage the provider reported. The masked response went into all three
   cache tiers.
9. In one transaction, `JdbcUsageRecorder` wrote a `request_log` row and an `outbox` row.
10. The response was unmasked for this caller only and returned.

## 4. What the provider saw

The mock records what it receives (`GET /control/alpha/requests`):

```json
[{"at":"2026-10-27T16:46:43.408418586Z","endpoint":"chat","model":"mock-alpha-small",
  "contents":["system: You are a support assistant for a payments app.",
              "user: Hi, I am Kunal, reach me at <EMAIL_6cad145c>. Why was my card <CARD_3f7eb0eb> charged twice?"],
  "stream":false}]
```

The email address and card number never left the gateway.

## 5. The response

```
HTTP/1.1 200
X-Request-Id: req_journey_01
RateLimit-Limit: 60
RateLimit-Remaining: 59
RateLimit-Reset: 1
X-TokenGate-Provider: mock-alpha
X-TokenGate-Cache: miss
X-TokenGate-Attempts: 1
X-TokenGate-Pii-Masked: 2
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Type: application/json
```

```json
{"id":"chatcmpl-req_journey_01","object":"chat.completion","created":1793119603,"model":"gpt-4o-mini",
 "choices":[{"index":0,"message":{"role":"assistant",
   "content":"[alpha/mock-alpha-small] You asked: Hi, I am Kunal, reach me at kunal.journey@example.com. Why was my card 4111 1111 1111 1111 charged twice?"},
   "finish_reason":"stop"}],
 "usage":{"prompt_tokens":39,"completion_tokens":18,"total_tokens":57}}
```

The mock echoes the prompt back. It received the tokens, and the gateway swapped them back to the original values on
the way out.

## 6. Gateway database (`tokengate`)

`request_log`:

```
id                    | 569038
request_id            | req_journey_01
tenant_id             | journey-9603
model                 | gpt-4o-mini
provider              | mock-alpha
provider_model        | mock-alpha-small
prompt_tokens         | 39
completion_tokens     | 18
estimated_cost_micros | 17
latency_ms            | 186
cache                 | MISS
outcome               | OK
created_at            | 2026-10-27 16:46:43.571633+00
```

`outbox`, written in the same transaction:

```
id            | 569038
event_id      | b07f500b-746a-46a7-9408-82f50d396cb3
partition_key | journey-9603
payload       | {"cache": "MISS", "model": "gpt-4o-mini", "eventId": "b07f500b-746a-46a7-9408-82f50d396cb3",
                 "outcome": "OK", "apiKeyId": "8f94dfdf-a3f9-4cc1-8f46-a62415eb60f1", "provider": "mock-alpha",
                 "streamed": false, "tenantId": "journey-9603", "latencyMs": 186, "requestId": "req_journey_01",
                 "occurredAt": "2026-10-27T16:46:43.571632657Z", "promptTokens": 39,
                 "providerModel": "mock-alpha-small", "schemaVersion": 1, "completionTokens": 18}
created_at    | 2026-10-27 16:46:43.572339+00
published_at  | 2026-10-27 16:46:44.109962+00
```

`OutboxRelay` polls once a second. It published this row to `tokengate.usage.v1` about 0.54 s after commit, keyed by
tenant so that one tenant's events stay in order on a single partition.

## 7. Metering (`tokengate_metering`)

The event reached metering about 20 s after it was published. I had just finished the k6 benchmark, so
partition 2 still had 16,905 events of lag at 16:46:53 UTC. By 16:47:12 UTC the lag was 0 on all three partitions.

`processed_events` is the idempotency guard. The insert into this table and the daily upsert happen in one
transaction, so a redelivered event is a no-op:

```
event_id     | b07f500b-746a-46a7-9408-82f50d396cb3
tenant_id    | journey-9603
occurred_at  | 2026-10-27 16:46:43.571633+00
processed_at | 2026-10-27 16:47:04.383483+00
```

`daily_usage`:

```
tenant_id         | journey-9603
usage_day         | 2026-10-27
model             | gpt-4o-mini
provider          | mock-alpha
requests          | 1
cache_hits        | 0
unpriced_requests | 0
prompt_tokens     | 39
completion_tokens | 18
cost_usd          | 0.00001665
```

The cost comes from the price version in effect on the event's date. For mock-alpha-small that is $0.00015 per 1k
input and $0.0006 per 1k output tokens: 39 × 0.00015/1000 + 18 × 0.0006/1000 = 0.00001665. The gateway's own
estimate, 17 micro-dollars, is the same number rounded to whole micros. Metering keeps the exact value, and invoices
are built from it.

## What this trace doesn't show

- The gateway writes no INFO log line per request, so `grep req_journey_01` on the gateway log finds nothing.
  The request id shows up in the `X-Request-Id` header, in `request_log`, in the event payload and as the
  provider-side completion id. Traces go to OTel when an OTLP endpoint is configured; none was configured in this run.
