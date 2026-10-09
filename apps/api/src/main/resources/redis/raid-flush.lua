-- Postgres로 옮길 것을 꺼낸다(006 research R3). KEYS[1] = raid:boss, ARGV[1] = 한 번에 꺼낼 회원 수
-- 결과: {} (보스 없음) | {보스 ID, hp, 상태, 참여자 수, endedAt, 회원, 기여, 회원, 기여, ...}
local boss = redis.call('HMGET', KEYS[1], 'id', 'hp', 'status', 'endedAt')
if not boss[1] then
  return {}
end
local contributions = 'raid:contrib:' .. boss[1]
local members = redis.call('SPOP', 'raid:dirty:' .. boss[1], ARGV[1])
local result = { boss[1], boss[2], boss[3], redis.call('HLEN', contributions), boss[4] or '' }
if #members > 0 then
  local damages = redis.call('HMGET', contributions, unpack(members))
  for index, member in ipairs(members) do
    result[#result + 1] = member
    result[#result + 1] = damages[index]
  end
end
return result
