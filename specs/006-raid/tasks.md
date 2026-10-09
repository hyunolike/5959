---

description: "Task list for 006-raid (보스 레이드)"
---

# Tasks: 보스 레이드

**Input**: Design documents from `/specs/006-raid/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/raid.openapi.yaml, quickstart.md

**Tests**: 포함한다. constitution III에 따라 테스트 이름은 스펙의 인수 조건 ID로 시작한다(예: `` `US3-AC2 ...` ``). ID가 없는 보조 테스트는 한국어 설명만 쓴다. 인수 조건은 38개다(US1 8, US2 7, US3 8, US4 7, US5 8).

**Organization**: plan의 구현 순서(묶음 1~10)를 따른다. US3(몰려도 정확하다)의 스크립트가 US1의 전제라 Foundational에 먼저 들어간다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 미완료 작업에 의존하지 않아 병렬로 할 수 있다
- **[Story]**: 해당 사용자 스토리(US1~US5)

## Path Conventions

- API: `apps/api/src/main/kotlin/com/ogu/`, 테스트 `apps/api/src/test/kotlin/com/ogu/`, 리소스 `apps/api/src/main/resources/`
- 웹: `apps/web/src/`, 전체 흐름 E2E `apps/web/e2e-full/`
- 계약: 저장소 루트 `contracts/openapi.yaml`

---

## Phase 1: Setup (묶음 1)

- [x] T001 `specs/006-raid/contracts/raid.openapi.yaml`을 루트 `contracts/openapi.yaml`에 합친다(0.6.0, 연산 2개, `RaidBoss`, `RaidState`, `RaidLive`, `RaidAttackRequest`, `RaidAttackResult`, `EmotionStats.raidBossesDefeated`, `NotificationType`에 `RAID_BOSS_DEFEATED`, `Notification.post` null 허용, 스트림의 `topics`). ContractTests `pendingPaths`에 두 연산을 올리고 `pnpm --filter web gen:api` **구현 메모**: 두 연산을 같은 묶음에서 구현해 `pendingPaths`에 올릴 일이 없었다.
- [x] T002 `V6__raid.sql`: `raid_boss`, `raid_contribution`, 부분 유일 인덱스, `notification`의 `post_id` NULL 허용과 `raid_boss_id`, 종류와 대상 CHECK(data-model.md). `RaidMigrationTests`로 제약을 확인한다(살아 있는 보스 둘은 거절, `DEFEATED`면 hp 0, 종류와 `post_id`의 짝)
- [x] T003 `raid` 모듈 뼈대: `package-info.java`(shared, post, emotion), `RaidProperties`와 `application.yml`의 `ogu.raid.*`, `ErrorCode`에 `RAID_COOLDOWN`(429), `RAID_BOSS_ENDED`(409), `RAID_UNAVAILABLE`(503). `ModularityTests`에 `raid`를 더한다 **구현 메모**: 컨트롤러가 인증된 회원 타입(`member.AuthenticatedMember`)을 받으므로 허용 의존에 `member`를 더했다(`safety`와 같다). plan의 "shared, post, emotion"에서 달라진 점이다.

## Phase 2: Foundational (묶음 2)

- [x] T004 [P] `resources/redis/raid-attack.lua`, `raid-flush.lua`, `raid-end.lua`, `raid-load.lua`(research R2~R5, data-model의 키)
- [x] T005 `raid` domain/RaidRedis: 스크립트 넷과 `raid:boss` 읽기를 감싼다. Redis를 쓸 수 없으면 `RaidUnavailableException` 하나로 바꿔 던진다 **구현 메모**: 다시 채우는 잠금은 잡기와 풀기를 따로 두지 않고 `withLoadLock`으로 묶었다.
- [x] T006 `RaidScriptTests`(Testcontainers Redis): `US1-AC3 쿨다운 안의 공격은 HP와 기여를 바꾸지 않고 만료를 늘리지 않는다`, `US1-AC5 참여자 수는 회원마다 한 번만 는다`, `US3-AC2 HP는 0에서 멈추고 남은 만큼만 받아들인다`, `US3-AC4 기여의 합은 줄어든 HP와 같다`, 처치로 바꾸는 공격은 하나뿐, 끝난 보스는 쿨다운을 걸지 않는다, 요청의 보스 ID가 다르면 ENDED, 보스가 없으면 MISSING, 옮기기 스크립트가 집합을 비우며 값을 준다, 다시 채우면 epoch가 오른다
- [x] T007 [P] domain/RaidBossRepository(삽입, 살아 있는 보스, 가장 최근 보스, 조건부 끝내기, `least`로 HP 적기), RaidContributionRepository(`greatest`로 여러 건 적기, 보스의 모든 기여, 회원의 기여, 참여자, 처치된 보스 수)

## Phase 3: US1 공격과 조회, US3 정확성 (묶음 3, 4)

- [x] T008 [P] [US1] `RaidApiTests`: `US1-AC1 조회에 감정, HP, 참여자 수, 내 기여가 있다`, `US1-AC2 공격하면 HP가 1 줄고 내 기여가 1 오른다`, `US1-AC3 1초 안의 두 번째 공격은 429 RAID_COOLDOWN과 Retry-After`, `US1-AC4 같은 회원의 동시 공격은 하나만 반영된다`, `US1-AC6 끝난 보스는 409 RAID_BOSS_ENDED`, `US1-AC7 온보딩 전 회원은 403`, 보스가 한 번도 없으면 `boss`가 null, `bossId`가 빠지면 400, 응답에 다른 회원의 정보가 없다(`US4-AC4`)
- [x] T009 [P] [US3] `RaidConcurrencyTests`: `US3-AC1 회원 64명의 동시 공격에서 줄어든 HP가 받아들여진 수와 같다`, `US3-AC2 남은 HP보다 많은 공격이 와도 0에서 멈춘다`, `US3-AC3 마지막 HP를 다퉈도 처치 기록과 이벤트는 하나다`, `US3-AC4 기여의 합이 줄어든 HP와 같다(Redis와, 옮긴 뒤 Postgres)` **구현 메모**: 처치 이벤트가 하나인지는 알림 수로 본다. 알림 리스너가 생기는 T021에서 더한다. 여기서는 처치 기록과 기여의 합을 본다.
- [x] T010 [US1] application/RaidAttackService(스크립트 호출, MISSING이면 다시 채우고 한 번 더, 결과를 응답으로), RaidQueryService(Redis의 값, 안 되면 Postgres의 마지막 기록과 `available = false`, `nextBossAt`), presentation/RaidController(`GET /api/v1/raid`, `POST /api/v1/raid/attacks`). `pendingPaths`를 비운다
- [x] T011 [US3] application/RaidFlusher(1초마다 옮기기, 실패하면 집합에 되돌리기, 인스턴스 여럿이어도 안전), 처치 마무리(모든 기여를 적고 조건부로 `DEFEATED`, 같은 트랜잭션에서 `RaidBossDefeated`), "Redis는 끝났는데 Postgres는 살아 있음"을 다시 마무리
- [x] T012 [P] [US3] `RaidDurabilityTests`: `US3-AC5 옮긴 뒤 Redis의 상태가 그대로면 API를 다시 띄운 것과 같이 이어진다`(서비스 빈을 새로 만들어 확인), `US3-AC8 Redis를 비우면 마지막 기록에서 이어지고 epoch가 오른다`, `US3-AC8 처치된 보스는 Redis를 비워도 처치된 채다`, 옮기기를 두 번 하거나 순서를 바꿔도 결과가 같다, 처치 마무리가 실패한 뒤 다음 옮기기가 마무리한다
- [x] T013 [P] [US3] `RaidRedisOutageTest`(Redis 컨테이너를 멈춘다): `US3-AC6 공격은 503 RAID_UNAVAILABLE이고 조회는 마지막 기록과 available false`, `US3-AC7 글쓰기와 댓글, 알림 목록은 그대로` **구현 메모**: 공용 테스트 Redis를 멈추지 않고, 아무것도 듣지 않는 포트를 Redis 주소로 준 컨텍스트를 따로 띄운다.

## Phase 4: US2 실시간 (묶음 5)

- [ ] T014 [US2] `shared/realtime/TopicBroadcaster`(인터페이스와 `@ConditionalOnMissingBean` 기본 구현). `notification.stream`: 연결의 `topics`, 연결마다 주제별 최신 값 하나, `StreamTask.BROADCAST`, `SseHub`가 `TopicBroadcaster`를 구현. 스트림 컨트롤러가 `topics`를 받는다(모르는 주제는 무시)
- [ ] T015 [US2] `raid` application/RaidBroadcaster: 250ms마다 듣는 연결이 있으면 Redis를 읽어 바뀌었을 때만 보낸다. 새로 붙은 연결에는 지금 값을 한 번 보낸다. Redis를 읽지 못하면 `available = false`를 한 번
- [ ] T016 [P] [US2] `RaidStreamTests`: `US2-AC1 다른 회원의 공격이 1초 안에 raid 이벤트로 온다`, `US2-AC3 공격 50번이 몰려도 1초에 받는 이벤트는 넷 이하이고 마지막 값이 정확하다`, `US2-AC4 참여자 수가 이벤트에 실린다`, `US2-AC5 다시 붙으면 지금 값을 한 번 받는다`, `US2-AC7 처치되면 status DEFEATED 이벤트가 온다`, 주제 없이 연 연결은 받지 않는다, `raid` 이벤트에 id가 없어 알림의 `lastEventId`가 바뀌지 않는다, 이벤트에 회원 정보가 없다

## Phase 5: 웹 US1, US2 (묶음 6)

- [ ] T017 [P] [US2] apps/web/src/entities/raid/: 타입, `useRaidQuery`(스트림이 닫혀 있을 때만 3초마다), `mergeRaidState`(같은 보스면 작은 HP, epoch가 커지면 받은 값, 보스가 바뀌면 새 보스), `BossBanner`. 단위 테스트: `US2-AC2 늦게 온 큰 HP가 화면의 HP를 올리지 않는다`, `US2-AC6 스트림이 닫혀 있으면 3초마다 다시 받는다`, epoch가 커지면 받은 값을 따른다
- [ ] T018 [P] [US2] `features/notification-stream`: 스토어에 `topics`, 바뀌면 다시 연결, `raid` 이벤트를 `entities/raid`의 캐시에 합친다. 단위 테스트
- [ ] T019 [P] [US1] `features/raid-attack`: 공격 뮤테이션과 버튼. 응답의 `cooldownMs`로 잠그고, 429면 `Retry-After`만큼, 409면 조회를 다시, 503이면 쉬는 중 안내. 단위 테스트: `US1-AC2`, `US1-AC3 쿨다운 동안 버튼이 눌리지 않는다`, `US1-AC6`, `US3-AC6`
- [ ] T020 [US1] `widgets/raid-arena`와 `app/raid/page.tsx`: 보스(3D, 못 그리면 정지 이미지 `US1-AC8`), HP 막대(`progressbar`), 참여자 수, 내 기여, 공격 버튼. `entities/monster`의 `MonsterDisplay`에 보스 크기와 효과. 레이드 화면에 있는 동안 스트림 주제를 `raid`로. 미들웨어의 보호 경로에 `/raid`(`US1-AC7`)

## Phase 6: US4 처치 뒤 (묶음 7)

- [ ] T021 [P] [US4] `RaidNotificationTests`: `US4-AC1 처치되면 참여한 회원마다 RAID_BOSS_DEFEATED 알림이 하나`, `US4-AC2 공격한 적 없는 회원에게는 없다`, `US4-AC7 물러난 보스는 알리지 않고 수도 그대로`, `US4-AC5 감정 통계의 raidBossesDefeated가 1 는다`, 이벤트가 다시 와도 알림은 하나, 알림 응답의 `post`가 null
- [ ] T022 [US4] `raid` RaidFacade(`RaidApi`), `notification`: `NotificationType`, 드래프트, `on(RaidBossDefeated)`(500명씩), 저장소와 응답이 `post_id` NULL을 다룬다. `feed`: 감정 통계에 `raidBossesDefeated`
- [ ] T023 [P] [US4] 웹: `widgets/raid-arena`의 결과 화면(`US4-AC3`, `US4-AC4`), `entities/notification`의 문구와 이동할 곳(`US4-AC6`), 종의 토스트, `widgets/emotion-stats-panel`에 함께 물리친 보스 수(`US4-AC5`). 단위 테스트

## Phase 7: US5 보스의 생애 (묶음 8)

- [ ] T024 [P] [US5] `RaidLifecycleTests`(시계 주입): `US5-AC1 보스가 없으면 HP 300의 첫 보스`, `US5-AC2 최근 7일의 가장 많은 감정, 같으면 정해진 순서, 없으면 무기력`, `US5-AC3 처치 다음 날 0시(한국 시간)에 새 보스이고 그 전에는 nextBossAt`, `US5-AC4 HP는 직전 참여자 수 × 100을 300과 5000 사이로`, `US5-AC5 7일 뒤 물러나고 다음 날 HP 300`, `US5-AC6 동시에 만들어도 살아 있는 보스는 하나`, `US5-AC8 숨긴 글과 지운 글은 세지 않는다`, 23시 59분에 끝나도 다음 날 0시
- [ ] T025 [US5] `PostApi.visibleIdsSince`, application/RaidBossLifecycle(나타남, 물러남, 감정과 HP), 1분 주기와 기동 때 한 번
- [ ] T026 [P] [US5] 웹: 홈에 `BossBanner`(`US5-AC7`), 끝난 뒤 새 보스가 나오면 화면이 새 보스로 바뀐다. 단위 테스트

## Phase 8: e2e와 부하 (묶음 9)

- [ ] T027 apps/web/e2e-full/raid.spec.ts: `US1-AC1`, `US1-AC2`, `US2-AC1`(브라우저 둘), `US2-AC7`과 `US4-AC1`, `US4-AC3`(HP가 작은 보스를 둘이 처치), `US5-AC7`, `US1-AC7`. 보스의 HP는 테스트가 DB와 Redis에서 맞춘다(`support/raid.ts`)
- [ ] T028 `infra/k6/raid-attack.js`와 `apps/api/src/test/resources/seed/m5-raid-load.sql`. 유지 실행과 처치 실행을 로컬에서 돌려 `docs/benchmarks/raid-attack.md`에 남긴다(SC-001, SC-002, SC-003)

## Phase 9: Polish (묶음 10)

- [ ] T029 [P] 인수 조건 38개가 모두 테스트 이름에 있는지 확인하고 빠진 것을 더한다
- [ ] T030 [P] 문서: apps/api/AGENTS.md에 `raid` 절, apps/web/docs/ARCHITECTURE.md에 레이드 절, docs/architecture/overview.md 5.1 표와 그래프, 5.4를 구현과 맞춘다. README에 한 단락과 측정값
- [ ] T031 성능 측정(SC-004, SC-005, SC-008)과 quickstart의 수동 시나리오 22개를 로컬에서 실행하고 결과를 quickstart.md에 남긴다
- [ ] T032 일관성을 확인하고 PR을 연다(스펙 링크, 인수 조건 체크리스트, 운영 준비 항목)
