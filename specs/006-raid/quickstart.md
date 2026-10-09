# Quickstart: 보스 레이드 검증 (006-raid)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/raid.openapi.yaml](contracts/raid.openapi.yaml)(구현 후에는 저장소 루트 `contracts/openapi.yaml`), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다.

## 준비

```bash
# API: Redis가 있어야 레이드가 열린다. local 프로필의 compose에 들어 있다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e OGU_SSE_ALLOWED_ORIGINS=http://localhost:3000 ./gradlew bootRun

# 웹
API_ORIGIN=http://localhost:8080 SSE_PUBLIC_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key \
  APP_ORIGIN=http://localhost:3000 pnpm --filter web dev
```

수동 검증에서 보스를 빨리 끝내려면 HP를 작게 잡는다.

```bash
OGU_RAID_HP_MIN=10 OGU_RAID_HP_MAX=10 ./gradlew bootRun    # 다음에 나오는 보스부터 HP 10
```

지금 보스의 상태를 직접 보는 법:

```bash
docker exec -it <redis 컨테이너> redis-cli hgetall raid:boss
docker exec -it <postgres 컨테이너> psql -U ogu -d ogu -c "select * from raid_boss order by id desc limit 3"
```

## 자동 검증

```bash
cd apps/api && ./gradlew test            # 스크립트 규칙, 동시 공격, 옮기기, Redis 장애, 생애, 스트림, 계약, 모듈 경계
pnpm --filter web test                   # HP 합치기, 공격 버튼, 결과 화면, 알림 문구
pnpm --filter web test:e2e:full          # raid.spec.ts
grep -rn "US3-AC2" apps/                 # 인수 조건에서 테스트 찾기
```

## 수동 검증 시나리오 (로컬)

회원 A, B, C를 가입과 온보딩까지 마친 상태에서 시작한다. 각각 다른 브라우저 프로필을 쓴다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | 빈 DB로 API를 띄우고 A가 홈을 엶 | 홈에 보스의 감정과 HP(300/300)가 보이는 안내. 누르면 `/raid` | US5-AC1, US5-AC7 |
| 2 | A가 `/raid`를 엶 | 보스(무기력), HP 막대, 참여자 0명, 내 기여 0, 공격 버튼 | US1-AC1 |
| 3 | A가 공격 버튼을 누름 | HP 299, 내 기여 1, 참여자 1명. 버튼이 1초 동안 잠김 | US1-AC2, US1-AC5 |
| 4 | A가 버튼을 빠르게 연달아 누름 | 1초에 한 번만 줄어듦. 더 오래 잠기지 않음 | US1-AC3 |
| 5 | A가 탭 둘에서 동시에 누름 | 둘 중 하나만 반영 | US1-AC4 |
| 6 | B가 `/raid`를 열어 둔 채 A가 공격 | B의 화면에서 새로고침 없이 HP와 참여자 수가 바뀜 | US2-AC1, US2-AC4 |
| 7 | A, B, C가 함께 연달아 공격 | 세 화면의 HP가 줄어들기만 하고 같은 값으로 맞춰짐 | US2-AC2, US2-AC3 |
| 8 | B의 네트워크를 끊었다 다시 이음 | 다시 이어지면 지금 HP를 보임 | US2-AC5 |
| 9 | 브라우저에서 스트림 주소를 막고 `/raid`를 엶 | 3초마다 값이 맞춰지고 공격은 그대로 됨 | US2-AC6 |
| 10 | HP 10짜리 보스를 A, B가 함께 처치(C는 보기만) | 세 화면 모두 새로고침 없이 처치 장면과 결과. 공격 버튼이 사라짐 | US2-AC7, US4-AC3 |
| 11 | 10번 직후 A, B, C가 알림을 봄 | A, B에게 "함께 보스를 물리쳤어요". C에게는 없음. 누르면 `/raid` | US4-AC1, US4-AC2, US4-AC6 |
| 12 | 결과 화면을 살펴봄 | 함께한 회원 2명, 걸린 시간, 내 기여, 다음 보스가 나오는 때. 다른 회원의 닉네임과 기여는 없음 | US4-AC3, US4-AC4 |
| 13 | A가 마이페이지를 엶 | 함께 물리친 보스 1 | US4-AC5 |
| 14 | 처치된 보스를 A가 API로 공격 | 409 `RAID_BOSS_ENDED` | US1-AC6 |
| 15 | 시계를 다음 날 0시(한국 시간)로 넘김(테스트 프로필의 시계 또는 `ended_at`을 어제로 고침) | 1분 안에 새 보스. HP는 직전 참여자 2명 × 100 = 300(하한) | US5-AC3, US5-AC4 |
| 16 | 분석이 끝난 글을 감정별로 넣고 15번을 다시 함 | 가장 많은 감정의 보스. 숨긴 글은 세지 않음 | US5-AC2, US5-AC8 |
| 17 | 살아 있는 보스의 `spawned_at`을 8일 전으로 고침 | 1분 안에 물러남. 공격은 409. 알림과 마이페이지의 수는 그대로 | US5-AC5, US4-AC7 |
| 18 | 로그아웃한 채, 그리고 온보딩 전 회원으로 `/raid`에 감 | 로그인, 온보딩으로 보내짐 | US1-AC7 |
| 19 | 브라우저의 WebGL을 끄고 `/raid`를 엶 | 보스가 정지 이미지로 보이고 공격은 됨 | US1-AC8 |
| 20 | 공격하던 중 API를 다시 띄움 | 다시 뜬 뒤 HP와 내 기여가 그대로 | US3-AC5 |
| 21 | Redis 컨테이너를 멈추고 A가 공격, B가 글을 씀 | 공격은 "잠시 쉬는 중" 안내와 함께 거절(503). 화면은 마지막 HP. 글쓰기와 알림은 그대로 | US3-AC6, US3-AC7 |
| 22 | Redis를 다시 띄우고 A가 공격 | 마지막으로 기록된 값에서 이어짐. 처치됐던 보스는 처치된 채 | US3-AC8 |

