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
- 프로필 수정(004 US5, research R13)은 `PATCH /api/v1/members/me`이고 `ProfileService`가 한다. 보낸 항목만 바꾸고,
  하나도 없으면 `400 INVALID_REQUEST`다. 회원 행을 `FOR UPDATE`로 잠그고, 닉네임은 온보딩과 같은 `Nickname.of`로
  검증한다. 소문자 키가 내 지금 키와 같으면(대소문자만 바꿈) 중복 확인을 건너뛰고, 다르면 `existsByNicknameKey`로
  본 뒤 저장 때 `member_nickname_key_key` 위반을 `409 NICKNAME_TAKEN`으로 바꾼다. 온보딩 전 회원은 필터
  (`OnboardingGuard`)와 서비스가 모두 `403 ONBOARDING_REQUIRED`로 막는다. 글의 직군과 경력은 작성 시점 스냅숏이라
  건드리지 않고, 닉네임은 어디서나 `MemberApi.getMembers`로 지금 값을 읽으므로 이벤트를 내지 않는다. access 토큰에
  닉네임이 없어 토큰도 새로 주지 않는다.
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
  쓰기는 전용 실행기(`notificationStreamExecutor`, 4스레드)가 하고, 연결마다 한 번에 하나만 돈다. 하트비트(`: hb` 주석과 `ping` 이벤트, 25초)와
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
  알림 목록과 읽음(004 US2, research R10~R12)은 `NotificationController`에 있다. 목록(`GET /api/v1/notifications`)은
  `NotificationQueryService.page`가 `seq DESC` 키셋으로 읽고(커서는 `base64url("{seq}")`, size 1~50), 쿼리는 쪽 크기와
  상관없이 알림, `PostApi.previews`, `MemberApi.getMembers` 세 개다. 글 미리보기와 행동한 회원은 스트림과 같은
  `NotificationViewAssembler`와 `NotificationResponse`가 만들어 목록과 실시간 이벤트의 모양이 갈라지지 않는다. 지운 글은
  `post`가 null이다. 읽음은 `NotificationReadService`가 한다: 하나 읽음(`PUT .../{id}/read`)은 남의 알림, 없는 알림,
  보관 기간이 지난 알림을 구분하지 않고 `404 NOTIFICATION_NOT_FOUND`로 답하고, 모두 읽음(`POST .../read-all`)은
  `seq <= upToSeq`만 바꾼다(`upToSeq`는 그 회원의 지금 `last_seq`를 넘지 않게 낮춘다). 하나 읽음은 알림 ID로 읽으므로
  기다리는 사이에 공감이 더해진 묶음은 그 공감까지 읽음이 된다(의도한 동작). 읽음이 실제로 바뀌었을 때만 커밋 뒤 `{memberId}:r` 신호를 보내고, `SseHub`가 그 회원의 모든
  연결에 `unread-count` 이벤트(ID 없음, 재전송 안 함)를 보낸다. 읽은 공감 묶음은 부분 유일 인덱스에서 빠지므로 다음
  공감은 새 묶음을 만든다. Redis가 내려가 있으면 읽음 신호가 닿지 않으므로, 줄어든 안전망 주기(5초)에만
  `SseHub.drainLagging(withUnreadCounts = true)`가 회원별 안 읽은 수를 쿼리 하나로 읽어 마지막으로 보낸 수와 다른 연결에 `unread-count`를
  보낸다. `NotificationPurgeJob`이 매일 04:00(한국 시간)에 만든 지 90일이 지난 알림을 1,000행씩
  (`MATERIALIZED` CTE와 `FOR UPDATE SKIP LOCKED`, 한 문장이 한 트랜잭션) 지우고, 끝난 지 7일이 지난 이벤트 발행도 지운다
  (`CompletedEventPublications.deletePublicationsOlderThan`). ShedLock 없이 두 인스턴스가 같이 돌아도 된다.
  두 단계는 따로 실패한다: 한쪽이 실패해도 다른 쪽은 돌고, 단계마다 ERROR를 남긴 뒤 처음 실패를 다시 던진다.

## Core loop (`post`, `ai`, `emotion`, `monster`, `feed`)

003-core-loop에서 모듈 다섯 개가 생겼다. 허용 의존은 각 모듈의 `package-info.java`에
적혀 있고, 다른 모듈에 보이는 것은 아래 루트 타입뿐이다.

