-- KEYS[1] = state
-- ARGV[1] = traceId
-- ARGV[2] = conversation ttl ms
-- ARGV[3] = session shared key（可空）
-- ARGV[4] = baseVersion（Redis 无 version 时用 MySQL state_version）
-- ARGV[5..] = 任务组 key、ttlMs 成对
local oldTrace = redis.call('HGET', KEYS[1], 'lastTraceId')
if oldTrace == false then
  oldTrace = ''
end
redis.call('HSETNX', KEYS[1], 'version', ARGV[4])
local version = redis.call('HINCRBY', KEYS[1], 'version', 1)
redis.call('HSET', KEYS[1], 'lastTraceId', ARGV[1])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('HSET', KEYS[1], 'updatedAt', now)
redis.call('PEXPIRE', KEYS[1], ARGV[2])
if ARGV[3] ~= nil and ARGV[3] ~= '' then
  redis.call('PEXPIRE', ARGV[3], ARGV[2])
end
local i = 5
while i <= #ARGV do
  redis.call('PEXPIRE', ARGV[i], ARGV[i + 1])
  i = i + 2
end
return { version, oldTrace }
