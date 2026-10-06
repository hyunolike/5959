# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project Overview

오구오구 백엔드: Kotlin + Spring Boot + Spring Modulith 모듈러 모놀리스.
kotlin-spring-modulith-template에서 이식했다. 단일 Gradle 모듈이며 루트 패키지는
`com.ogu`. 지금 모듈은 `shared`(OPEN), `member`, `post`, `ai`, `emotion`,
`monster`, `feed`, `notification`(004) 여덟 개다. 모듈 목록과 의존 방향은
`docs/architecture/overview.md` 5.1절을 따른다.

## Commands

```bash
cd apps/api && ./gradlew test                                              # all tests (requires Docker for Testcontainers)
cd apps/api && ./gradlew test --tests "com.ogu.ModularityTests"             # module boundary verification only
cd apps/api && ./gradlew ktlintCheck detekt                                 # lint & static analysis
cd apps/api && ./gradlew ktlintFormat                                       # auto-format (run before ktlintCheck on new code)
cd apps/api && ./gradlew bootRun                                            # local run (starts Postgres via compose.yaml)
cd apps/api && ./gradlew clean build                                        # full verification before finishing work
cd apps/api && ./gradlew koverHtmlReport                                    # coverage report (build/reports/kover/html)
```

## Architecture Rules (enforced by tests — do not break)

- **Package = module boundary.** Only a module's root package is visible to
  other modules (facade interfaces, public DTOs, events). `application`,
  `domain`, `presentation` sub-packages are hidden by Spring Modulith.
- **No dependency cycles between modules.** An event consumer compiles
  against the publisher's event type, so synchronous facade calls and event
  consumption must point in the same direction. 의존 방향은
  `docs/architecture/overview.md` 5.1절의 그래프를 따른다.
- Cross-module access happens only through a facade interface or an event
  listener (`@ApplicationModuleListener`). Never inject another module's
  repository, service, or entity.
- `ApplicationModules.verify()` in `ModularityTests` fails the build on any
  violation. Fix the code, never relax the verification.
- The same verification also runs at application startup
  (`spring-modulith-runtime` + `spring.modulith.runtime.verification-enabled`)
  — a violation prevents the app from booting.

## Conventions

- REST: paths under `/api/v1`, every response wrapped in `ApiResponse<T>`
  (`shared/response`), errors via `BusinessException` + `ErrorCode`
  (`shared/error`) handled by `GlobalExceptionHandler`.
- Entities extend `BaseTimeEntity` (JPA auditing) and use table names that
  avoid SQL reserved words.
- Global infrastructure annotations (`@EnableAsync`, `@EnableJpaAuditing`)
  live on `OguApplication`, not in a module — `@ApplicationModuleTest`
  bootstraps a single module and would miss module-local config.
- Module tests use `@ApplicationModuleTest` + `TestcontainersConfiguration`;
  mock dependency facades with `@MockitoBean`; assert event flows with the
  `Scenario` DSL. Test names are Korean backtick sentences.
- Kotlin style is ktlint `ktlint_official`: no blank line at the start of a
  class body, explicit imports (no wildcards), trailing commas, max line 120.
- Schema is owned by Flyway migrations under `src/main/resources/db/migration`
  (`ddl-auto: validate` in every profile) — never let Hibernate auto-generate
  DDL. Event Publication Registry uses `spring-modulith-starter-jdbc`, whose
  schema (Modulith 2.1 v2, Postgres) is created by `V1__init.sql`, not by the
  starter's own auto schema init.
- 스키마 변경은 `src/main/resources/db/migration`의 Flyway 스크립트로만 한다.
  `ddl-auto`는 `validate`다.
- 테스트 이름 앞에 스펙 인수 조건 ID를 붙인다. 예: `US1-AC2 ...`

## Auth (`member` module)

