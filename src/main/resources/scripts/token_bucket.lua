-- Atomic token bucket rate limiter.
--
-- KEYS[1]  bucket key, e.g. openmesh:rl:tenant:acme
-- ARGV[1]  replenish rate (tokens added per second)
-- ARGV[2]  burst capacity (maximum tokens in the bucket)
-- ARGV[3]  tokens requested by this call (default 1)
--
-- Returns { allowed (1|0), remaining tokens, seconds }
--   seconds = time until the bucket is full again (allowed)
--           = time until enough tokens are available (rejected)
--
-- The clock comes from Redis itself so every gateway instance shares one time source.

local key = KEYS[1]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

if rate == nil or rate < 0 then rate = 0 end
if capacity == nil or capacity <= 0 then capacity = 1 end
if requested == nil or requested <= 0 then requested = 1 end

local time = redis.call('TIME')
local now = tonumber(time[1]) + (tonumber(time[2]) / 1000000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local last = tonumber(state[2])

if tokens == nil or last == nil then
    tokens = capacity
else
    local elapsed = math.max(0, now - last)
    tokens = math.min(capacity, tokens + elapsed * rate)
end

local allowed = 0
local seconds = 0

if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    if rate > 0 then
        seconds = math.ceil((capacity - tokens) / rate)
    end
else
    if rate > 0 then
        seconds = math.max(1, math.ceil((requested - tokens) / rate))
    else
        seconds = 60
    end
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))

-- Idle buckets expire once they would have refilled completely (minimum 60s).
local ttl = 60
if rate > 0 then
    ttl = math.max(60, math.ceil(capacity / rate) * 2)
end
redis.call('EXPIRE', key, ttl)

return { allowed, math.floor(tokens), seconds }
