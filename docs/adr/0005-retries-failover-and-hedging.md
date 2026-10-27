# 5. Retry only what provably didn't run; hedging is opt-in

Status: accepted

## Context

A chat completion isn't idempotent from a billing point of view. If a provider timed out after it had already run
the model, asking it again means paying twice. Failover and hedging both trade money for availability, so the
rules need to be explicit.

## Decision

`FailureKind` decides the rule for each failure:

| Failure | Retry same provider | Fail over | Counts against breaker |
|---|---|---|---|
| connect refused | yes | yes | yes |
| 429 | yes | yes | no |
| 503 | yes | yes | yes |
| timeout / 504 | no | yes | yes |
| other 5xx | no | yes | yes |
| 401/403 | no | yes | yes |
| other 4xx | no | no | no |
| mid-stream break | no | no (bytes already sent) | yes |

Same-provider retries use full-jitter exponential backoff (100 ms base, 2 s cap, at most 2 attempts per provider).
Each provider has a count-based circuit breaker: it opens when 50% of the last 20 calls fail, once at least 5 have
been recorded, and stays open for 15 s before half-open trials. A provider whose breaker is open is left out of the
routing plan.

For a stream, failover is allowed only until the first content byte reaches the client. After that, the gateway sends
an SSE error event without `[DONE]` and bills what was streamed.

Hedging (`TOKENGATE_HEDGE_AFTER`, unset by default) starts the second provider in the plan if the first one hasn't
answered after the delay. Whichever answers first wins and the other call is cancelled.

## Consequences

- Ambiguous failures can cost one extra upstream call on a different provider. That's the price of answering at
  all.
- A hedged request can be charged by both providers if the loser had already started generating. The tenant is
  billed only for the winner, so the gateway owner absorbs the difference. That's why hedging is off unless set,
  and it's meant for latency-sensitive tenants on cheap models.
- In the demo outage (`/workspace/tokengate-run/demo-output.txt`), alpha returned 503 to everything. Each of the first five
  requests took 3 attempts: alpha, an alpha retry, then beta. That's 10 failures. Alpha's breaker window still held
  about 10 successes from earlier traffic, so the failure rate reached 50% of the last 20 calls only then, and the
  breaker opened. Requests 6 to 8 went straight to beta with 1 attempt. Before the k6 runs, the window had different
  history, and the breaker was open by the second request.
