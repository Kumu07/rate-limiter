-- token_bucket.lua
-- Atomically refills and consumes tokens from a per-client bucket stored as a Redis Hash.
--
-- KEYS[1] = bucket key, e.g. "rate_limit:client-42"
-- ARGV[1] = bucket capacity            (max tokens, integer)
-- ARGV[2] = refill rate                (tokens per second, float)
-- ARGV[3] = current timestamp in ms    (long)
-- ARGV[4] = tokens requested for this call (usually 1)
-- ARGV[5] = key TTL in seconds
--
-- Returns: { allowed (1/0), tokens_remaining (float, rounded), retry_after_ms (0 if allowed) }

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refill_rate    = tonumber(ARGV[2])
local now            = tonumber(ARGV[3])
local requested      = tonumber(ARGV[4])
local ttl_seconds    = tonumber(ARGV[5])

local bucket = redis.call('HMGET', key, 'tokens', 'timestamp')
local tokens = tonumber(bucket[1])
local last_ts = tonumber(bucket[2])

-- First request from this client: start with a full bucket
if tokens == nil or last_ts == nil then
    tokens = capacity
    last_ts = now
end

-- Refill based on elapsed time since the last request (lazy refill, no cron/background job needed)
local elapsed_seconds = math.max(0, (now - last_ts) / 1000.0)
local refill_amount = elapsed_seconds * refill_rate
tokens = math.min(capacity, tokens + refill_amount)

local allowed = 0
local retry_after_ms = 0

if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
else
    -- Compute how long until enough tokens will be available
    local deficit = requested - tokens
    retry_after_ms = math.ceil((deficit / refill_rate) * 1000)
end

redis.call('HMSET', key, 'tokens', tokens, 'timestamp', now)
redis.call('EXPIRE', key, ttl_seconds)

return { allowed, tostring(tokens), retry_after_ms }
