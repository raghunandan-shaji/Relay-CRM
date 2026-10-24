local current = redis.call('HGET', KEYS[1], 'version')
local incoming = ARGV[1]
if current and (#current > #incoming or (#current == #incoming and current > incoming)) then
    return 0
end
redis.call('HSET', KEYS[1], 'version', incoming, 'data', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
