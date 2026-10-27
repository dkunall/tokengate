# 2. A transactional outbox for usage events

Status: accepted

## Context

Billing has to see every request the gateway served, exactly once in effect. If the gateway published straight to
Kafka after answering, it would lose events whenever Kafka was down or the process died between the response and the
send. Publishing before answering would put Kafka latency, and Kafka outages, on every request.

## Decision

In one local transaction, the gateway writes a `request_log` row and an `outbox` row (`JdbcUsageRecorder`).
`OutboxRelay` polls the outbox every second in batches of up to 200, sends each batch to `tokengate.usage.v1` keyed
by tenant, waits for the broker ack, and then marks the rows published. Each payload carries a `schemaVersion`.

The metering service commits offsets per record, after the listener returns. It inserts the event id into `processed_events` and upserts
`daily_usage` in the same transaction, so a redelivered event changes nothing. When the consumer sees a schema
version it doesn't understand, it logs the event and skips it.

## Consequences

- Delivery is at least once from the gateway and effectively once in metering. A crash between the broker ack and
  the "published" update re-sends the batch, and the dedup table absorbs it.
- Keying by tenant keeps one tenant's events in order without a global order.
- Usage reaches metering with a delay: in normal runs about half a second (one poll interval plus the send). Under
  the k6 backlog it was about 20 s. Budgets are enforced from Redis in real time (ADR 4), so this delay never lets
  anyone overspend.
- There's no dead-letter topic yet. A record that can't be deserialised is logged and skipped. I'd want a DLT
  before real money depended on this.
- The outbox table grows. Published rows need a retention job, which I haven't written.
