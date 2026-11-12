---

description: "Task list for 018-free-infra (무료 인프라로 이전)"
---

# Tasks: 무료 인프라로 이전

**Input**: Design documents from `/specs/018-free-infra/`

**Tests**: 포함한다(원칙 III). 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 38개다(US1 6, US2 6, US3 8, US4 8, US5 3, US6 4, US7 3). 공급자의 실제 동작과 한 달 사용량은 [quickstart.md](quickstart.md)의 Q1~Q7과 전환 표로 본다.

**경로**: API는 `apps/api/src/main/kotlin/com/ogu/`(아래 `M/`), 테스트는 `apps/api/src/test/kotlin/com/ogu/`(아래 `T/`), 설정은 `apps/api/src/main/resources/`(아래 `R/`).

## Phase 1: Setup

- [X] T001 `specs/018-free-infra/contracts/maintenance.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.17.0): `components.responses.Maintenance`, `ErrorResponse.retryAfterSeconds` 설명, 모든 쓰기 연산(POST, PUT, PATCH, DELETE)에 `'503': { $ref: '#/components/responses/Maintenance' }`. 이미 503이 있는 `requestPasswordReset`, 레이드 공격은 설명에 `SERVICE_MAINTENANCE`를 덧붙인다. 레이드 503, `RaidState.available`, `RaidLive.available`, `epoch` 설명에서 Redis를 지운다. `info.description`에 `DATABASE_UNAVAILABLE` 한 줄(FR-025). `pnpm --filter web gen:api`로 `apps/web/src/shared/api/generated.ts`를 다시 만든다
- [X] T002 [P] `R/db/migration/V16__embedding_backfill.sql`: `embedding_backfill`(`model VARCHAR(100) PRIMARY KEY`, `completed_at TIMESTAMPTZ NOT NULL`). 번호는 올리기 직전에 `origin/main`을 다시 본다
- [X] T003 [P] JDK 25 실행: `apps/api/Dockerfile`과 `infra/gcp/Dockerfile.api`의 실행 이미지 `eclipse-temurin:21-jre` → `25-jre`. 컴파일은 toolchain 21 그대로 둔다(Kotlin 2.2.21의 JVM 대상이 25를 받지 않을 수 있다. AOT 캐시는 실행 JRE만 25면 된다, research R1). detekt와 ktlint의 고정 버전은 건드리지 않는다. 기동은 CI의 스모크 테스트가 본다

## Phase 2: Foundational (모든 스토리가 기대는 것)

- [X] T004 `T/shared/schedule/DueTimerTest.kt`, `ActivityGateTest.kt`를 먼저 쓴다(주입한 `Clock`과 직접 돌리는 스케줄러): `US4-AC4 기동 때 DB에서 읽은 가장 이른 시각에 돈다`, `더 이른 시각으로만 다시 건다`, `지난 시각이면 바로 돈다`, `돌고 나면 다음 시각을 다시 읽는다`, `꺼져 있으면 걸지 않는다`, `ActivityGate는 4분이 지나면 비활성이다`, `헬스 요청은 활동으로 세지 않는다`
- [X] T005 `M/shared/schedule/DueTimer.kt`: 작업 하나의 다음 시각을 들고 `arm(at)`(지금보다 이를 때만), `rearm()`(작업이 넘긴 `nextDue(): Instant?`를 다시 읽음), 울리면 `runDue()` 뒤 `rearm()`. 하나의 `ThreadPoolTaskScheduler`(스레드 1, 빈이 아님. `StreamTimer`와 같은 까닭)를 모든 타이머가 함께 쓴다. 커밋 뒤에 거는 도우미 `armAfterCommit(at)`(트랜잭션이 없으면 바로). 기동(`ApplicationReadyEvent`) 때 등록된 모든 타이머를 `rearm()`
- [X] T006 `M/shared/schedule/ActivityGate.kt`, `ActivityFilter.kt`: `lastActivityAt`, `isActive(now)`(`ogu.activity.window` 기본 4m). 필터는 `/api/v1/**`만 갱신하고 `/actuator/**`는 갱신하지 않는다. `@ApplicationModuleListener`가 돌 때도 갱신한다. 방법은 `applicationTaskExecutor`에 거는 `TaskDecorator` 하나다(Boot의 `TaskDecorator` 빈을 자동 구성이 집어 간다. `spring.task.execution.mode: force`라 리스너가 모두 이 실행기를 탄다). 주기 작업과 `DueTimer`는 이 실행기를 쓰지 않으므로 세지 않는다
- [X] T007 `R/application.yml`: `spring.datasource.hikari`(`minimum-idle: 0`, `idle-timeout: 60s`, `keepalive-time: 0`, `max-lifetime: 30m`, `maximum-pool-size: 5`, `connection-timeout: 15s`), `management.endpoint.health.probes.enabled: true`, `server.tomcat.threads.max: 40`, `spring.lifecycle.timeout-per-shutdown-phase: 20s`. `M/member/infrastructure/security/SecurityPaths.kt`의 공개 경로에 `/actuator/health/liveness`
- [X] T008 [P] `T/shared/config/HealthEndpointTests.kt`: `US2-AC3 liveness는 DB가 내려가도 UP이고 DB 쿼리를 내지 않는다`(쿼리 수를 세는 `DataSource` 감싸기로 확인), `US2-AC5 liveness는 로그인 없이 부른다`, `/actuator/health는 db를 본다`

**Checkpoint**: 틀과 설정이 있다. 이 묶음은 그대로 내보내도 동작이 바뀌지 않는다(PR 1의 일부).

## Phase 3: US4 뒤에서 도는 일은 DB를 계속 깨우지 않는다 (P1)

**Goal**: 할 일이 없으면 DB에 쿼리가 나가지 않는다. 재시도 일정과 정해진 작업의 때는 그대로다.

**Independent Test**: 시계를 앞으로 보내며 쿼리 수를 센다. 할 일이 없을 때 0이고, 재시도와 정해진 작업은 제때 돈다.

### Tests

- [X] T009 [US4] `T/shared/schedule/IdleDatabaseTests.kt`(모든 주기 작업을 켠 컨텍스트, 쿼리를 세는 `DataSource`, 앞으로 가는 시계): `US4-AC3 할 일이 없으면 마지막 요청 뒤 10분 동안 쿼리가 하나도 없다`(레이드 보스가 살아 있는 상태 포함), `US4-AC4 기다리는 재시도가 있는 채 다시 뜨면 그 시각에 돈다`
- [X] T010 [P] [US4] 기존 재시도 테스트에 타이머 경로를 더한다(스케줄러 스위치를 켠 컨텍스트 하나): `US4-AC1 새 글의 감정 분석과 몬스터가 1분 안에 생긴다`, `US4-AC2 실패한 분석은 30초 뒤, 그다음 60초 뒤 다시 시도한다` — 감정(`T/emotion/`), 위험(`T/safety/`), 임베딩(`T/recommend/`), 편지(`T/report/`) 각각
- [X] T011 [P] [US4] `T/report/WeeklyReportScheduleTests.kt`: `US4-AC5 월요일 05:00에 리포트와 알림이 나간다`, `기동이 늦어도 밀린 주를 채운다`. `T/shared/schedule/HousekeepingTests.kt`: `US4-AC6 04:00 한 번에 다섯 정리가 지금 기준으로 지운다`, `한 단계가 실패해도 다른 단계는 돈다`
- [X] T012 [P] [US4] `T/companion/`에 `US4-AC7 조용한 6시간이 지나면 글쓰기 타이머가 울린다`, `새 글이 오면 타이머가 미뤄진다`. `T/raid/RaidLifecycleTests.kt`에 `US4-AC8 보스가 물러날 시각에 다음 보스로 바뀐다`

### Implementation

- [X] T013 [P] [US4] 감정 분석: `M/emotion/application/RetryScheduler.kt`의 `@Scheduled`를 `DueTimer`로. `nextDue = select min(next_attempt_at) from emotion_analysis where status = 'PENDING'`(`EmotionAnalysisRepository`에 쿼리 추가). `PostCreatedListener`의 행 생성, `AnalysisRunner`의 맡기(임대)와 실패(백오프)가 커밋 뒤 `armAfterCommit`. `ogu.emotion.retry.scheduler-enabled`, `poll-interval` 키는 남기고 `poll-interval`은 쓰지 않는다고 적거나 지운다
- [X] T014 [P] [US4] 위험 분류: `M/safety/application/RiskClassificationRunner.kt`의 `RiskRetryScheduler`를 같은 방식으로(`risk_assessment`). `ScreeningListener`의 `insertPending`이 건다
- [X] T015 [P] [US4] 임베딩: `M/recommend/application/EmbeddingPipeline.kt`의 `EmbeddingRetryScheduler`(`post_embedding`). `EmbeddingListener.request`, `markAttempt`, 백필의 다시 요청이 건다
- [X] T016 [P] [US4] 주간 편지: `M/report/application/WeeklyLetterPipeline.kt`의 `WeeklyLetterRetryScheduler`(`weekly_report.letter_next_attempt_at`, `letter_status = 'PENDING'`)
- [X] T017 [P] [US4] AI 친구 반응: `M/companion/application/ReactionRunner.kt`의 `ReactionScheduler`. `nextDue = min(greatest(post_created_at + wait, next_attempt_at))`과 가장 이른 만료(`post_created_at + 24h`) 가운데 이른 것(`CompanionReactionRepository`). `ReactionEnrollListener`, 뒤따르는 친구 예약, 미루기가 건다. `ogu.companion.enabled=false`면 걸지 않는다
- [X] T018 [P] [US4] AI 친구 글쓰기: `M/companion/application/PostingRunner.kt`의 `PostingScheduler`. 쓰는 중인 행의 `next_attempt_at`, 없으면 `max(latestVisiblePostAt + quiet, 오늘 또는 내일 active-from)`이고 `active-until`을 넘으면 다음 날. `CompanionPostApi`가 아닌 `PostCreated`를 받아(이미 `companion`이 받는 이벤트) 다시 건다. `posting.enabled=false`면 걸지 않는다
- [X] T019 [P] [US4] 레이드 보스 교체: `M/raid/application/RaidBossLifecycle.kt`의 `RaidLifecycleScheduler`. 살아 있는 보스면 `spawned_at + retreat-after`, 끝났으면 `ended_at` 다음 날 00:00(`ogu.raid.zone`). `insertAlive`, `RaidFinisher.finish`가 건다. `tick`의 `findLatest()` 두 번을 한 번으로
- [X] T020 [P] [US4] 주간 리포트: `M/report/application/WeeklyReportJob.kt`의 `WeeklyReportScheduler`. 이번 주 `publish-at`(월요일 05:00 한국 시간), 훑는 중이면 1분 뒤, `weekly_report_run`이 있으면 다음 주
- [X] T021 [US4] `M/shared/schedule/Housekeeping.kt`: `ogu.housekeeping.cron`(`0 0 4 * * *`, Asia/Seoul) 하나에서 `NotificationPurgeJob`, `LoginAttemptCleanupJob`, `SafetyPurgeJob`, `SseTicketCleanupJob`, `WeeklyReportPurgeJob`, `PasswordResetTokenCleanupJob`을 차례로 부른다. 각 잡의 `@Scheduled`를 지우고 메서드는 남긴다. 단계마다 따로 실패하고 ERROR를 남긴다. 끝에 모든 `DueTimer`를 `rearm()`하고 이벤트 재전송을 한 번 돌린다. `shared`가 다른 모듈을 직접 부르면 경계 위반이므로, 각 모듈이 `shared`의 `HousekeepingStep` 빈을 내놓고 `Housekeeping`이 목록으로 받는다. 옛 cron 키 다섯 개는 `application.yml`에서 지운다
- [X] T022 [US4] `M/shared/config/EventPublicationResubmitter.kt`: `scheduledResubmit`이 `ActivityGate.isActive`일 때만 돈다. 기동 때는 Modulith의 `republish-outstanding-events-on-restart`가 맡는다
- [X] T023 [US4] `M/recommend/application/EmbeddingBackfill.kt`: 기동 때 `embedding_backfill`에 지금 모델 행이 있으면 훑지 않는다. 끝나면 `INSERT ... ON CONFLICT DO NOTHING`. 테스트 `T/recommend/EmbeddingBackfillTests.kt`에 `다시 떠도 끝난 모델은 훑지 않는다`, `모델을 바꾸면 한 번 훑는다`
- [X] T024 [US4] `M/raid/application/RaidFlusher.kt`: 바뀐 회원이 없으면 DB를 열지 않는다(`store()`의 조건을 고친다. 지금은 살아 있는 보스면 매초 UPDATE). 이 태스크는 Redis를 지우기 전에도 들어간다

**Checkpoint**: 주기 작업이 DB를 계속 깨우지 않는다(PR 2). GCP 결제 계정이 닫혀(2026-11-10) GCP에서 두 주 지켜보는 단계는 하지 않는다. 테스트로 확인하고 Render와 Neon으로 바로 간다.

## Phase 4: US3 실시간 알림과 레이드는 서버 한 대에서 그대로 동작한다 (P1)

**Goal**: Redis 없이 같은 동작을 한다.

**Independent Test**: Redis 컨테이너 없이 전체 테스트가 통과하고, k6 500명에서 불변식이 맞는다.

### Tests

- [X] T025 [US3] `T/notification/stream/LocalSignalPublisherTests.kt`(`RedisSignalPublisherTests`의 커밋 뒤와 롤백 경우를 옮긴다): `US3-AC1 댓글이 커밋되면 글쓴이의 연결에 5초 안에 알림이 간다`, `롤백되면 보내지 않는다`, `US3-AC7 비밀번호를 바꾸거나 탈퇴하면 그 회원의 연결이 닫힌다`
- [X] T026 [P] [US3] `T/notification/NotificationStreamReplayTest.kt`에 `US3-AC2 끊긴 동안의 알림을 다시 붙을 때 순서대로 받는다`(Redis 없이). `T/notification/stream/SafetyDrainTests.kt`: `회원 요청이 4분 넘게 없으면 안전망이 쿼리를 내지 않는다`, `요청이 있는 동안 빠진 알림을 60초 안에 보낸다`
- [X] T027 [US3] `T/raid/RaidLiveStoreTests.kt`(`RaidScriptTests`를 옮긴다): 없음과 옛 보스 → 다시 올림, 끝남, 쿨다운(남은 ms, 늘리지 않음), 공격(HP −1, 기여 +1, 바뀐 회원), 0에서 처치 한 번, `end`, `load`(더 새 보스만), 꺼내기(바뀐 회원만, 실패하면 되돌림)
- [X] T028 [P] [US3] `T/raid/RaidConcurrencyTests.kt`를 메모리 저장소로: `US3-AC3 64명이 동시에 공격해도 받아들인 수 = 줄어든 HP = 기여의 합`, 마지막 HP에서 처치 한 번. `T/raid/RaidStreamTests.kt`: `US3-AC4 다른 회원의 공격이 1초 안에 화면에 보인다`
- [X] T029 [P] [US3] `T/raid/RaidDurabilityTests.kt`를 다시 쓴다: `US3-AC5 정상 종료하면 받아들인 공격이 하나도 사라지지 않는다`(컨텍스트를 닫은 뒤 기록을 본다), `US3-AC8 쓰기 전에 죽으면 직전 1초만 잃고 숫자가 서로 맞는다`(저장소를 비우고 다시 올림), `기동 뒤 맞추기가 옛 인스턴스의 마지막 기록을 받아들인다`(research R6), `종료 중에는 공격이 503 RAID_UNAVAILABLE`, `FR-015 레이드가 공격을 받지 않는 동안에도 글쓰기, 댓글, 알림은 된다`(지우는 `RaidRedisOutageTest`의 약속을 옮긴다)
- [X] T030 [P] [US3] `T/notification/NotificationStreamTests.kt`나 `StreamLifecycleTests.kt`에 `US3-AC6 서버가 내려갈 때 열린 연결을 먼저 닫는다`(지금 동작의 확인)

### Implementation

- [X] T031 [US3] `M/notification/stream/LocalSignalPublisher.kt`: `publish(signal)` — 트랜잭션 안이면 커밋 뒤, 아니면 바로, 전용 스레드 하나(큐 10,000, 넘치면 1분에 한 번 WARN)에서 모든 `NotificationSignalHandler`를 부른다(처리기마다 예외를 잡는다). `NotificationWriter`, `NotificationReadService`, `WithdrawalNotificationListener`, `SessionsEndedListener`가 이것을 쓴다. `NotificationSignal`의 `encode`/`parse`는 테스트가 쓰지 않으면 지운다
- [X] T032 [US3] 지운다: `RedisSignalPublisher`, `RedisSignalSubscriber`, `NotificationSubscriptionStarter`, `NotificationRedisConfig`(실행기는 `LocalSignalPublisher` 쪽 설정으로 옮긴다), `RealtimeConnectionState`, `RealtimeConnectionProbe`, `M/shared/config/RedisClientConfig.kt`, `RedisHealthGroupConfig.kt`. `SafetyDrain`은 `ActivityGate`를 타고, 장애 주기(`outage-drain-interval`)와 `RealtimeConnectionChanged` 처리를 지운다. `SseHub.drainLagging`의 `withUnreadCounts` 갈래를 지운다
- [X] T033 [US3] `M/raid/domain/RaidLiveStore.kt`(data-model 4): `ReentrantLock` 하나, `boss`, `contributions`, `dirty`, `cooldownUntil`, `accepting`. `RaidRedis`의 연산(`attack`, `read`, `damageOf`, `contributions`, `flush`, `markDirty`, `end`, `load`, `withLoadLock`, `expireContributions` → 다음 보스를 올릴 때 비움)을 같은 이름과 결과 타입(`AttackOutcome`, `RaidFlush`)으로 옮긴다. `RaidUnavailableException`은 `accepting = false`일 때만 던진다. `RaidRedis.kt`와 `R/redis/raid-*.lua` 다섯 개를 지운다
- [X] T034 [US3] `M/raid/application/`: `RaidLoader`에 맞추기(기동 뒤 `ogu.raid.reconcile-window` 3m 동안 `reconcile-interval` 30s마다, 회원별 `max(메모리, 기록)`, `hp = max(0, maxHp − 합)`). `RaidFlusher`에 정상 종료 단계(`SmartLifecycle.stop`: `accepting = false` → 남은 것을 모두 쓴다). `epoch`는 올린 시각의 밀리초. `RaidProperties`에 `reconcileWindow`, `reconcileInterval`
- [X] T035 [US3] `M/member/infrastructure/config/ProdAuthSettingsCheck.kt`의 `checkRealtime()`에서 Redis 검사를 지우고 `ogu.sse.allowed-origins` 검사는 남긴다. `T/member/infrastructure/config/ProdAuthSettingsCheckTest.kt`의 `REDIS_URL` 경우를 지운다. `SecurityPaths.PUBLIC`에서 `/actuator/health/realtime`을 지운다. `R/application.yml`의 `spring.data.redis`, `health.group.realtime`, `ogu.notification.outage-drain-interval`을 지우고 `connection-lifetime: 50m`
- [X] T036 [US3] 의존과 테스트 지원: `apps/api/build.gradle.kts`에서 `spring-boot-starter-data-redis`, `com.redis:testcontainers-redis`를 지운다(테스트 힙 1g 주석도 고친다). `apps/api/compose.yaml`의 redis 서비스. `T/TestcontainersConfiguration.kt`의 Redis 컨테이너. `T/support/RaidFixture.kt`(`RaidLiveStore`로 비우고 읽기), `T/notification/NotificationTestSupport.kt`(신호를 `NotificationSignalHandler` 테스트 빈으로 모은다), `T/notification/NotificationConcurrencyTest.kt`
- [X] T037 [US3] Redis 전용 테스트를 지운다: `T/notification/TwoInstanceStreamTest.kt`, `RedisOutageStreamTest.kt`, `T/notification/stream/RedisOutageTest.kt`, `RedisSignalPublisherTests.kt`(T025로 옮긴 뒤), `RedisSignalSubscriberTest.kt`, `NotificationSubscriptionStarterTest.kt`, `RealtimeConnectionStateTest.kt`, `T/shared/config/RedisSetupTests.kt`, `RedisOutageHealthTests.kt`, `T/raid/RaidRedisOutageTest.kt`, `RaidScriptTests.kt`(T027로 옮긴 뒤). `T/support/AppInstance.kt`를 아직 쓰는 곳이 없으면 지운다
- [X] T038 [US3] CI와 e2e: `.github/workflows/ci.yml`의 스모크 테스트에서 `ogu-ci-redis`와 `REDIS_URL`을 지운다. `infra/compose.e2e.yaml`의 redis와 `REDIS_URL`. `apps/web/e2e-full/support/db.ts`의 `runRedis`, `support/raid.ts`의 `DEL raid:boss` → `e2e` 프로필에서만 빈이 생기는 `POST /api/v1/e2e/raid/reload`(BFF 키 필요, 계약에 넣지 않음, `@Profile("e2e")`)를 불러 메모리 저장소를 비우고 기록에서 다시 올린다. 운영 프로필에서 이 경로가 없음을 테스트로 본다. `infra/k6/raid-attack.js`, `run-raid-load.sh`의 `REDIS_CLI` → API(`GET /api/v1/raid`)와 DB로 불변식
- [X] T039 [US3] k6 정확성: `infra/k6/run-raid-load.sh`를 VUS=500으로 로컬 compose(e2e)에 돌려 불변식이 맞는지 보고, 값을 `specs/018-free-infra/research.md` R5 끝에 적는다(SC-006)

**Checkpoint**: Redis가 없다(PR 3). 지금의 GCP VM에서도 Redis 없이 뜬다. VM compose(`infra/gcp/vm/compose.yaml`, `runtime.py`)의 Redis도 이 PR에서 지운다(T040).

- [X] T040 [US3] `infra/gcp/vm/compose.yaml`, `infra/gcp/vm/runtime.py`(`REDIS_PASSWORD`, `REDIS_URL` 만들기와 필수 값), `infra/gcp/scripts/prepare-secrets.py`, `infra/gcp/tests/test_compose.py`, `test_prepare_secrets.py`에서 Redis를 지운다. 옛 Oracle 자료(`infra/compose.prod.yaml`, `infra/tests/deploy_test.sh`의 Redis 검사)도 같이. `python -m unittest`와 `bash infra/tests/deploy_test.sh`

## Phase 5: US2 운영자는 월 0원으로 운영한다 (P1)

**Goal**: Render와 Neon에 올라가 무료 한도 안에서 돈다.

**Independent Test**: quickstart Q1(512MB, CPU 0.1개), Q2(DB가 잠든다).

- [X] T041 [US2] (학습에 DB가 필요해 캐시는 `infra/render/scripts/build-image.sh`가 Postgres를 띄워 만든다. 힙은 측정 뒤 192MB) `apps/api/Dockerfile`: `eclipse-temurin:25-jre`, jar를 풀고(`-Djarmode=tools extract`) 학습 실행(`-XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh`, 학습용 가짜 env는 CI 스모크와 같은 값, DB 없이 뜨도록 학습 프로필)으로 캐시를 만든다. `ENTRYPOINT`에 `-XX:AOTCache=app.aot -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xmx224m -XX:MaxMetaspaceSize=150m -XX:ReservedCodeCacheSize=48m -Xss512k`. 학습이 DB 없이 안 되면 캐시를 빼고 플래그만 두며 research R1에 적는다. OTel 에이전트는 남긴다(꺼진 채)
- [X] T042 [US2] (Render 실측: 메모리는 맞췄고 기동 약 4분, 레이드 p95 2.6초로 SC-010 미달. research R1에 적고 저장소 소유자 판단으로 계속한다) quickstart Q1을 돌린다: `--cpus=0.1 --memory=512m`, 3분 안에 liveness UP, k6 50명 30분 동안 480MB 아래, p95 500ms 안(SC-010). 값을 research R1 끝에 적는다. 못 맞추면 힙, 스레드를 줄이고 다시. 그래도 안 되면 멈추고 사용자에게 알린다
- [X] T043 [P] [US2] (무료 플랜이 `maxShutdownDelaySeconds`를 받지 않아 뺐다) `render.yaml`(루트): 서비스 `ogu-api`, `runtime: image`, 이미지 `ghcr.io/hyunolike/5959-api`, `plan: free`, `region: singapore`, `healthCheckPath: /actuator/health/liveness`, `maxShutdownDelaySeconds: 30`, env 이름 목록(`SPRING_PROFILES_ACTIVE=prod`, `PORT=8080`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `OGU_BFF_KEY`, `OGU_SSE_ALLOWED_ORIGINS`, `OGU_WEB_ORIGIN`, `OAUTH_ALLOWED_REDIRECT_URIS`, `KAKAO_*`, `GOOGLE_*`, `AI_*`, `AI_COMPANION_*`, `MAIL_*`, `OGU_MAINTENANCE_READ_ONLY`), 비밀은 `sync: false`
- [X] T044 [P] [US2] (배포 훅 대신 Render API로 배포하고 배포 상태로 기다린다, research R9) `.github/workflows/release.yml`: arm64와 VM SSH 배포를 지운다. `linux/amd64` 이미지만 GHCR에 올리고, `RENDER_DEPLOY_HOOK_URL`에 `imgURL=<이미지@digest>`로 배포를 건다. 배포 뒤 `/actuator/health/liveness`를 최대 10분 기다린다. `workflow_dispatch`만 두는 지금 방식을 유지한다. actionlint
- [X] T045 [P] [US2] `infra/gcp/terraform/web.tf`: `API_ORIGIN`, `SSE_PUBLIC_ORIGIN`을 새 변수 `api_origin`(기본은 지금의 sslip 값)으로. `main.tf`의 Artifact Registry에 `cleanup_policies`(웹 이미지 최근 3개 남김, US7-AC3). `tests/architecture.tftest.hcl`을 고친다. `terraform fmt -check`, `validate`, `test`
- [X] T046 [US2] 운영 문서 `infra/render/README.md`: 계정 준비(quickstart 0), cron-job.org 작업(10분, liveness, 실패 메일, US2-AC5), Neon 직접 연결 주소, Render env 넣는 법, 배포 훅. `infra/gcp/README.md`에 "API는 Render로 옮김" 안내
- [ ] T047 [US2] 리허설(계정이 있어야 한다): 최근 백업을 Neon에 되살리고, Render에 운영 설정(점검 꺼짐)으로 띄우되 웹은 아직 옛 API를 본다. 리허설 동안 `AI_COMPANION_ENABLED=false`, `AI_COMPANION_POSTING_ENABLED=false`, `MAIL_ENABLED=false`로 둬서 리허설 DB에 AI 친구의 글이 생기거나 AI 요금, 메일이 나가지 않게 한다(전환 때 운영 값으로 되돌린다). quickstart Q2를 돌려 `US2-AC3`, `SC-003` 값을 research R3 끝에 적는다

**Checkpoint**: 새 환경이 떠 있고 측정이 끝났다(PR 4, 5와 리허설).

## Phase 6: US1 회원은 이전 뒤에도 같은 서비스를 쓴다 (P1)

**Goal**: 데이터를 잃지 않고, 읽기 전용 점검 1시간 안에 옮긴다.

**Independent Test**: 전환 전후 행 수가 같고, 같은 회원의 화면이 같다.

- [ ] T048 [US1] `T/shared/maintenance/ReadOnlyModeTests.kt`를 먼저 쓴다(`ogu.maintenance.read-only=true`): `US1-AC6 점검 중 글쓰기는 503 SERVICE_MAINTENANCE와 Retry-After이고 저장되지 않는다`, `GET은 그대로 된다`, `로그인, 소셜 로그인, 토큰 갱신, 로그아웃, 알림 연결 표는 받는다`, `헬스는 받는다`. `T/shared/error/DatabaseUnavailableTests.kt`: `FR-025 DB에 연결할 수 없으면 503 DATABASE_UNAVAILABLE이고 liveness는 UP이다`
- [ ] T049 [US1] `M/shared/maintenance/MaintenanceProperties.kt`(`ogu.maintenance.read-only` 기본 false, env `OGU_MAINTENANCE_READ_ONLY`, `retry-after` 600s), `ReadOnlyFilter.kt`(보안 필터 체인 앞, contracts의 예외 목록), `M/shared/error/ErrorCode.kt`에 `SERVICE_MAINTENANCE`(503), `DATABASE_UNAVAILABLE`(503). `GlobalExceptionHandler`가 `CannotGetJdbcConnectionException`과 그 원인이 연결 실패인 `DataAccessResourceFailureException`을 `DATABASE_UNAVAILABLE`로 바꾼다(FR-025). 웹(T050)도 같은 안내를 보인다. `R/application.yml`
- [ ] T050 [US1] 웹: `apps/web/src/shared/api/`의 오류 처리에서 `SERVICE_MAINTENANCE`를 "점검 중이라 지금은 저장할 수 없어요. 잠시 뒤 다시 시도해 주세요."로 보인다(글, 댓글, 공감, 레이드 공격 등 쓰기의 공통 오류 안내 자리). 단위 테스트. `typecheck`, `lint`, `lint:fsd`
- [ ] T051 [US1] 행 수 대조 스크립트 `infra/render/scripts/row-counts.sh <DB_URL>`: `flyway_schema_history`를 빼고 모든 테이블의 `count(*)`를 이름순으로 출력. `shellcheck`. CI의 infra 작업에 shellcheck 경로 추가
- [ ] T052 [US1] quickstart Q3: 지금 운영의 피드 응답 시간을 잰다(전환 전 기준값). 리허설에서 웹 아이오와와 웹 싱가포르 두 경우를 잰다. 결과로 웹 리전을 정하고 research R9 끝에 적는다. 싱가포르로 옮기면 `infra/gcp/terraform/web.tf`의 리전과 도메인 매핑을 바꾸는 태스크를 여기서 더한다
- [ ] T053 [US1] quickstart Q4(50분 연결), Q5(배포 겹침)를 리허설에서 돌리고 결과를 research R4, R6 끝에 적는다
- [ ] T054 [US1] 전환(quickstart 전환 표, 저장소 소유자와 함께): 점검 켬 → 덤프 → Neon 비우고 복원 → 행 수 대조(`US1-AC3`) → Render 다시 켬 → 웹 env를 Render로(`terraform apply`) → 확인(`US1-AC1`, `US1-AC2`, 한국에서 `US1-AC4`, `US1-AC5`) → 공지 내림. 점검 시간을 적는다(SC-008)

**Checkpoint**: 회원이 새 환경을 쓴다. 7일 관찰을 시작한다.

## Phase 7: US5 비밀번호 재설정 메일은 막힌 포트 없이 간다 (P2)

**Independent Test**: quickstart Q6.

- [ ] T055 [P] [US5] `apps/api/src/main/kotlin/com/ogu/shared/mail/MailProperties.kt`의 포트 기본값은 587로 두고, `render.yaml`과 `infra/render/README.md`에 Resend 값(`MAIL_SMTP_HOST=smtp.resend.com`, `MAIL_SMTP_PORT=2587`, `MAIL_SMTP_USERNAME=resend`)을 적는다. `specs/015-password-reset/quickstart.md`에 Render에서는 2587이라는 한 줄
- [ ] T056 [US5] 메일을 켤 때 quickstart Q6을 돌린다: `US5-AC1 1분 안에 받는다`. 안 되면 research R7의 대안(HTTP API 발송, `MailSender`의 두 번째 구현)을 새 태스크로 연다. `US5-AC2`, `US5-AC3`은 015의 자동 테스트(`PasswordResetApiTests`)가 포트와 상관없이 본다 — 전체 테스트 통과로 확인한다

## Phase 8: US6 데이터는 매일 백업되고 복구할 수 있다 (P2)

**Independent Test**: quickstart Q7.

- [ ] T057 [US6] `.github/workflows/ops-daily.yml`: `schedule: '30 18 * * *'`(03:30 한국 시간)과 `workflow_dispatch`(입력 `restore-drill`, `threshold-percent` 기본 70). 작업 `backup`: `postgresql-client-17`, `pg_dump -Fc "$NEON_DIRECT_URL"` → `aws s3 cp --endpoint-url https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com` → `s3://ogu-backup/postgres/<UTC 날짜>.dump`. 작업 `usage`: `curl -H "Authorization: Bearer $NEON_API_KEY" https://console.neon.tech/api/v2/projects/$NEON_PROJECT_ID` → `compute_time_seconds/3600`와 `synthetic_storage_size`를 출력하고 한도(100 CU-시간, 1GB)의 `threshold-percent`를 넘으면 `exit 1`(US2-AC4). 작업 `restore-drill`(입력이 true일 때): 최신 덤프를 받아 서비스 컨테이너 `pgvector/pgvector:pg17`에 `CREATE EXTENSION vector` 뒤 `pg_restore --no-owner --no-acl`, `row-counts.sh` 출력, 걸린 시간(SC-009). actionlint
- [ ] T058 [US6] R2 버킷 수명 주기 규칙(`postgres/` 14일)을 `infra/render/README.md`에 적고, 저장소 소유자가 콘솔에서 건다(`US6-AC1`)
- [ ] T059 [US6] `infra/RESTORE.md`를 새 절차로 다시 쓴다: R2에서 받기, Neon의 빈 DB 만들기, `pg_restore --no-owner --no-acl`, 행 수 대조, Render 다시 켜기, 탈퇴 정리 확인(지금 문서의 마지막 절은 남긴다)
- [ ] T060 [US6] quickstart Q7을 돌린다: `US6-AC1 오늘 덤프가 있다`, `US6-AC2 되살린 행 수가 같다`, `US6-AC3 실패하면 메일이 온다`(임계값 1%), `US6-AC4 백업 때문에 깨어 있던 시간이 10분 안`(Neon 활성 시간). 이 태스크는 전환(T054) 전에 끝낸다

## Phase 9: US7 옛 인프라를 정리한다 (P3)

**Independent Test**: 정리 뒤 리소스 목록과 다음 달 청구서.

- [ ] T061 [US7] 전환 뒤 7일이 지나고 문제가 없으면: VM에서 마지막 덤프를 R2 `ogu-backup/final/`에 둔다(`US7-AC1`)
- [ ] T062 [US7] `infra/gcp/terraform/main.tf`: `google_compute_instance.api`, `google_compute_disk.data`, `google_compute_address.api`, 방화벽 `ogu-public-https`, `ogu-iap-ssh`, VM 서비스 계정 `ogu-api`와 그 권한, `ogu-api-env` 비밀, 백업 버킷의 VM 권한을 지운다(`prevent_destroy`, `deletion_protection`을 먼저 푼다). `web.tf`의 `api_origin` 기본값을 Render 주소로. tftest를 고친다. **`terraform apply`는 저장소 소유자가 계획을 보고 승인한 뒤에 한다**(`US7-AC2`)
- [ ] T063 [P] [US7] 옛 VM 자료를 지운다: `infra/gcp/vm/`, `infra/gcp/scripts/deploy-api.sh`, `build-images.sh`의 API 부분, `infra/gcp/cloudbuild.yaml`의 API 이미지, `infra/gcp/Dockerfile.api`, `infra/scripts/`, `infra/compose.prod.yaml`, `infra/Caddyfile`, `infra/tests/deploy_test.sh`와 그 테스트들. CI의 infra 작업 경로를 고친다. 루트 `CLAUDE.md`의 `infra` 줄(테스트 명령)을 고친다
- [ ] T064 [P] [US7] `docs/adr/0007-render-neon.md`: ADR-0004를 대체한다(상태: 채택, 날짜). 맥락(유료 VM $18), 결정(Render, Neon, Redis 없음, 단일 인스턴스), 검토한 대안(Oracle 무료 VM, GCP e2-micro, Koyeb, Supabase), 결과(DB 사용량 관리, 단일 인스턴스의 한계, 되돌리기 7일 비용 약 $4). `docs/adr/0004-self-hosted-postgres.md`의 상태를 "0007로 대체"로
- [ ] T065 [US7] 다음 달 청구서와 Artifact Registry 용량을 확인한다(SC-001, `US7-AC3`). gcloud로 남은 리소스 목록을 남긴다

## Phase 10: Polish

- [ ] T066 [P] 문서: `apps/api/AGENTS.md`(Redis 절을 "서버 안 신호"로, Raid의 "Redis의 스크립트" 절을 메모리 저장소로, 주기 작업과 `DueTimer`, `ActivityGate`, 점검 모드, Hikari 설정의 까닭), `README.md`(구성도, 운영 주소), `apps/web/docs/ARCHITECTURE.md`의 Redis 언급, `specs/006-raid/spec.md`의 SC-003, SC-006 옆에 "018에서 바뀜" 한 줄
- [ ] T067 API: `./gradlew ktlintFormat ktlintCheck detekt`, `./gradlew test`, `./gradlew clean build`. 웹: `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`. e2e-full(Redis 없는 compose). infra: unittest, shellcheck, actionlint, terraform test
- [ ] T068 별도 검토자가 PR마다 브랜치 전체를 본다. 지적을 고친 뒤 `main`으로 PR을 연다(PR 본문에 스펙 링크와 인수 조건 체크리스트)

## Dependencies

- T001 → T048, T049, T050. T002 → T023. T003 → T041
- T004 → T005, T006. T005 → T013~T021. T006 → T022, T032(SafetyDrain). T007 → T008
- US4(T009~T024) → US3(T025~T040): 레이드 교체 타이머(T019)와 기록(T024)이 먼저 들어간 뒤 저장소를 바꾼다
- US3 → T041, T042(Redis 없이 재야 512MB 측정이 맞다)
- T041~T046 → T047(리허설) → T052, T053
- T057~T060(백업) → T054(전환). T049, T050(점검 모드) → T054. T051 → T054, T057
- T054 + 7일 → T061 → T062, T063, T065
- T055는 언제든. T056은 메일을 켤 때

## Parallel Example

```text
# Phase 3 구현: 작업마다 파일이 달라 함께 할 수 있다
T013 감정 분석   T014 위험 분류   T015 임베딩   T016 주간 편지
T017 AI 친구 반응   T018 AI 친구 글쓰기   T019 레이드 보스 교체   T020 주간 리포트

# Phase 4 테스트
T026 알림 재전송과 안전망   T028 레이드 동시성   T029 레이드 내구성   T030 종료 때 연결

# Phase 5 인프라
T043 render.yaml   T044 release.yml   T045 Terraform
```

## Implementation Strategy

- **MVP는 US4다.** 코드를 고치는 다섯 묶음 가운데 US4가 DB 한도를 지키는 핵심이고, 지금의 GCP에서도 그대로 내보낼 수 있다. 먼저 내보내 두 주 동안 동작이 바뀌지 않았는지 본다.
- 그다음 US3(Redis 지움)을 내보내면 VM에서도 Redis 없이 돈다. 이 둘이 끝나면 새 환경에 올릴 코드가 다 된 것이다.
- US2의 Q1(T042)이 관문이다. 512MB, CPU 0.1개에서 안 되면 이전을 멈추고 다시 정한다.
- US6(백업)을 US1(전환)보다 먼저 끝낸다. 백업 없이 새 DB로 옮기지 않는다.
- US7(삭제)은 7일 관찰 뒤, 저장소 소유자의 승인을 받고 한다.
