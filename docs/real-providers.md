# Real provider check

Tested against the real Gemini API on 2026-10-27, evening IST, with a free-tier key. Model:
`gemini-3.5-flash-lite` for chat, `gemini-embedding-2` for embeddings. About 28 real requests in total.
OpenAI and Anthropic haven't been tried with real keys yet; those adapters are still only checked
against recorded formats.

To rerun it: start the stack, export `GEMINI_API_KEY` for the gateway, then
`scripts/real-provider-check.sh`. It skips if the key isn't set and never reads the value itself.
Trimmed output of the last run is kept outside the repo (`tokengate-run/real-gemini-output.txt`).

## What was checked

| step | result | latency |
|---|---|---|
| non-streaming chat | "Paris", usage 14 + 1 from `usageMetadata` (jtokkit guessed 24 prompt tokens), 7 micro-dollars | 0.85 s |
| streaming | OpenAI-style chunks, last one has usage 13/13, then `[DONE]` | 0.88 s |
| semantic cache | paraphrase served from cache, similarity 0.999, no second Gemini call | 43.7 s (Gemini was slow on that one), hit in 0.03 s |
| PII | email, phone, PAN, card and Aadhaar left as placeholders in the outbound body (wire log), restored in the reply | 0.64 s |
| failover, normal | gemini first, served | 0.69 s |
| failover, unknown model at Google | 404, then mock-beta | 0.23 s |
| failover, dead base URL | connect error, retried, then mock-beta | 0.25 s |
| everything failing | 502 problem+json, attempts `gemini-badmodel:not_found` | 0.15 s |
| `max_tokens: 1` | Google sends `MAX_TOKENS` and no `candidatesTokenCount`; we return `length`, 0 completion tokens | 0.64 s |
| embeddings | 2 x 3072 dims; Google returns no usage, so the gateway counts locally | 0.36 s |
| metering | daily usage rows and a draft invoice match the per-request costs | - |

Prices: $0.30 in / $2.50 out per 1M tokens for 3.5 Flash-Lite and $0.20 per 1M for embedding-2,
from https://ai.google.dev/gemini-api/docs/pricing. Added to the gateway config and to metering.

## What broke

- The configured `gemini-2.0-flash` is retired, and `gemini-2.5-flash-lite` says "no longer available to new
  users". Default is now `gemini-3.5-flash-lite`, overridable with `TOKENGATE_GEMINI_CHAT_MODEL`.
- An upstream 404 counted as a bad request, so it never failed over. It's now `NOT_FOUND`: no retry on the
  same provider, but it fails over and counts towards the breaker.
- The problem detail carried the provider's raw multi-line error body. It now says which provider failed
  and uses Google's `error.message` on one line.
- `thoughtsTokenCount` wasn't billed and thought parts would have ended up in the content. Fixed and covered by
  a test, but 3.5 Flash-Lite never sent thinking tokens in these runs, so it's not checked live.
- The test suite picked up `GEMINI_API_KEY` from the shell and one e2e test hit the real API. Surefire now
  drops provider keys from the test environment.
- The WireMock fixtures were made up; they now use the real response shapes (thought signatures, the empty
  final stream chunk, `MAX_TOKENS` with no candidate count, Google's 404 body).

The key goes in the `x-goog-api-key` header, never in the URL. The wire log (`tokengate.provider.wire`, off by
default) logs path and body only.

## Not covered

- OpenAI and Anthropic with real keys
- safety-blocked prompts (only the recorded fixture)
- 429 handling against Google; we never hit the free-tier limit
- streaming accounting prices by the configured model, non-streaming by the model the provider reports. Same
  thing for Gemini, but a dated OpenAI model name could end up unpriced
- embeddings responses don't carry the `X-TokenGate-*` headers
