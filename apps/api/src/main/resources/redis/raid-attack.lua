-- 공격 한 번(006 research R2). KEYS[1] = raid:boss
-- ARGV: 1 보스 ID, 2 회원 ID, 3 쿨다운(ms), 4 지금 시각(epoch ms)
-- 결과: {'MISSING'} | {'STALE'} | {'ENDED'} | {'COOLDOWN', 남은 ms} | {'OK', hp, 내 기여, 참여자 수, 처치 여부, maxHp}
local boss = redis.call('HMGET', KEYS[1], 'id', 'status', 'maxHp')
if not boss[1] then
  return { 'MISSING' }
end
-- 기록에는 새 보스가 있는데 여기에는 지난 보스가 남아 있다. 부르는 쪽이 다시 채운다
if tonumber(boss[1]) < tonumber(ARGV[1]) then
  return { 'STALE' }
end
if boss[1] ~= ARGV[1] or boss[2] ~= 'ALIVE' then
  return { 'ENDED' }
end

-- 쿨다운은 걸려 있으면 거절만 하고 만료를 늘리지 않는다
local cooldown = 'raid:cd:' .. ARGV[2]
local remaining = redis.call('PTTL', cooldown)
if remaining > 0 then
  return { 'COOLDOWN', remaining }
end
redis.call('SET', cooldown, '1', 'PX', ARGV[3])

local contributions = 'raid:contrib:' .. ARGV[1]
local hp = redis.call('HINCRBY', KEYS[1], 'hp', -1)
local mine = redis.call('HINCRBY', contributions, ARGV[2], 1)
redis.call('SADD', 'raid:dirty:' .. ARGV[1], ARGV[2])

local defeated = 0
if hp <= 0 then
  redis.call('HSET', KEYS[1], 'status', 'DEFEATED', 'endedAt', ARGV[4])
  defeated = 1
end
return { 'OK', hp, mine, redis.call('HLEN', contributions), defeated, tonumber(boss[3]) }