- 다른 모듈에 노출하는 공개 타입은 모두 `member` 패키지 루트에 있다: `MemberApi`
  (파사드, `getMember(memberId)`, 일괄 조회 `getMembers(ids)`), `MemberInfo`(온보딩 전에는 `nickname`/`jobRole`/
  `careerYear`가 null), `AuthenticatedMember`(컨트롤러 인자, `memberId`/`sessionId`/
  `onboarded`), `JobRole`, `CareerYear`.
- 보안 설정은 `member/infrastructure/security`에 모여 있다: `SecurityConfig`(필터
  체인 조립), `SecurityPaths`(공개 경로·온보딩 허용 목록·`/api/v1/**` 매처),
  `SessionCheckFilter`(JWT 서명 통과 뒤 세션 유효성 확인), `OnboardingGuard`
  (`SessionCheckFilter` 다음에 도는 필터), `BffClientIpResolver`, `SecurityErrorWriter`.
  요청 처리 순서: Bearer JWT 검증 → `SessionCheckFilter` → `OnboardingGuard` → 인가 규칙.
- 로그인 실패 제한(`LoginThrottle`, FR-004, research R6)은 Postgres
  `login_attempt` 테이블에 IP와 이메일을 SHA-256 해시한 키로 기록한다(원문을 저장하지
  않는다). 비밀번호를 검증하기 전에 시도를 먼저 예약(reserve)해 두므로 동시 요청도
  검증 전에 한도가 걸린다. `ip+email` 키는 15분에 5회로 15분 차단, `email` 키는
  1시간에 20회로 1시간 차단.
- refresh 토큰 교체(`SessionService.refresh`, US4-AC1~AC3, research R2)는 매번
  새 refresh 토큰을 발급하고, 직전 토큰으로 교체 후 30초(`ogu.auth.session.rotation-grace`)
  안에 다시 오면 access 토큰만 새로 준다(refresh는 그대로, 여러 탭 대응). 유예를 지나
  직전 토큰이 다시 오면 탈취로 보고 세션을 `REUSE_DETECTED`로 무효화한다.
- 온보딩 전(`onboarded=false`) 토큰으로도 부를 수 있는 허용 목록은
  `SecurityPaths.ONBOARDING_ALLOWED`에 있다: `GET /api/v1/members/me`(메서드까지 본다.
  같은 경로의 `PATCH` 프로필 수정은 온보딩 뒤에만 된다, 004 research R13),
  `/api/v1/members/nickname-availability`, `/api/v1/members/me/onboarding`,
  `/api/v1/auth/logout`. 그 밖의 인증 필요 경로는 `OnboardingGuard`가
  `403 ONBOARDING_REQUIRED`로 막는다.
- 운영(`prod` 프로필) 기동 조건은 `ProdAuthSettingsCheck`가 강제한다: JWT
  비밀키가 로컬 개발용 고정 값이면 안 되고, `OGU_BFF_KEY`가 비어 있으면 안 되고,
  카카오·구글 client id/secret이 모두 있어야 하고, 허용 redirect URI는 전부
  `https`여야 하고, `prod`와 `e2e` 프로필을 동시에 켤 수 없다(e2e의 비밀 값은
  `infra/compose.e2e.yaml`에 커밋된 고정 값이라 prod에 같이 켜면 토큰을 위조할 수 있다).
  004부터는 `spring.data.redis.url`(`REDIS_URL`)이 없거나 localhost이면, `ogu.sse.allowed-origins`
  (`OGU_SSE_ALLOWED_ORIGINS`)가 비면 뜨지 않는다.
