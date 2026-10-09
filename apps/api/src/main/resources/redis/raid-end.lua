-- 살아 있는 보스를 끝낸다(물러남, 006 research R5). KEYS[1] = raid:boss
-- ARGV: 1 보스 ID, 2 새 상태, 3 지금 시각(epoch ms). 결과: 1 끝냄, 0 이미 끝났거나 다른 보스
local boss = redis.call('HMGET', KEYS[1], 'id', 'status')
if boss[1] ~= ARGV[1] or boss[2] ~= 'ALIVE' then
  return 0
end
redis.call('HSET', KEYS[1], 'status', ARGV[2], 'endedAt', ARGV[3])
return 1
