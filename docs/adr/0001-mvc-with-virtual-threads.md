# 1. Spring MVC on virtual threads, not WebFlux

Status: accepted

## Context

Almost every request to the gateway spends most of its time waiting on something: an upstream model that takes
hundreds of milliseconds to seconds, a stream that stays open for as long as the model keeps generating, plus Redis,
Postgres and pgvector along the way. The obvious way to hold thousands of slow calls open is a reactive stack
(WebFlux, Reactor Netty, R2DBC).

## Decision

I used Spring MVC with `spring.threads.virtual.enabled=true` on Java 21. Each request gets its own virtual thread and
the code is plain blocking code: JdbcClient, the JDK HttpClient, Lettuce through the sync API.

For SSE, backpressure is just a blocking flush. `SseStreamSink` writes and flushes each chunk. If the client reads
slowly, the socket buffer fills, `flush()` blocks, and `StreamSession` stops pulling from the upstream reader. A
client disconnect shows up as an `IOException` on the next write. At that point the session closes the upstream
connection, bills the tokens streamed so far, and releases the rest of the budget reservation.

## Consequences

- Stack traces, transactions, `ThreadLocal`-based MDC and debugging all keep working as usual. The failover and
  hedging code reads top to bottom.
- JDBC drivers stay fine. Postgres has no production-ready R2DBC pgvector story, and I didn't want two data access
  styles.
- Virtual threads pin their carrier inside `synchronized` blocks that do I/O. I kept I/O out of synchronized
  sections; the L1 cache lock and the router lock only guard in-memory work.
- Cancelling the upstream is cooperative. It happens at the next write, so a client that disconnects while the
  model is "thinking" before its first token only gets noticed when that first token arrives. WebFlux would
  propagate the cancel immediately.
- The Hikari pool (20 by default) is now the real concurrency limit for anything that touches Postgres. The k6 miss
  path is bound by the pgvector query, not by threads.
