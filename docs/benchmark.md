# Benchmark

These are single-machine dev numbers. k6, the gateway, the metering service, the mock provider, Postgres, Redis,
Kafka, Prometheus and Grafana all ran on one 8 vCPU / 16 GB Linux box that also had another project's containers on it.
The load average went past 20 during the runs, so k6 was competing with the gateway for CPU. Read these as relative
numbers for comparing code paths. They don't tell you capacity on real hardware, and they never touch a real LLM.

Run on 2026-10-27 (about 22:15 IST) with `bench/run.sh`. The raw k6 output is written to `/workspace/tokengate-run/bench/`.

## Setup

- k6 v0.54.0, 20 VUs, 30 s per scenario, after a 10 s warm-up that isn't counted.
- Non-streaming `POST /v1/chat/completions` at `temperature: 0`.
- The tenant limits are set high enough (600k rpm, 1B daily tokens) that the limiter always admits. The Redis Lua
  script still runs on every request.
- Embeddings come from all-MiniLM-L6-v2 on ONNX Runtime (CPU). Tokens are counted with jtokkit cl100k.
- Unless a row says otherwise, the mock provider's latency is set to 0, so the numbers show what the gateway itself costs.

| Scenario | What a request does | RPS | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|---|
| L1 hit | same prompt every time; served from the in-process LRU | 4,072 | 4.25 ms | 8.56 ms | 13.02 ms | 0 |
| Semantic hit | near-duplicate prompt; Redis exact miss, embed, pgvector hit | 940 | 20.0 ms | 33.2 ms | 42.1 ms | 0 |
| Full miss | random prompt; embed, pgvector miss, provider call, Redis + pgvector write, outbox insert | 208 | 85.8 ms | 184 ms | 230 ms | 0 |
| Full miss, mock latency on | as above, mock alpha 120±30 ms / beta 40±10 ms | 105 | 182 ms | 265 ms | 312 ms | 0 |

The L1 run's cache-header check failed on 20 of 122,164 requests. Those were the first request from each VU, which
raced to populate the cache, so they came back as `miss` or Redis `hit`. The semantic run had 3 such misses.

## What the benchmark found

The first full-miss run did 78 RPS at a p50 of 234 ms, even though the mock was answering in about 0 ms. Thread dumps
showed most request threads inside `PgVectorSemanticCache.nearest`. `EXPLAIN ANALYZE` on the prepared, generic-plan
form of the query took 228 ms over 3.8k rows. Written inline as `?::vector`, the probe was being re-parsed from its
384-float text form for every candidate row. Moving the parse into a materialised CTE brought the same query down to
26 ms. On the next run the miss path did 208 RPS at a p50 of 86 ms.

| Full miss (mock latency 0) | RPS | p50 | p95 |
|---|---|---|---|
| before the fix | 77.6 | 234 ms | 531 ms |
| after the fix | 208 | 85.8 ms | 184 ms |

## What's still slow, and why

The miss path still slows down as one tenant's cache grows. With `tenant_id`/`context_key` in the WHERE clause, the
planner picks the btree scope index and sorts every row in scope by distance. It doesn't use the HNSW index. That makes
the lookup an exact kNN, which is correct but linear: by the end of the run the strict tenant had about 9.4k rows. The
fixes I'd try next are pgvector 0.8's iterative index scans (`hnsw.iterative_scan`), partial HNSW indexes per large
tenant, or a cap on rows per tenant. I haven't measured any of them yet.

Streaming isn't part of this benchmark.