- Redis(004, research R5)는 실시간 알림의 인스턴스 간 신호만 나른다. API는 Redis 없이도 뜨고,
  `/actuator/health`(배포 롤백 기준)에는 Redis 지표가 없다(`shared/config/RedisHealthGroupConfig`).
  Redis 상태는 `/actuator/health/realtime`으로 따로 본다. 테스트는 `TestcontainersConfiguration`의
  `redis:7.4-alpine`에 붙는다.
  Lettuce는 연결이 끊긴 동안 명령을 바로 거절한다(`shared/config/RedisClientConfig`, `REJECT_COMMANDS`).
  알림 신호(`notification/stream`)는 커밋 뒤에만 `ogu:notification` 채널로 나가고(`RedisSignalPublisher`, 전용
  스레드 하나에서 보내 요청 스레드가 기다리지 않는다), 구독 컨테이너는 `NotificationSubscriptionStarter`가 시작한다.
  첫 구독이 실패해도 기동을 막지 않고 5초마다 다시 시도한다(경고는 1분에 한 번).
  Redis 장애 판정은 `RealtimeConnectionState`가 한다. `RealtimeConnectionProbe`가 5초마다 PING을 보내 연속 2번
  실패하면 DOWN, 한 번 성공하면 UP으로 바꾸고, 바뀔 때만 애플리케이션 이벤트 `RealtimeConnectionChanged(state)`를
  낸다. 구독 컨테이너의 오류 처리기는 리스너 예외만 받고 Lettuce는 끊긴 구독을 조용히 다시 붙이므로, 안전망 주기
  전환(60초와 5초)과 복구 뒤 따라잡기는 이 이벤트를 듣고 한다.
  알림 생성(004 US1)은 `notification/application/NotificationEventListener`가 `PostLiked`, `CommentCreated`,
  `MonsterSpawned`, `MonsterDefeated`를 `@ApplicationModuleListener`(커밋 뒤 비동기, 새 트랜잭션)로 받아 한다. 그래서
  알림 실패가 댓글, 공감, HP 반영을 되돌리지 않고(FR-003), 끝나지 않은 발행은 재전송된다. 글 노출은 `PostApi.find`
  하나로만 보고, 댓글 받는 사람은 `PostApi.findComment`로 정한다. 쓰기는 `NotificationWriter`가 리스너 트랜잭션 안에서
  한다: 멱등 키(`COMMENT:{id}`, `SPAWNED:{id}`, `DEFEATED:{id}`)가 이미 있으면 번호도 받지 않고 건너뛰고, 여러 회원이면
  회원 ID 오름차순으로 번호를 받는다. 공감 묶음은 참여자 키에 걸리면 리스너 트랜잭션 전체를 rollback-only로 돌린다
  (예외가 아니라서 발행은 완료로 남는다). 소급 처치(`MonsterDefeated.retroactive`)일 때만 처치 알림이 글쓴이의 `SPAWNED`를 먼저 만들어 번호 순서를 고정한다.
  따로 처치되면 다시 만들지 않는다(90일 정리로 지워진 생성 알림이 되살아나지 않게).
  `MonsterFactory`는 몬스터 저장 직후 `MonsterSpawned`를 내고, 소급 반영으로 처치되면 그 뒤에 `MonsterDefeated`가 나간다.
  알림 번호(`NotificationSequenceRepository.next`)와 공감 묶음 갱신(`NotificationRepository.upsertLikeGroup`)은 트랜잭션
  안에서만 부를 수 있다. 읽는 쿼리는 보관 기간 조건을
  `NotificationRetention.condition("n")`처럼 별칭을 붙여 쓴다.
  실시간 전달(004 US1, research R2~R5)은 `notification/stream`에 있다. 브라우저는 BFF가 받아 준 일회용 연결 표로
  `GET /api/v1/notifications/stream?ticket=...&lastEventId=...`에 바로 붙는다(공개 경로라 Bearer를 받지 않는다). 표는
  `member`가 발급하고 소비한다(`MemberApi.issueStreamTicket`, `consumeStreamTicket`, 원문 32바이트 base64url,
  `sse_ticket`에는 SHA-256만, 30초, 조건부 `UPDATE ... RETURNING` 한 문장으로 한 번만, 소비 뒤 세션 유효 확인).
  `SseTicketCleanupJob`이 만료된 지 하루 지난 표를 지운다. `SseHub`는 회원별 연결(5개 상한, 넘으면 가장 오래된 것을 닫음)과
  연결별 마지막 전송 번호를 들고, 보낼 것은 언제나 DB에서 `seq > 마지막 번호`로 읽는다(Redis 신호는 힌트). 새 연결은
  허브에 먼저 등록한 뒤 재전송한다. 이 순서를 바꾸면 재전송 쿼리와 구독 사이에 커밋된 알림이 안전망 주기까지 빠진다.
  쓰기는 전용 실행기(`notificationStreamExecutor`, 4스레드)가 하고, 연결마다 한 번에 하나만 돈다. 하트비트(`: hb`, 25초)와
  안전망(`SafetyDrain`, 60초, Redis DOWN이면 5초, UP으로 돌아오면 모든 연결을 한 번 따라잡음)은 `StreamTimer` 스레드 하나가
  시각만 맞춘다. 이 타이머는 `TaskScheduler` 빈이 아니다(빈이면 Boot가 `@Scheduled`용 기본 스케줄러를 만들지 않는다).
  같은 이유로 `spring.task.execution.mode: force`를 두어 모듈의 실행기 빈이 있어도 `@Async`와 `@ApplicationModuleListener`가
  Boot의 `applicationTaskExecutor`를 쓰게 한다. 연결은 수명(15분)이 지나면 서버가 닫고, 종료 때 허브가 먼저 닫아 우아한
  종료가 열린 스트림을 기다리지 않는다. CORS는 스트림 경로에만 `ogu.sse.allowed-origins`를 허용하고 자격 증명은 쓰지 않는다
  (`StreamCorsConfig`). 쓰기 스레드 4개는 소켓에 블로킹으로 쓴다. 받는 쪽이 느리면 그 쓰기는 Tomcat 쓰기 타임아웃까지
  스레드 하나를 잡고 있을 수 있으므로, 느린 연결이 4개를 넘으면 그동안 다른 연결의 전달이 밀린다. 안전망의 DB 조회도
  타이머가 아니라 이 실행기에서 돈다(DB가 느려도 하트비트 시각은 밀리지 않는다). 복구는 PING으로 판정하므로, PING이
  성공한 뒤 구독이 다시 붙기 전에 나간 신호는 받지 못한다. 그 알림은 다음 안전망 주기(60초)나 재연결 때 온다.
  붙을 때 `lastEventId`는 지금 번호(`notification_sequence.last_seq`)를 넘지 않게 낮추고, 허브가 멈추는 중에 온 연결은
  바로 끝낸다. 스트림 컨트롤러의 오류는 모두 JSON 봉투로 쓴다(`Accept: text/event-stream`이어도). 테스트는 `support/SseTestClient`(JDK `HttpClient`, `ofLines()`)로 실제 스트림을 읽고,
  서버 두 대와 Redis 장애는 `support/AppInstance`로 직접 띄운다.