| 모듈 | 공개 파사드와 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `post` | `PostApi`(`find`, `page`, `likedPostIds`, `attacksSoFar`, `findComment`, `previews`), `PostActivityApi`(`pageByAuthor`, `pageCommentsByAuthor`, `pageLikedBy`, `liveRefsByAuthor`, `liveIds`), `PostSummary`, `PostPage`, `MyCommentPage`, `Attack`, `AttackAction`, `CommentSummary` | `PostCreated`(커밋 후), `PostLiked`, `CommentCreated`, `CommentLiked`(같은 트랜잭션) | - |
| `ai` | `EmotionAnalyzer`, `EmotionClassification`, `EmotionAnalysisFailed` | - | - |
| `emotion` | `EmotionApi`(`findByPostIds`), `EmotionView`, `AnalysisStatus` | `EmotionAnalyzed` | `PostCreated` |
| `monster` | `MonsterApi`(`findByPostIds`, `hasCountedComment`, `damagerIds`, `statRows`, `defeatedPostIdsDamagedBy`), `MonsterView`, `MonsterStatRow` | `MonsterSpawned`, `MonsterDefeated` | `EmotionAnalyzed`, `PostLiked`, `CommentCreated`, `CommentLiked` |
| `feed` | 없음(HTTP API만) | - | - |

`post`는 몬스터를 모른다. 글에 감정과 몬스터를 붙여 보여 주는 일은 `feed`가
파사드를 한 번씩 불러 조합한다. 피드 한 쪽은 쿼리 네 개로 끝난다.

- **마이페이지 목록(004 US3, research R12).** `feed/presentation/MyPageController`가
  `GET /api/v1/members/me/posts`, `/comments`, `/liked-posts`를 받는다. 경로는 회원 아래지만 글, 몬스터,
  감정을 모아야 해서 `feed`에 있다. 내가 쓴 글과 공감한 글은 `PostActivityApi.pageByAuthor`, `pageLikedBy`가 준
  쪽을 피드와 같은 `FeedAssembler`로 조합해 응답이 `FeedPage` 그대로이고 쿼리는 4개다. 내 댓글은
  `PostActivityApi.pageCommentsByAuthor`의 쿼리 하나다. 읽는 쪽은 `post/application`의 `MyPostsReader`(`id DESC`,
  커서는 피드와 같은 `PostCursor`), `MyCommentsReader`(`id DESC`, 살아 있는 글의 살아 있는 댓글, 글 앞 50글자),
  `LikedPostsReader`(`(created_at DESC, post_id DESC)`, 커서는 공감 시각의 마이크로초와 글 ID)다. size는 1~50이고
  벗어나거나 커서가 틀리면 `400 INVALID_REQUEST`다.
- **감정 통계(004 US4, research R12).** `GET /api/v1/members/me/emotion-stats`는 `feed/application/EmotionStatsQuery`가
  파사드 네 번(`PostActivityApi.liveRefsByAuthor`, `MonsterApi.statRows`, `MonsterApi.defeatedPostIdsDamagedBy`,
  `PostActivityApi.liveIds`)으로 읽어 메모리에서 센다. `posts`와 `monsters`는 소유 모듈이 달라 SQL로 조인하지 않는다.
  비율은 정수로 반올림하고 합이 100이 아니면 가장 많은 감정에서 맞춘다. 가장 많은 감정은 수가 같으면 가장 최근에
  생긴 몬스터의 감정이다. 주별 추이는 주입한 `Clock`으로 한국 시간 월요일 0시 기준 8주를 만들고, 몬스터가 생긴
  시각이 아니라 글을 쓴 시각으로 묶는다. 함께 물리친 몬스터는 내가 HP를 실제로 줄인 처치된 몬스터 가운데 글이
  살아 있는 것의 수다. `PostApi`가 detekt의 함수 수 한도에 닿아, 회원 한 명의 활동을 읽는 조회는 `PostActivityApi`로 나눴다.
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
  ID만 담는다. 끝난 지 7일이 지난 행은 `notification`의 `NotificationPurgeJob`이 매일 지운다(004 research R10).
  끝나지 않은 행은 지우지 않는다.