## 동시성 확인

```bash
cd apps/api && ./gradlew test --tests "*RaidConcurrencyTests*" --rerun-tasks
```

- 회원 64명이 동시에 공격해도 줄어든 HP가 받아들여진 수와 같고, 기여의 합과 같다(US3-AC1, AC4).
- 남은 HP보다 많은 공격이 와도 HP는 0에서 멈추고 처치 기록과 알림은 하나씩이다(US3-AC2, AC3).
- 두 인스턴스가 동시에 보스를 만들어도 살아 있는 보스는 하나다(US5-AC6).

## 부하 테스트 (SC-001, SC-002, SC-003)

로컬에서만 돌린다. 운영 VM에는 걸지 않는다.

```bash
# 1. 회원 500명 시드(같은 비밀번호)와 HP가 큰 보스
docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 < apps/api/src/test/resources/seed/m5-raid-load.sql

# 2. 유지 실행: 가상 사용자 500명이 60초 동안 1초에 한 번씩 공격
k6 run -e API=http://localhost:8080 -e MODE=sustain infra/k6/raid-attack.js

# 3. 처치 실행: HP를 받아들여질 수보다 작게 잡고 같은 부하
k6 run -e API=http://localhost:8080 -e MODE=defeat infra/k6/raid-attack.js
```

스크립트가 끝에 확인하는 것:

- 받아들여진 응답 수 = 최대 HP − 남은 HP = 기여의 합(Redis) = 기여의 합(1초 뒤 Postgres)
- 처치 실행에서 처치 기록 1건, 알림은 참여자마다 1건
- 공격 응답의 p50, p95, p99와 초당 처리 수

결과는 `docs/benchmarks/raid-attack.md`에 기기, 날짜, 설정과 함께 남긴다.

## 성능 측정 (SC-004, SC-005, SC-008)

- SC-004: A의 공격 응답 시각과 B의 화면에 반영된 시각의 차이를 20번 잰다.
- SC-005: 부하 테스트 중 스트림 하나가 1초에 받은 `raid` 이벤트 수를 센다.
- SC-008: 새 회원이 홈에서 `/raid`로 가 첫 공격을 하기까지의 시간을 e2e가 잰다.

## 운영 준비 (저장소 소유자)

1. **Redis가 떠 있는지 확인한다.** M3부터 compose에 있다. 레이드는 Redis가 없으면 공격을 받지 않는다(다른 기능은 그대로다).
2. **운영 DB가 V5인지 확인한다.** V6은 `notification`의 `post_id`를 NULL 허용으로 바꾼다. 잠금은 짧다.
3. **첫 보스.** 배포 뒤 API가 뜨면 1분 안에 HP 300의 보스가 나타난다. `select * from raid_boss`로 본다.
4. **값을 바꾸려면** `OGU_RAID_*` 환경 변수를 고치고 다시 띄운다. 다음에 나오는 보스부터 적용된다.
5. **Redis를 다시 띄우면** 직전 1초 안의 공격이 사라질 수 있다. 보스가 살아 있는 동안에는 되도록 다시 띄우지 않는다.
