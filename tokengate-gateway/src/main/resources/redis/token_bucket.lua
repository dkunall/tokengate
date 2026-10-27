-- Same arithmetic as domain.ratelimit.TokenBucket. Uses the Redis clock so gateway instances with skewed clocks
-- share one notion of time.
-- KEYS[1] bucket hash   ARGV: refill per second, burst, cost
local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1]) or burst
local ts = tonumber(state[2]) or now

tokens = math.min(burst, tokens + math.max(0, now - ts) / 1000000 * rate)

local allowed = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
end

local retry_ms = 0
if allowed == 0 then
  retry_ms = math.ceil((cost - tokens) / rate * 1000)
end
local reset_ms = math.ceil((burst - tokens) / rate * 1000)

-- string.format keeps the microsecond timestamp exact; the default number conversion would round it
redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', string.format('%d', now))
redis.call('PEXPIRE', KEYS[1], reset_ms + 1000)

return { allowed, math.floor(tokens), retry_ms, reset_ms }