## Core loop (`post`, `ai`, `emotion`, `monster`, `feed`)

003-core-loop에서 모듈 다섯 개가 생겼다. 허용 의존은 각 모듈의 `package-info.java`에
적혀 있고, 다른 모듈에 보이는 것은 아래 루트 타입뿐이다.

| 모듈 | 공개 파사드와 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `post` | `PostApi`(`find`, `page`, `likedPostIds`, `attacksSoFar`, `findComment`, `previews`), `PostSummary`, `PostPage`, `Attack`, `AttackAction`, `CommentSummary` | `PostCreated`(커밋 후), `PostLiked`, `CommentCreated`, `CommentLiked`(같은 트랜잭션) | - |
| `ai` | `EmotionAnalyzer`, `EmotionClassification`, `EmotionAnalysisFailed` | - | - |
| `emotion` | `EmotionApi`(`findByPostIds`), `EmotionView`, `AnalysisStatus` | `EmotionAnalyzed` | `PostCreated` |
| `monster` | `MonsterApi`(`findByPostIds`, `hasCountedComment`, `damagerIds`), `MonsterView` | `MonsterSpawned`, `MonsterDefeated` | `EmotionAnalyzed`, `PostLiked`, `CommentCreated`, `CommentLiked` |
| `feed` | 없음(HTTP API만) | - | - |