- **운영 기동 조건.** AI 키 검사는 `shared/config/ProdAiSettingsCheck`에 있다.
  인증 쪽 검사(`ProdAuthSettingsCheck`)는 `member`에 있으니 둘을 함께 본다.
  `prod`에서 `AI_API_KEY`나 `AI_MODEL`이 비어 있으면 앱이 뜨지 않는다. `AI_BASE_URL`은
  비우면 바인딩에서 기본 주소로 돌아가고, compose도 같은 기본값을 채운다.
- **글자 수.** 본문은 사람이 보는 글자 단위로 센다(`shared/text/Grapheme`,
  `BreakIterator`를 `Locale.ROOT`로 고정). 웹의 `Intl.Segmenter`와 같은 값을 낸다.
- 경로 변수나 쿼리 파라미터 형식이 틀리면(`MethodArgumentTypeMismatchException`)
  400 `INVALID_REQUEST`다. 변환기가 없는 `ConversionNotSupportedException`은
  서버 문제라 500으로 남긴다.

## Safety (`safety` module)

005-safety에서 생겼다. 허용 의존은 `shared`, `post`, `ai`, `member`이고 `notification`이 `safety`의 이벤트를 받는다.
`post`는 `safety`를 모른다.

| 모듈 | 공개 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `safety` | `RiskLevel` | `RiskDetected`, `ContentRestored`, `ReviewResolved`(모두 커밋 후 `notification`이 받는다) | `PostWritten`, `CommentWritten`, `PostRemoved`, `CommentRemoved`(post 트랜잭션 안에서 동기) |
| `post`(더한 것) | `PostModerationApi`(`contentOf`, `contentsOf`, `markRisk`, `markReviewRequested`, `hide`, `unhide`, `scan`), `PostApi.findVisible`, `findForViewer`, `ContentType`, `ContentSafety`, `ModerationTarget` | `PostWritten`, `CommentWritten`(쓰거나 고칠 때), `PostRemoved`, `CommentRemoved` | - |
| `ai`(더한 것) | `RiskClassifier`, `ClassifiedRisk`, `RiskClassificationFailed` | - | - |
| `shared`(더한 것) | `text/ContentMask`(구현은 `safety`의 `ProfanityMask`) | - | - |

- **숨김 상태는 `post`의 열이다.** `posts`와 `comments`의 `hidden_at`, `hidden_reason`, `risk_level`,
  `review_requested_at`은 `PostModerationApi`로만 바꾼다. 엔티티에서는 읽기 전용이다
  (`insertable = false, updatable = false`). 다른 모듈이 숨김 여부를 `safety`에 묻지 않아도 된다.
- **조회 조건은 둘이다.** 다른 회원에게 보이는 것은 `Visibility.visible`(지우지 않았고 숨기지 않았다),
  작성자에게 보이는 것은 `Visibility.ownedOrVisible`(지우지 않았고, 숨겼어도 내 것이면 보인다)이다.
  글이나 댓글을 읽는 쿼리를 새로 쓸 때 `deleted_at is null`만 쓰면 숨긴 글이 샌다.
  `HiddenContentMatrixTests`가 경로마다 확인한다. `PostApi.find`는 지우지 않은 글(숨긴 것 포함)이라
  다른 회원에게 보일 것을 정할 때는 `findVisible`이나 `findForViewer`를 쓴다.
- **키워드 판정은 저장과 같은 트랜잭션에서 돈다.** `ScreeningListener`가 `PostWritten`, `CommentWritten`을
  동기 `@EventListener`로 받아 판정하고 위기면 그 자리에서 숨긴다. 저장과 숨김이 함께 커밋되므로 목록에 있는
  위기 표현이 든 글은 한 번도 공개되지 않는다(SC-001). 여기서는 메모리 계산과 짧은 쓰기만 한다.
  글과 댓글을 고치는 쪽은 이벤트를 내기 전에 flush한다(`PostModerationService`가 JdbcClient라서).
- **AI 분류는 커밋 뒤에 따로 돈다.** `RiskClassificationRunner`가 트랜잭션 밖에서 `RiskClassifier`를 부르고,
  실패하면 `risk_assessment.next_attempt_at`으로 다시 시도한다(30초부터 두 배씩, 최대 5분, 24시간 뒤 `FALLBACK`).
  감정 분석의 `AnalysisStore`, `AnalysisRetryScheduler`와 같은 구조다. 판정은 키워드와 AI 가운데 높은 쪽이고,
  올리기만 한다. 위기 표현을 지워 고쳐도 숨김은 풀리지 않는다. 고친 뒤 늦게 온 분류 결과는 `content_version`으로 버린다.
