-- 지금의 보스를 한 번에 읽는다(006 research R7). KEYS[1] = raid:boss
-- 결과: {} (보스 없음) | {id, emotion, maxHp, hp, status, spawnedAt, endedAt, epoch, 참여자 수}
local boss = redis.call('HMGET', KEYS[1], 'id', 'emotion', 'maxHp', 'hp', 'status', 'spawnedAt', 'endedAt', 'epoch')
if not boss[1] then
  return {}
end
boss[7] = boss[7] or ''
boss[8] = boss[8] or '0'
boss[9] = redis.call('HLEN', 'raid:contrib:' .. boss[1])
return boss
