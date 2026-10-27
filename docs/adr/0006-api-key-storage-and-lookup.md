# 6. API keys: HMAC with a pepper, prefix lookup, short cache

Status: accepted

## Context

Every `/v1` request carries an API key. The gateway has to find the tenant fast, must never store the key itself,
and has to stop honouring a revoked key reasonably quickly.

## Decision

Keys look like `tg_<8-char prefix>_<32-char secret>`, both base62 and from `SecureRandom`, which gives about 190 bits
of secret. The database stores the prefix in plain text (unique and indexed) and `HMAC-SHA256(pepper, secret)`. The
pepper comes from `TOKENGATE_API_KEY_PEPPER` and never touches the database. The full key is returned exactly once,
on creation.

When a request comes in, the gateway looks up the row by prefix and compares hashes with `MessageDigest.isEqual`.
Prefix-to-key lookups are cached in process for 30 s. The hash comparison still runs on every request.

I didn't use bcrypt or argon2. They exist to slow down guessing of low-entropy passwords. With 190 random bits
there's nothing to guess, and a slow hash would add tens of milliseconds to every call.

The admin API is a separate security chain: an OAuth2 resource server that validates JWTs, with `ADMIN`, `VIEWER`
and `BILLING` roles. Locally it uses an HS256 dev secret, or `TOKENGATE_JWT_ISSUER_URI` for a real issuer.

## Consequences

- A leaked database dump doesn't yield usable keys without the pepper.
- Revocation takes effect within the cache TTL (30 s) on each gateway instance, not instantly. Making it instant
  would need a Redis pub/sub invalidation, which I haven't built.
- Rotating the pepper invalidates every key. There's no dual-pepper rotation yet.