`post`는 몬스터를 모른다. 글에 감정과 몬스터를 붙여 보여 주는 일은 `feed`가
파사드를 한 번씩 불러 조합한다. 피드 한 쪽은 쿼리 네 개로 끝난다.

- **공격 반영은 동기다.** `PostLiked`, `CommentCreated`, `CommentLiked`는
  `@EventListener`(`monster/application/AttackListener`)가 post 트랜잭션 안에서
  받는다. 공감 저장과 HP 감소가 함께 성공하거나 함께 실패한다. 규칙은 이 순서로
  본다: 작성자의 행동은 무시한다, 몬스터가 없으면 반영하지 않는다(생성 때 소급
  반영한다), `monster_hp_log`의 유일 제약에 걸리지 않고 기록이 들어갔을 때만 HP를
  줄인다, HP는 0에서 멈추고 처치된 순간에 `MonsterDefeated`를 한 번 낸다. 감소량은
  공감 1, 회원별 첫 댓글 3, 댓글 공감 1이다.
- **소급 반영.** `MonsterFactory`는 `EmotionAnalyzed`를 커밋 뒤 비동기로 받아
  몬스터를 만들고, 같은 트랜잭션에서 `PostApi.attacksSoFar`로 그때까지 쌓인 공격을
  `retroactive = true`로 반영한다. 같은 이벤트가 다시 와도 몬스터는 하나다.
- **글 잠금과 잠금 순서.** 공격 반영, 몬스터 생성, 글 삭제는
  `shared/lock/PostLock`(두 정수 키 `pg_advisory_xact_lock(NAMESPACE, key(postId))`)
  으로 같은 글에서 겹치지 않는다. 두 모듈이 같은 키를 써야 해서 `shared`에 있다.
  잠금은 언제나 `posts`나 `comments` 행을 먼저 잠그고(카운터나 `deleted_at`을
  UPDATE) `PostLock`을 나중에 잡는다. 이 순서를 바꾸면 교착이 생긴다.
  `AttackListener`는 잠금을 잡은 뒤에 몬스터를 찾아야 생성 중인 몬스터를 놓치지
  않는다. 작성 제한(`PostRateLimit`)은 다른 이름공간의 두 정수 키를, 이메일 가입
  잠금은 bigint 키 하나를 써서 서로 키 공간이 겹치지 않는다.
- **분석 재시도.** `PostCreatedListener`가 `emotion_analysis` 행을 만들고 바로 한 번
  시도한다. 실패하면 `next_attempt_at`을 `min(30초 x 2^(n-1), 5분)` 뒤로 미루고,
  `RetryScheduler`가 10초마다 차례가 된 행을 `FOR UPDATE SKIP LOCKED`로 맡아 다시
  시도한다. 글을 쓴 지 24시간이 지나면 `DEFAULTED`(무기력, 낮음)로 끝낸다. LLM
  호출은 트랜잭션 밖에서 하고, 맡기와 기록은 각각 짧은 트랜잭션이다. 값은
  `ogu.emotion.retry.*`에 있다.
- **ai 모듈.** `SpringAiEmotionAnalyzer`는 Resilience4j 타임아웃(`ogu.ai.timeout`,
  20초)과 서킷 브레이커 `emotionAnalyzer`를 거친다. SDK의 HTTP 타임아웃도 같은
  값이라 어느 쪽이 먼저 알아채든 `TIMEOUT`으로 분류한다. SDK 자체 재시도는 끄고
  재시도는 emotion의 일정에 맡긴다. 모델 응답 원문은 로그에 남기지 않는다. 키가
  비어 있으면 외부로 호출하지 않는 `DisabledEmotionAnalyzer`를 쓰고, `e2e`
  프로필은 본문 머리말(`[불안:낮음]`, `[실패:1]`)로 결과를 정하는 `FakeEmotionAnalyzer`를 쓴다.