- **낱말 목록은 `TermCache`가 메모리에 올려 둔다.** 30초마다 행 수와 가장 늦은 `updated_at`만 보고 바뀌었을 때만
  다시 읽는다. 읽지 못하면 마지막 목록을, 한 번도 읽지 못했으면 `SafetyTerms.BUILT_IN`을 쓴다.
  낱말은 `TextNormalizer.termOf`로 다듬은 꼴로 저장한다. 시드를 고치면 `KeywordRuleEvalTest`가 평가 묶음
  (`src/test/resources/safety/eval-set.tsv`)으로 다시 잰다.
- **욕설은 읽을 때 가린다.** 원문은 바꾸지 않는다. `feed`(피드와 내 활동의 미리보기, 글 상세),
  `post`(댓글 목록, 내 댓글의 글 앞부분, 알림에 붙는 `previews`)가 `ContentMask.maskFor(viewerId, authorId, text)`로
  가린다. 작성자에게는 원문이다. 미리보기는 가린 뒤 자른다. 본문을 내보내는 응답을 새로 만들면 여기를 거쳐야 한다.
  감정 분석과 위험 감지는 원문으로 한다.
- **운영자.** `member.role`이 `OPERATOR`인 회원이다. 지정은 SQL로 한다(005 quickstart). `/api/v1/operator/**`는
  `OperatorInterceptor`가 입력을 읽기 전에 막아, 운영자가 아니면 언제나 404다. 역할은 요청마다 DB에서 읽고
  JWT에 넣지 않는다. 웹의 BFF는 이 경로를 넘기지 않는다. 상태를 바꾼 처리만 `moderation_action`에 남는다.
- **한 번 도는 작업과 정리.** `SafetyBackfill`이 기동 뒤 따로 도는 스레드에서 안전 기능 전에 쓰인 글과 댓글을
  키워드 규칙으로 훑는다(`safety_backfill` 표지로 이어서 하고 끝나면 다시 돌지 않는다. `ogu.safety.backfill.enabled`).
  `SafetyPurgeJob`이 매일 04:30(한국 시간)에 1년 지난 기록을 지운다. 열린 신고와 재검토 요청은 남긴다.
- **모델.** 기본 모델은 `openai/gpt-oss-20b`(NVIDIA)다. 답보다 추론 과정을 먼저 내므로 응답 토큰 상한
  (`ogu.ai.max-tokens` 600, `risk-max-tokens` 300)을 넉넉히 두고 `reasoning-effort`를 `low`로 보낸다. 상한이 작으면
  답이 비어 온다. 모델이나 프롬프트를 바꾸면 `OGU_RUN_AI_EVAL=true`로 `RiskEvalWithAiTest`와
  `EmotionAnalyzerLiveTest`를 돌려 본다. 가짜 분석기를 쓰는 테스트는 공급자가 모델을 내려도 알아채지 못한다
  (2026-07에 앞선 기본 모델이 그렇게 내려갔다). 위기 재현율은 목표에 못 미친다(005 quickstart).
- **민감 정보.** 이벤트, 로그, `risk_assessment`에 본문과 걸린 표현을 싣지 않는다(`SensitiveLogTests`).
  운영자 조회 응답만 원문을 싣는다. 알림 문구에는 단계와 글 내용이 없다.
- **테스트 표지.** 가짜 분류기(`FakeRiskClassifier`)는 본문의 `[위기]`, `[우려]`, `[위험분류실패]`,
  `[위험분류실패:N]`을 읽는다. 키워드에 걸리는 문장은 `SafetyFixture`의 상수를 쓴다.

## Raid (`raid` module)

006-raid에서 생겼다. 허용 의존은 `shared`, `post`, `emotion`, `member`다. `notification`과 `feed`가 `raid`에 의존한다.
`monster`와는 서로 모른다. 보스는 글의 몬스터와 다른 것이다.

| 모듈 | 공개 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `raid` | `RaidApi`(`participantIds`, `defeatedCount`) | `RaidBossDefeated`(커밋 후 `notification`이 받는다) | - |
| `post`(더한 것) | `PostApi.visibleIdsSince` | - | - |
| `shared`(더한 것) | `realtime/TopicBroadcaster`(구현은 `notification`의 `StreamTopicBroadcaster`) | - | - |

