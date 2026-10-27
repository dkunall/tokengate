-- KEYS[1] tokens used today   KEYS[2] cost (micros) spent this month
-- ARGV: tokens, cost micros, daily token limit, monthly cap micros, seconds to end of day, seconds to end of month
local tokens = tonumber(ARGV[1])
local cost = tonumber(ARGV[2])
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local spent = tonumber(redis.call('GET', KEYS[2]) or '0')

if used + tokens > tonumber(ARGV[3]) then
  return { 0, 1, used, spent }
end
if spent + cost > tonumber(ARGV[4]) then
  return { 0, 2, used, spent }
end

used = redis.call('INCRBY', KEYS[1], tokens)
spent = redis.call('INCRBY', KEYS[2], cost)
if redis.call('TTL', KEYS[1]) < 0 then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]) + 3600)
end
if redis.call('TTL', KEYS[2]) < 0 then
  redis.call('EXPIRE', KEYS[2], tonumber(ARGV[6]) + 86400)
end
return { 1, 0, used, spent }
