# 3. Fail open when Redis is down

Status: accepted

## Context

Redis sits on the hot path three times: the rate limiter, the budget ledger and the exact cache. If any of those
failed closed, a Redis blip would turn into a 5xx for every tenant, even though the gateway, the providers and
Postgres were all fine.

## Decision

All three fail open. `Admission` admits the request when the token bucket script or the budget script throws, and
logs a warning. `ResponseCache` treats a Redis error, an embedding error or a pgvector error as a miss. The Redis
client has a 500 ms command timeout and a 1 s connect timeout, so a dead Redis costs at most that much on each
request rather than hanging it.

## Consequences

- Availability wins over enforcement during a Redis outage. During that window a tenant can go over its rate
  limit or its daily budget.
- How much can be lost is bounded. Every served request still writes `request_log` and an outbox event, so
  metering, and the invoice built from it, sees the true usage. The overspend shows up on the bill, not as free use.
- A cache outage costs money (more upstream calls), not correctness.
- The opposite choice would make sense for a tenant on a hard prepaid balance. That would be a per-tenant flag. It
  isn't built.