- **판단은 Redis의 스크립트가 한다.** 공격, 옮길 것 꺼내기, 끝내기, 올리기, 읽기가 각각 Lua 스크립트 하나다
  (`src/main/resources/redis/raid-*.lua`, `RaidRedis`가 감싼다). 공격 스크립트 안에서 쿨다운, HP, 기여, 처치가
  함께 바뀐다. 이 규칙을 Kotlin 쪽에서 나눠서 다시 구현하지 않는다. 나누면 그 사이에 다른 공격이 끼어든다.
- **불변식.** 받아들인 공격 수 = 줄어든 HP = 기여의 합. `RaidConcurrencyTests`와 k6(`infra/k6/run-raid-load.sh`)가 본다.
- **Postgres는 기록이고 뒤따라 적힌다.** `RaidFlusher`가 1초마다 절댓값을 적는다(기여는 `greatest`, HP는 `least`).
  살아 있는 보스의 정확한 값은 Redis에 있다. 살아 있는 보스의 `raid_boss.hp`를 읽어 판단하지 않는다.
- **처치는 기록에 남긴 뒤에 알린다.** `RaidFinisher.finish`가 모든 기여를 적고 `WHERE status = 'ALIVE'` 조건으로
  끝낸 뒤 같은 트랜잭션에서 `RaidBossDefeated`를 낸다. 겹쳐 불려도 이벤트는 하나다. 마무리가 실패하면 다음 옮기기가
  다시 한다.
- **Redis가 비어 있는 모든 경우를 `RaidLoader`가 다룬다.** 처음, Redis가 다시 뜬 뒤, 새 보스가 나온 뒤가 같은 길이다.
  살아 있는 보스의 HP는 기록된 기여의 합으로 다시 계산해 올린다. 올릴 때마다 `epoch`가 커지고 웹은 그것으로
  "값이 돌아갔다"를 알아본다.
- **Redis를 쓸 수 없으면 `RaidUnavailableException` 하나로 온다.** 공격은 503 `RAID_UNAVAILABLE`, 조회는 마지막
  기록과 `available = false`다. 주기 작업은 조용히 건너뛴다. 다른 기능에 번지지 않게 한다(`RaidRedisOutageTest`).
- **살아 있는 보스는 하나다.** 부분 유일 인덱스 `raid_boss_alive_key`가 지킨다. 스케줄러의 잠금에 기대지 않는다.
  `RaidBossLifecycle.tick`은 여러 인스턴스가 함께 돌아도 된다.
- **실시간.** `RaidBroadcaster`가 전용 스레드에서 250ms마다 돈다. `raid` 주제를 듣는 연결이 있을 때만 Redis를 읽고,
  지난번과 다르거나 새 연결이 붙었을 때만 `TopicBroadcaster`로 보낸다. 공용 스케줄러에 두면 부하 중에 주기가
  늘어진다. 스트림은 연결할 때 `topics`로 고른 주제만 보내고, 주제 소식에는 SSE `id`가 없다.
- **다른 회원의 정보를 싣지 않는다.** 응답과 이벤트에 나가는 것은 보스의 상태, 참여자 수, 요청한 회원의 기여뿐이다.
  순위나 기여 목록을 주는 조회를 더하지 않는다(스펙 FR-013).
- **테스트.** 컨텍스트들이 Postgres와 Redis를 함께 쓰고 다른 컨텍스트의 주기 작업도 계속 돈다. 보스를 지우지 말고
  `RaidFixture.freshBoss`로 바꿔 놓는다. 시계를 움직이는 테스트는 `ogu.raid.lifecycle-scheduler-enabled=false`로
  주기 작업을 끈다. 켜 두면 앞서간 시계가 다른 테스트의 보스를 물러나게 한다.

## Recommend (`recommend` module)

007-recommend에서 생겼다. 허용 의존은 `shared`, `post`, `ai`, `emotion`이다. `feed`가 `recommend`에 의존한다.

| 모듈 | 공개 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `recommend` | `RecommendApi.similar`(글 ID와 근거만 돌려준다) | - | `PostWritten`, `PostRemoved`(커밋 뒤) |
| `ai`(더한 것) | `Embedder`, `Embedding`, `EmbeddingFailed` | - | - |
| `post`(더한 것) | `PostSelectionApi`(`visibleIds`, `pageOf`, `authorsAfter`) | - | - |
| `emotion`(더한 것) | `EmotionApi.recentPostIds` | - | - |

