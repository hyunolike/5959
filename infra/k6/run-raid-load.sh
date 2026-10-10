#!/usr/bin/env bash
# 보스 레이드 부하 테스트를 처음부터 끝까지 돌린다(006 research R15, SC-001~SC-003).
#
#   1. 살아 있는 보스를 물러나게 하고 HP가 정해진 새 보스를 놓는다
#   2. k6로 가상 사용자들이 1초에 한 번씩 공격한다
#   3. 받아들여진 공격 수를 줄어든 HP, Redis의 기여 합, Postgres의 기여 합과 견준다
#      처치 실행이면 처치 기록이 하나이고 알림이 참여자마다 하나인지도 본다
#
# 로컬에서만 돌린다. 운영 환경에 돌리지 않는다. 보스와 회원이 테스트 데이터로 바뀐다.
#
#   MODE=sustain infra/k6/run-raid-load.sh     # 보스가 버티는 실행: 유실이 없는지와 응답 시간
#   MODE=defeat  infra/k6/run-raid-load.sh     # 보스가 중간에 처치되는 실행: 처치가 한 번인지
#
# 환경 변수: API(기본 http://localhost:8080), VUS(500), DURATION(60s), BOSS_HP(sustain 1000000, defeat 10000),
# PSQL(psql까지의 명령), REDIS_CLI(redis-cli까지의 명령)
set -euo pipefail

MODE="${MODE:-sustain}"
API="${API:-http://localhost:8080}"
VUS="${VUS:-500}"
DURATION="${DURATION:-60s}"
PSQL="${PSQL:-docker compose -f apps/api/compose.yaml exec -T postgres psql -U ogu -d ogu}"
REDIS_CLI="${REDIS_CLI:-docker compose -f apps/api/compose.yaml exec -T redis redis-cli}"
SUMMARY_FILE="${SUMMARY_FILE:-$(mktemp -t raid-load-XXXXXX).json}"

case "$MODE" in
  sustain) BOSS_HP="${BOSS_HP:-1000000}" ;;
  defeat) BOSS_HP="${BOSS_HP:-10000}" ;;
  *)
    echo "MODE는 sustain이나 defeat이어야 합니다: $MODE" >&2
    exit 2
    ;;
esac
if ! [[ "$BOSS_HP" =~ ^[0-9]+$ ]]; then
  echo "BOSS_HP는 숫자여야 합니다: $BOSS_HP" >&2
  exit 2
fi

# 명령은 공백으로 나눈 낱말들이다. 배열로 풀어 쓴다
read -r -a psql_command <<<"$PSQL"
read -r -a redis_command <<<"$REDIS_CLI"

sql() {
  "${psql_command[@]}" -v ON_ERROR_STOP=1 -At -c "$1"
}

redis() {
  "${redis_command[@]}" "$@"
}

json_field() {
  python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$SUMMARY_FILE" "$1"
}

echo "== 보스 놓기 (HP $BOSS_HP)"
sql "update raid_boss set status = 'RETREATED', ended_at = now() where status = 'ALIVE'" >/dev/null
BOSS_ID="$(sql "insert into raid_boss (emotion, max_hp, hp, status, spawned_at) values ('LETHARGY', $BOSS_HP, $BOSS_HP, 'ALIVE', now()) returning id" | head -n 1)"
redis DEL raid:boss >/dev/null
echo "보스 $BOSS_ID"

echo "== k6 ($MODE, 가상 사용자 ${VUS}명, $DURATION)"
k6_status=0
k6 run --quiet -e API="$API" -e VUS="$VUS" -e DURATION="$DURATION" -e SUMMARY_FILE="$SUMMARY_FILE" \
  "$(dirname "$0")/raid-attack.js" || k6_status=$?

# 뒤따라 적기(1초 주기)와 알림 만들기가 끝나기를 기다린다
sleep 5

accepted="$(json_field accepted)"
hp_lost="$(json_field hpLost)"
redis_sum="$(redis HVALS "raid:contrib:$BOSS_ID" | awk '{ sum += $1 } END { print sum + 0 }')"
redis_participants="$(redis HLEN "raid:contrib:$BOSS_ID" | tr -d '[:space:]')"
pg_sum="$(sql "select coalesce(sum(damage), 0) from raid_contribution where boss_id = $BOSS_ID")"
pg_boss="$(sql "select status || ' hp=' || hp || ' participants=' || participant_count from raid_boss where id = $BOSS_ID")"

echo "== 검증"
echo "받아들여진 공격 (k6)        : $accepted"
echo "줄어든 HP (API)             : $hp_lost"
echo "기여의 합 (Redis)           : $redis_sum (회원 ${redis_participants}명)"
echo "기여의 합 (Postgres)        : $pg_sum"
echo "보스 기록 (Postgres)        : $pg_boss"

failures=0
check() {
  if [ "$2" = "$3" ]; then
    echo "  통과: $1"
  else
    echo "  실패: $1 ($2 != $3)"
    failures=$((failures + 1))
  fi
}

check "줄어든 HP = 받아들여진 수" "$hp_lost" "$accepted"
check "Redis의 기여 합 = 받아들여진 수" "$redis_sum" "$accepted"
check "Postgres의 기여 합 = 받아들여진 수" "$pg_sum" "$accepted"

if [ "$MODE" = "defeat" ]; then
  notifications="$(sql "select count(*) from notification where raid_boss_id = $BOSS_ID")"
  duplicated="$(sql "select count(*) from (select receiver_id from notification where raid_boss_id = $BOSS_ID group by receiver_id having count(*) > 1) d")"
  status="$(sql "select status from raid_boss where id = $BOSS_ID")"
  echo "처치 알림                   : ${notifications}건 (같은 회원에게 둘 이상: ${duplicated}명)"
  check "받아들여진 수 = 보스의 HP" "$accepted" "$BOSS_HP"
  check "보스 상태" "$status" "DEFEATED"
  check "처치 알림 수 = 참여자 수" "$notifications" "$redis_participants"
  check "같은 회원에게 알림이 둘 이상 간 일" "$duplicated" "0"
fi

echo "요약 파일: $SUMMARY_FILE"
if [ "$failures" -gt 0 ] || [ "$k6_status" -ne 0 ]; then
  echo "부하 테스트 실패 (검증 실패 ${failures}건, k6 종료 코드 $k6_status)" >&2
  exit 1
fi
echo "부하 테스트 통과"
