-- Applies (actual - reserved) to both counters. Release is the same call with actual = 0.
-- KEYS[1] tokens today   KEYS[2] cost this month   ARGV: token delta, cost delta
-- A key that expired between reserve and settle (call spanning midnight) is left alone instead of going negative.
if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
end
if redis.call('EXISTS', KEYS[2]) == 1 then
  redis.call('INCRBY', KEYS[2], tonumber(ARGV[2]))
end
return 1
