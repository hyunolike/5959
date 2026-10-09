-- 기록에서 읽은 보스를 올린다(006 research R4, R10). 기여는 부르는 쪽이 먼저 raid:contrib:{id}에 채워 둔다.
-- KEYS[1] = raid:boss
-- ARGV: 1 보스 ID, 2 감정, 3 maxHp, 4 hp, 5 상태, 6 spawnedAt(ms), 7 endedAt(ms 또는 ''), 8 epoch
-- 결과: 1 올림, 0 같은 보스나 더 새 보스가 이미 있음
local current = redis.call('HGET', KEYS[1], 'id')
if current and tonumber(current) >= tonumber(ARGV[1]) then
  return 0
end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'id', ARGV[1], 'emotion', ARGV[2], 'maxHp', ARGV[3], 'hp', ARGV[4],
  'status', ARGV[5], 'spawnedAt', ARGV[6], 'endedAt', ARGV[7], 'epoch', ARGV[8])
return 1
