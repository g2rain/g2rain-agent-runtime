-- KEYS[1] = state
-- ARGV[1] = 本轮栅栏版本
-- ARGV[2] = 改动列表 JSON：[{op, key, field, value, ttl}]
local current = redis.call('HGET', KEYS[1], 'version')
if (not current) or (tostring(current) ~= tostring(ARGV[1])) then
  return 0
end
local ops = cjson.decode(ARGV[2])
if type(ops) ~= 'table' then
  return redis.error_reply('flush ops must be array')
end
local function has_ttl(op)
  return op.ttl and op.ttl ~= cjson.null
end
for _, op in ipairs(ops) do
  local name = op.op
  if name == 'HSET' then
    redis.call('HSET', op.key, op.field, op.value)
    if has_ttl(op) then
      redis.call('PEXPIRE', op.key, op.ttl)
    end
  elseif name == 'HDEL' then
    redis.call('HDEL', op.key, op.field)
  elseif name == 'SET' then
    if has_ttl(op) then
      redis.call('SET', op.key, op.value, 'PX', op.ttl)
    else
      redis.call('SET', op.key, op.value)
    end
  elseif name == 'DEL' then
    redis.call('DEL', op.key)
  elseif name == 'LPUSH_TRIM' then
    redis.call('LPUSH', op.key, op.value)
    if has_ttl(op) then
      redis.call('LTRIM', op.key, 0, tonumber(op.ttl) - 1)
    end
  elseif name == 'EXPIRE' then
    redis.call('PEXPIRE', op.key, op.ttl)
  else
    return redis.error_reply('unknown flush op: ' .. tostring(name))
  end
end
return 1
