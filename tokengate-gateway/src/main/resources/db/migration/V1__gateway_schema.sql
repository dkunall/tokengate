create extension if not exists vector;

create table tenants (
    id                        text primary key,
    name                      text        not null,
    rate_per_minute           bigint      not null,
    rate_burst                bigint      not null,
    daily_tokens              bigint      not null,
    monthly_cost_cap_micros   bigint      not null,
    routing_policy            text        not null,
    cache_enabled             boolean     not null,
    semantic_enabled          boolean     not null,
    semantic_threshold        double precision not null,
    cache_ttl_seconds         bigint      not null,
    cache_streamed            boolean     not null,
    cache_non_deterministic   boolean     not null,
    pii_mode                  text        not null,
    pii_custom_terms          text[]      not null default '{}',
    active                    boolean     not null default true,
    created_at                timestamptz not null,
    updated_at                timestamptz not null default now()
);

create table api_keys (
    id           uuid primary key,
    tenant_id    text        not null references tenants (id),
    prefix       char(8)     not null unique,
    secret_hash  char(64)    not null,
    name         text,
    created_at   timestamptz not null,
    revoked_at   timestamptz
);
create index api_keys_tenant on api_keys (tenant_id);

-- runtime overrides for providers declared in configuration
create table provider_settings (
    provider_id  text primary key,
    weight       int         not null,
    priority     int         not null,
    enabled      boolean     not null,
    updated_at   timestamptz not null default now()
);

create table request_log (
    id                     bigserial primary key,
    request_id             text        not null,
    tenant_id              text        not null,
    api_key_id             text,
    model                  text        not null,
    provider               text,
    provider_model         text,
    prompt_tokens          bigint      not null,
    completion_tokens      bigint      not null,
    estimated_cost_micros  bigint      not null,
    cache                  text        not null,
    streamed               boolean     not null,
    latency_ms             bigint      not null,
    outcome                text        not null,
    attempts               int         not null,
    pii_masked             int         not null,
    created_at             timestamptz not null
);
create index request_log_tenant_time on request_log (tenant_id, created_at desc);
create index request_log_request on request_log (request_id);

create table outbox (
    id            bigserial primary key,
    event_id      uuid        not null unique,
    partition_key text        not null,
    payload       jsonb       not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz,
    attempts      int         not null default 0,
    last_error    text
);
create index outbox_pending on outbox (id) where published_at is null;

create table semantic_cache (
    id            bigserial primary key,
    tenant_id     text         not null,
    context_key   char(64)     not null,
    prompt        text         not null,
    embedding     vector(384)  not null,
    completion    jsonb        not null,
    created_at    timestamptz  not null default now(),
    expires_at    timestamptz  not null
);
create index semantic_cache_scope on semantic_cache (tenant_id, context_key);
create index semantic_cache_expiry on semantic_cache (expires_at);
create index semantic_cache_embedding on semantic_cache using hnsw (embedding vector_cosine_ops);
