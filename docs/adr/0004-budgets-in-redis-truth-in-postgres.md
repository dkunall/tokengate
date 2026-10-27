# 4. Enforce budgets in Redis, keep the truth in Postgres

Status: accepted

## Context

A daily token budget and a monthly cost cap have to be checked before the upstream call, but the real token count is
only known after it. Checking against Postgres aggregates on every request would be slow and would trail behind
the outbox.

## Decision

The gateway counts the masked prompt with jtokkit (cl100k) and adds `max_tokens`, or a default completion allowance
when `max_tokens` isn't set. It reserves that amount, plus the estimated cost from the primary provider's price, in
Redis with one Lua script (`budget_reserve.lua`). The script rejects the request if either limit would be crossed and
otherwise increments both counters atomically. Once the call finishes, `budget_adjust.lua` moves the counters by the
difference between the reservation and the actual usage the provider reported. If the call fails, the whole
reservation is released. The keys expire an hour after the end of their day, or a day after the end of their month.

The metering service's `daily_usage` table in Postgres is the system of record for billing.

## Consequences

- Concurrent requests can't jointly overshoot: each one reserves before it runs, and the script is atomic.
- The Redis counters and Postgres can drift. Causes include fail-open windows (ADR 3), a gateway dying between
  reserve and settle (the reservation stays counted until the key expires, which errs on the safe side), and
  different rounding: the gateway works in whole micro-dollars and metering keeps the exact decimal. The request
  journey shows 17 micros against 0.00001665 USD. There is no reconciliation job that resets Redis from Postgres
  yet.
- Pre-estimation is only as good as cl100k is for the target model. For Gemini and Claude it is an approximation,
  which is why every reservation is settled to the provider's reported usage.
- In the demo, a 150-token budget let three 32-token calls through and rejected the fourth, which needed a
  reservation larger than the 54 tokens left.