- **`recommend`는 글 ID만 고른다.** 본문을 싣거나 카드를 만들지 않는다. 조립은 `feed`의 `SimilarPostsQuery`가
  `PostSelectionApi.pageOf`와 `FeedAssembler`로 한다. 피드와 같은 길이라 숨김, 욕설 가리기, 몬스터가 그대로 적용된다.
  추천에만 쓰는 조립을 따로 만들지 않는다. 만들면 숨긴 글이 새는 길이 하나 더 생긴다.
- **보이는지는 조회할 때마다 `post`에 묻는다.** `post_embedding`에 숨김이나 삭제를 복사해 두지 않는다. 가까운 글을
  넉넉히(`candidates`) 뽑아 `visibleIds`로 거른 뒤 5개를 남긴다.
- **임베딩은 커밋 뒤, 트랜잭션 밖에서 만든다.** `EmbeddingListener`가 `PostWritten`을 받아 행을 만들고 바로 한 번
  시도한다. 실패하면 30초부터 두 배씩 최대 5분 간격으로 `EmbeddingRetryScheduler`가 다시 하고 24시간 뒤 `GIVEN_UP`이다.
  글쓰기는 임베더를 기다리지 않는다.
- **늦게 온 결과는 버린다.** 글을 고치면 `requested_seq`가 오른다. 결과를 적을 때 번호가 다르면 적지 않는다. 고쳐서
  다시 기다리는 동안에는 이전 값이 남아 추천이 비지 않는다.
- **차례는 나중에 요청된 글부터다.** 이미 있는 글이 밀려 있어도 새 글의 재시도가 그 뒤에 서지 않는다
  (`PostEmbeddingRepository.lockDue`의 `order by requested_at desc`). `next_attempt_at` 순서로 되돌리지 않는다.
- **모델이 다른 값은 견주지 않는다.** 가까운 글 찾기는 지금 모델(`Embedder.model`)로 만든 값끼리만 한다. 모델을
  바꾸면 `EmbeddingBackfill`이 기동 뒤에 옛 값을 차례로 다시 만든다. 그동안은 같은 감정의 글이 대신 보인다.
- **대비책은 같은 감정의 최근 글이다.** 기준(`ogu.recommend.max-distance`) 안의 글이 없으면 `SAME_EMOTION`, 감정
  분석도 없으면 `NONE`이다. 응답의 `pending`은 이 글의 값이 아직 만들어지는 중이라는 뜻이다.
- **임베더는 채팅 모델의 주소와 키를 함께 쓴다.** `HttpEmbedder`는 Spring AI가 아니라 JDK `HttpClient`로 부른다.
  공급자가 `input_type`을 요구하는데 OpenAI 표준에 없다. 키가 없으면 `FakeEmbedder.Disabled`가 바로 실패해 본문이
  나가지 않는다. 로그와 `last_error`에는 실패의 분류만 남긴다.
- **지금 모델은 품질 목표에 못 미친다.** `specs/007-recommend/research.md`의 R9에 측정이 있다. 모델을 바꾸면
  `RecommendEvalTest`(`OGU_RUN_AI_EVAL=true`)로 다시 재고 `max-distance`를 정한다. 차원이 2048이 아니면
  `halfvec(2048)` 열과 색인을 바꾸는 마이그레이션이 필요하다.
- **테스트 표지.** 가짜 임베더(`FakeEmbedder`)는 본문의 `[주제:이름]`, `[멀기:N]`, `[임베딩실패]`, `[임베딩실패:N]`을
  읽는다. 주제 이름은 `RecommendFixture.topic()`으로 테스트마다 새로 짓는다. 시계를 움직이는 테스트는
  `ogu.recommend.retry.scheduler-enabled=false`와 `ogu.recommend.backfill.enabled=false`로 주기 작업을 끈다.

## Weekly report (`report` module)

008-weekly-report에서 생겼다. 허용 의존은 `shared`, `post`, `monster`, `emotion`, `ai`, `member`다. `notification`이
`report`의 이벤트를 받는다. `member`는 조회 API가 로그인한 회원을 받는 데만 쓴다.

| 모듈 | 공개 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `report` | - (HTTP API만) | `WeeklyReportPublished`(리포트를 넣은 트랜잭션에서. `notification`과 자기 모듈이 받는다) | - |
| `ai`(더한 것) | `WeeklyLetterWriter`, `WeeklyLetterInput`, `WeeklyLetterFailed` | - | - |
| `post`(더한 것) | `PostReportApi`(`authorIdsBetween`, `postsBetween`, `receivedBetween`) | - | - |
| `monster`(더한 것) | `MonsterApi.defeatedCountBetween` | - | - |

