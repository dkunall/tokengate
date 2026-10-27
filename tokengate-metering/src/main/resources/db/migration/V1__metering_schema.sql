-- Dedup table: an event id lands here in the same transaction as its daily_usage increment,
-- so a redelivered event is a no-op rather than a double count.
CREATE TABLE processed_events (
    event_id     uuid PRIMARY KEY,
    tenant_id    text        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE daily_usage (
    tenant_id         text          NOT NULL,
    usage_day         date          NOT NULL,
    model             text          NOT NULL,
    provider          text          NOT NULL,
    requests          bigint        NOT NULL,
    cache_hits        bigint        NOT NULL,
    unpriced_requests bigint        NOT NULL,
    prompt_tokens     bigint        NOT NULL,
    completion_tokens bigint        NOT NULL,
    cost_usd          numeric(20,8) NOT NULL,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, usage_day, model, provider)
);

-- Append-only. A price change is a new row with a later effective_from; old rows stay so past usage
-- keeps the price that applied when it happened.
CREATE TABLE model_prices (
    id                bigserial PRIMARY KEY,
    provider          text          NOT NULL,
    provider_model    text          NOT NULL,
    input_per_1k_usd  numeric(14,8) NOT NULL CHECK (input_per_1k_usd >= 0),
    output_per_1k_usd numeric(14,8) NOT NULL CHECK (output_per_1k_usd >= 0),
    effective_from    timestamptz   NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_model, effective_from)
);

CREATE TABLE invoices (
    id           uuid PRIMARY KEY,
    tenant_id    text          NOT NULL,
    period       char(7)       NOT NULL,
    status       text          NOT NULL CHECK (status IN ('DRAFT', 'ISSUED')),
    total_usd    numeric(20,8) NOT NULL,
    generated_at timestamptz   NOT NULL,
    issued_at    timestamptz,
    UNIQUE (tenant_id, period)
);

CREATE TABLE invoice_lines (
    invoice_id        uuid          NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    line_no           int           NOT NULL,
    model             text          NOT NULL,
    provider          text          NOT NULL,
    requests          bigint        NOT NULL,
    cache_hits        bigint        NOT NULL,
    prompt_tokens     bigint        NOT NULL,
    completion_tokens bigint        NOT NULL,
    cost_usd          numeric(20,8) NOT NULL,
    PRIMARY KEY (invoice_id, line_no)
);
