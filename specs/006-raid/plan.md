# Implementation Plan: 보스 레이드

**Branch**: `006-raid` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-raid/spec.md`

## Summary

모든 회원이 보스 한 마리를 버튼으로 함께 공격한다. 공격은 HP를 1 줄이고 회원마다 1초에 한 번이다. 다른 회원의 공격은 새로고침 없이 보이고, 처치되면 참여한 회원 모두가 알림을 받는다. 보스는 언제나 한 마리 있고 처치되거나 물러나면 다음 날 0시에 새로 나온다. 순위는 없다.

API에는 `raid` 모듈을 추가한다. 살아 있는 보스의 HP와 회원별 기여는 Redis에 두고, 공격 한 번을 Lua 스크립트 한 번으로 처리해 몰려도 HP 갱신이 빠지지 않고 처치가 한 번만 정해지게 한다. Postgres에는 1초마다 뒤따라 적고, 처치는 Postgres에 적은 뒤에 알린다. Redis가 내려가 있으면 공격만 거절하고 나머지 기능은 그대로다. 실시간 전달은 M3의 알림 스트림에 `raid` 주제를 더해 초당 네 번으로 묶어 보낸다. `raid`와 `notification`이 서로를 부르지 않도록 `shared`의 인터페이스로 잇는다.

웹은 레이드 화면(`/raid`), 공격 버튼, 홈의 보스 안내, 처치 알림을 만든다. 가상 사용자 500명의 동시 공격을 k6로 재서 문서에 남긴다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring Data Redis(Lettuce, M3에서 들어옴). 새 의존성은 없다
- 웹: Next.js 16, TanStack Query 5, Zustand, React Three Fiber(M2). 새 라이브러리는 없다
- 부하 테스트: k6(개발 도구, 저장소에는 스크립트만 둔다)

**Storage**: PostgreSQL 17. Flyway `V6__raid.sql`로 `raid_boss`, `raid_contribution`을 만들고 `notification`에 `raid_boss_id`와 종류 하나를 더한다. Redis 7.4(M3의 컨테이너 그대로, 64MB, 디스크 저장 없음)에 `raid:*` 키와 Lua 스크립트 넷

**Testing**:
- API: JUnit 5, MockMvc, Testcontainers(Postgres, Redis). 스크립트는 Redis에 직접 돌려 규칙을 확인한다. 동시 공격은 회원 64명의 통합 테스트로 불변식을 본다. 보스의 생애는 시계 주입, Redis 장애는 컨테이너를 멈춰서 본다
- 웹: Vitest, Testing Library, Playwright(`e2e-full/raid.spec.ts`, 브라우저 컨텍스트 둘)
- 부하: k6 `infra/k6/raid-attack.js`(로컬에서 수동 실행)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API, Caddy 뒤), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 가상 사용자 500명의 동시 공격에서 HP 갱신 유실 0건(SC-001), 처치 기록 1건(SC-002)
- 같은 부하에서 공격 응답 p95 300ms 이하(SC-003)
- 다른 회원의 공격이 1초 안에 보인다(SC-004). 화면의 갱신은 초당 네 번 이하(SC-005)

**Constraints**:
- Redis는 디스크에 저장하지 않는다. Redis가 다시 뜨면 직전 1초 안의 공격이 사라질 수 있고, 스펙이 그 범위를 적어 두었다(FR-019, R4)
- 처치는 Postgres에 적은 뒤에 응답하고 알린다. 처치된 보스가 되살아나지 않는다(R5)
- Redis가 내려가도 글, 댓글, 알림은 그대로다(SC-007). API는 Redis 없이도 뜬다(M3의 규칙)
- 레이드의 응답과 이벤트에 다른 회원의 정보를 싣지 않는다(FR-013)
- 회원이 글자를 입력하는 곳이 없다(FR-020). M4의 판정과 가리기를 거칠 내용이 생기지 않는다
- 월 인프라 비용 증가 0원. 새 컨테이너가 없다

**Scale/Scope**: 인수 조건 38개(US1 8, US2 7, US3 8, US4 7, US5 8). 새 모듈 1개, 새 테이블 2개, 바뀌는 테이블 1개, 계약 연산 2개 추가와 기존 스키마 3개 변경, Lua 스크립트 4개, 웹 슬라이스 3개 추가와 1개 변경

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `raid`는 `shared`, `post`, `emotion`에만 의존한다(구현에서 컨트롤러가 받는 인증된 회원 타입 때문에 `member`가 더해졌다. `safety`와 같다). `notification → raid`, `feed → raid`가 더해진다. 실시간 전달은 `shared/realtime`의 인터페이스로 뒤집어 `raid → notification`을 만들지 않는다(R6). overview 5.1의 `raid → monster`와 `CommentCreated` 구독은 버튼 공격으로 정하면서 없어져 표를 고친다(R1). `ModularityTests`와 steiger가 확인한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/raid.openapi.yaml](contracts/raid.openapi.yaml)에 연산 2개와 기존 스키마의 변경, 스트림의 `raid` 이벤트를 먼저 적었다. 구현 첫 작업에서 루트 계약에 합친다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 38개에 ID를 붙였다. 가상 사용자 500명의 측정(SC-001~003)은 CI 밖에서 k6로 재고, 같은 불변식을 통합 테스트가 PR마다 확인한다(R15) | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | M4가 먼저 출시됐다. 레이드는 순위와 다른 회원의 기여를 보이지 않고(R13), 글자를 입력하는 곳이 없으며, 보스의 감정은 보이는 글만 세고 글의 내용을 싣지 않는다(R9). 쿨다운이 한 회원의 몰아치기를 막는다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | 레이드는 AI를 부르지 않는다. 보스의 감정은 이미 끝난 분석 결과를 세고, 없으면 무기력이다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | M3의 Redis 컨테이너를 그대로 쓴다. 보스 하나에 1MB 안쪽이다. 부하 테스트는 로컬에서 돌리고 운영 VM에 걸지 않는다 | 통과 |

**Phase 1 설계 후 재확인**:
- 새 테이블 2개와 Redis의 `raid:*` 키는 모두 `raid`가 소유한다. `notification` 테이블의 변경은 `notification`의 것이다.
- 이벤트 방향이 의존 그래프와 같다. `notification`이 `raid`의 `RaidBossDefeated`를 받는다.
- `raid`는 `monster`의 테이블과 규칙을 쓰지 않는다. 글의 몬스터와 보스는 서로 영향을 주지 않는다(스펙 Assumptions).
- 계약의 모든 JSON 응답이 `ApiResponse` 봉투를 따른다. 스트림의 `raid` 이벤트는 SSE이고 형식은 SSE 통합 테스트가 본다(M3와 같은 처리).
- 살아 있는 보스가 하나라는 것은 Postgres의 부분 유일 인덱스가 지킨다. 스케줄러의 잠금에 기대지 않는다(R10).
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/006-raid/
├── spec.md
├── plan.md                              # 이 문서
├── research.md                          # 결정 R1~R16
├── data-model.md                        # 테이블 2개, 바뀌는 테이블, Redis 키, 이벤트, 파사드, 설정
├── quickstart.md                        # 수동 시나리오, 부하 테스트 절차, 운영 준비
├── contracts/
│   └── raid.openapi.yaml                # 연산 2개와 기존 계약의 변경
├── checklists/
│   └── requirements.md
└── tasks.md                             # /speckit.tasks가 만든다
```