- **한 주는 한국 시간 월요일 0시부터다.** `WeekRange` 하나로 자른다. 서버의 시간대와 상관없다. 마이페이지의 주별
  추이와 같은 기준이다.
- **리포트 만들기는 "없는 것을 채우는" 주기 작업이다.** `WeeklyReportJob.tick`이 지난주 대상 회원 가운데 리포트가
  없는 회원을 만든다. 한 번 도는 배치로 바꾸지 않는다. 채우는 방식이라 일부 실패, 이어 하기, 늦게 뜬 서버가 같은 길로
  풀린다. 대상은 지난주뿐이고, 다 훑으면 `weekly_report_run`에 적고 다시 훑지 않는다.
- **"한 번만"은 유일 제약이 지킨다.** `weekly_report_member_week_key`에 기대어 `ON CONFLICT DO NOTHING`으로 넣고,
  넣은 쪽만 `WeeklyReportPublished`를 낸다. ShedLock을 넣지 않는다. 주기 작업은 여러 인스턴스가 함께 돌아도 된다.
- **수치는 발행할 때 한 번 세어 저장한다.** 조회는 저장된 값을 그대로 준다. 뒤에 글을 지워도 고치지 않는다.
  리포트에는 본문, 닉네임, 글 번호가 없다.
- **다른 모듈의 데이터는 파사드로 읽어 메모리에서 센다.** `posts`, `emotion_analysis`, `monsters`를 조인하지 않는다.
  감정은 분석이 끝난(`ANALYZED`) 글만 센다. 기본값을 받은 글(`DEFAULTED`)은 회원의 마음이 아니라 분석되지 않은 글로 센다.
- **편지는 리포트와 따로다.** 리포트와 알림은 AI를 부르기 전에 나간다. `WeeklyLetterListener`가 커밋 뒤에 한 번
  시도하고, 실패하면 `WeeklyLetterRetryScheduler`가 30초부터 최대 5분 간격으로 다시 한다. 발행한 지 24시간이 지나면
  `GIVEN_UP`이다. 편지가 채워져도 알림은 다시 보내지 않는다.
- **공급자에 보내는 것은 `WeeklyLetterInput`이 전부다.** 그 주의 수치뿐이고 본문, 닉네임, 회원 번호의 자리가 없다.
  자리를 더하려면 스펙 008의 FR-010부터 고친다. 0인 수치와 없는 감정은 요청에서 줄째로 빠지고 앞 주의 수치는 보내지
  않는다. 실제 모델이 그것을 받으면 "공감이 없었다"고 쓰거나 두 주를 틀리게 견주었다(research R7). 하지 말라고 적는
  것보다 모르게 하는 쪽이 확실했다.
- **AI가 쓴 글은 확인한 뒤에만 보인다.** `WeeklyLetterValidator`(빈 답, 300자, 보낸 수치에 없는 숫자, 이모지)와
  `ContentMask`(가려질 낱말)를 통과해야 편지가 된다. 걸리면 실패로 적고 다시 시도한다.
- **위기 글이 있던 주는 AI에게 맡기지 않는다.** 그 주에 쓴 글 가운데 `risk_level = CRISIS`가 있으면 편지의 상태를
  처음부터 `SUPPORT`로 넣는다. 화면이 정해 둔 문구와 도움받을 곳을 보인다. 문구와 전화번호는 리포트에 저장하지 않는다.
- **테스트.** 가짜 편지 쓰기는 본문을 받지 않아 수치로 결과가 정해진다(받은 댓글 13이면 계속 실패, 쓴 글 7이면 두 번
  실패). 리포트 테스트는 `ReportTestConfiguration`으로 시계를 움직이고 `ogu.report.scheduler-enabled=false`로 주기
  작업을 끈 채 직접 부른다. 테스트마다 `ReportFixture.newWeek()`로 아직 쓰지 않은 주를 받는다. 같은 주를 쓰면 앞
  테스트가 끝냈다고 적은 기록 때문에 아무것도 만들어지지 않는다. e2e는 글의 때를 한 주 앞으로 옮기고
  `weekly_report_run`을 지운다.

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