- **이벤트 재전송.** 비동기 리스너가 실패해 끝나지 않은 발행은
  `shared/config/EventPublicationResubmitter`가 1분마다, 2분보다 오래된 것만 다시
  보낸다. 처음 처리를 포함해 10번(`ogu.events.resubmit.max-attempts`) 실패한
  발행은 Spring Modulith 2.1의 `ResubmissionOptions` 필터로 건너뛰고 WARN을 한 번
  남긴다. 상한은 프로세스 하나 안에서만 지켜진다.
  `spring.modulith.events.republish-outstanding-events-on-restart: true`라서 재시작(배포)
  때마다 끝나지 않은 발행은 상한에 걸린 것까지 모두 한 번씩 다시 나가고, WARN 중복
  방지도 메모리에 있어 재시작하면 다시 한 번 남는다. 행은 지우지 않으므로 원인을 고친
  뒤에는 앱을 재시작하거나, 그 행의 `event_publication.completion_attempts`를 상한보다
  작게(예: 0) 되돌려 1분 주기 재전송이 다시 맡게 한다.
  끝난 발행도 `event_publication`에 계속 쌓이므로, 이벤트에는 글 본문 같은 내용을 싣지 않고
  ID만 담는다. 끝난 행을 지우거나 완료 모드를 바꾸는 보존 정책은 아직 없고 다음 작업으로 남겨 두었다.
- **운영 기동 조건.** AI 키 검사는 `shared/config/ProdAiSettingsCheck`에 있다.
  인증 쪽 검사(`ProdAuthSettingsCheck`)는 `member`에 있으니 둘을 함께 본다.
  `prod`에서 `AI_API_KEY`나 `AI_MODEL`이 비어 있으면 앱이 뜨지 않는다. `AI_BASE_URL`은
  비우면 바인딩에서 기본 주소로 돌아가고, compose도 같은 기본값을 채운다.
- **글자 수.** 본문은 사람이 보는 글자 단위로 센다(`shared/text/Grapheme`,
  `BreakIterator`를 `Locale.ROOT`로 고정). 웹의 `Intl.Segmenter`와 같은 값을 낸다.
- 경로 변수나 쿼리 파라미터 형식이 틀리면(`MethodArgumentTypeMismatchException`)
  400 `INVALID_REQUEST`다. 변환기가 없는 `ConversionNotSupportedException`은
  서버 문제라 500으로 남긴다.

## Gotchas

- Kotlin can't express package-level annotations: module metadata such as
  `@ApplicationModule(type = OPEN)` lives in `src/main/java/**/package-info.java`.
  Each module's `package-info.java` Javadoc doubles as the module description
  in Documenter-generated docs (Modulith 2.0+).
- Modulith config properties live under `spring.modulith.events.*` /
  `spring.modulith.runtime.*` — the bare `spring.modulith.republish-…` path is
  deprecated since 1.3.
- detekt runs with a pinned Kotlin version and ktlint is pinned to 1.7.1 in
  `build.gradle.kts` — do not remove those pins when bumping versions.
- Local compose maps Postgres to host port **5433** (5432 is often taken);
  spring-boot-docker-compose auto-detects the mapped port.
- The Postgres image is `pgvector/pgvector:pg17` everywhere (adds the `vector`
  extension for M6's embedding columns). spring-boot-docker-compose infers
  connection type from the image name and does not recognize
  `pgvector/pgvector` as Postgres on its own, so `compose.yaml` carries the
  `org.springframework.boot.service-connection: postgres` label to force it;
  `TestcontainersConfiguration` uses `asCompatibleSubstituteFor("postgres")`
  for the same reason.
- `docs/` is intentionally git-ignored (local working documents).
- CLAUDE.md is a symlink to this file — edit AGENTS.md only.