### Source Code (repository root)

```text
apps/api/src/main/
├── java/com/ogu/raid/package-info.java          # allowedDependencies: shared, post, emotion
├── kotlin/com/ogu/
│   ├── raid/
│   │   ├── RaidApi.kt                            # participantIds, defeatedCount
│   │   ├── RaidBossDefeated.kt
│   │   ├── application/
│   │   │   ├── RaidProperties.kt, RaidConfig.kt
│   │   │   ├── RaidAttackService.kt              # 스크립트 호출, 다시 채우기, 처치 마무리
│   │   │   ├── RaidQueryService.kt               # 지금의 레이드(Redis, 안 되면 Postgres)
│   │   │   ├── RaidFlusher.kt                    # 1초마다 Redis → Postgres
│   │   │   ├── RaidBossLifecycle.kt              # 나타남, 물러남, 감정과 HP 정하기
│   │   │   ├── RaidBroadcaster.kt                # 250ms마다 TopicBroadcaster로
│   │   │   └── RaidFacade.kt                     # RaidApi 구현
│   │   ├── domain/
│   │   │   ├── RaidBossRepository.kt, RaidContributionRepository.kt   # JdbcClient
│   │   │   └── RaidRedis.kt                      # 키와 스크립트 넷을 감싼다
│   │   └── presentation/RaidController.kt
│   ├── shared/realtime/TopicBroadcaster.kt       # 인터페이스와 아무것도 하지 않는 기본 구현
│   ├── notification/
│   │   ├── stream/                               # topics, 최신 값 하나를 보내는 일, TopicBroadcaster 구현
│   │   ├── application/NotificationEventListener.kt   # RaidBossDefeated → RAID_BOSS_DEFEATED
│   │   └── domain/NotificationType.kt
│   ├── post/PostApi.kt                           # visibleIdsSince
│   └── feed/                                     # 감정 통계에 raidBossesDefeated
└── resources/
    ├── db/migration/V6__raid.sql
    └── redis/raid-attack.lua, raid-flush.lua, raid-end.lua, raid-load.lua

apps/web/src/
├── app/raid/page.tsx
├── entities/raid/                                # 타입, useRaidQuery, mergeRaidState, BossBanner
├── features/raid-attack/                         # 공격 뮤테이션과 버튼
├── features/notification-stream/                 # topics, raid 이벤트를 캐시에 합치기
├── widgets/raid-arena/                           # 레이드 화면의 본문
├── entities/monster/                             # MonsterDisplay에 보스의 크기와 효과
└── entities/notification/                        # RAID_BOSS_DEFEATED 문구와 이동할 곳

apps/web/e2e-full/raid.spec.ts
infra/k6/raid-attack.js
docs/benchmarks/raid-attack.md
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 모듈 하나를 더하고, 웹은 FSD 슬라이스 셋을 더한다.

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, V6 마이그레이션, `raid` 모듈 뼈대와 설정, `ModularityTests` | 기반 |
| 2 | Redis 스크립트 넷과 `RaidRedis`, 스크립트 규칙 테스트 | 기반 |
| 3 | 공격과 조회 API, 다시 채우기, 동시 공격 통합 테스트 | US1, US3 |
| 4 | 옮기기(`RaidFlusher`), 처치 마무리, Redis 장애와 재기동 테스트 | US3 |
| 5 | `TopicBroadcaster`, 스트림의 `topics`, `RaidBroadcaster` | US2 |
| 6 | 웹: `entities/raid`, `features/raid-attack`, `widgets/raid-arena`, `/raid` | US1, US2 |
| 7 | 처치 알림, 결과 화면, 마이페이지의 수 | US4 |
| 8 | 보스의 생애(나타남, 물러남, 감정과 HP), 홈의 보스 안내 | US5 |
| 9 | e2e, k6 부하 테스트와 결과 문서 | US2, US3 |
| 10 | 인수 조건 점검, 문서(AGENTS, ARCHITECTURE, overview, README), quickstart 실행, PR | 마무리 |

묶음 3까지 끝나면 보스 하나를 API로 공격해 처치할 수 있다(보스는 테스트가 넣는다). 묶음 6까지가 화면으로 해 볼 수 있는 가장 작은 범위다.

## Complexity Tracking

위반이 없다.
