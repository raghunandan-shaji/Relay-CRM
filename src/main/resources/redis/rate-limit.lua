local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local count = tonumber(redis.call('GET', KEYS[1]) or '0')

if count == 0 then
    redis.call('SET', KEYS[1], '1', 'PX', window)
    return {1, limit - 1, window}
end

local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
    redis.call('PEXPIRE', KEYS[1], window)
    ttl = window
end

if count >= limit then
    return {0, 0, math.max(ttl, 1)}
end

count = redis.call('INCR', KEYS[1])
return {1, limit - count, math.max(ttl, 1)}
