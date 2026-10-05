# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project Overview

오구오구 백엔드: Kotlin + Spring Boot + Spring Modulith 모듈러 모놀리스.
kotlin-spring-modulith-template에서 이식했다. 단일 Gradle 모듈이며 루트 패키지는
`com.ogu`. 지금 모듈은 `shared`(OPEN), `member`, `post`, `ai`, `emotion`,
`monster`, `feed` 일곱 개다. 모듈 목록과 의존 방향은
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
  `SecurityPaths.ONBOARDING_ALLOWED`에 있다: `/api/v1/members/me`,
  `/api/v1/members/nickname-availability`, `/api/v1/members/me/onboarding`,
  `/api/v1/auth/logout`. 그 밖의 인증 필요 경로는 `OnboardingGuard`가
  `403 ONBOARDING_REQUIRED`로 막는다.
- 운영(`prod` 프로필) 기동 조건은 `ProdAuthSettingsCheck`가 강제한다: JWT
  비밀키가 로컬 개발용 고정 값이면 안 되고, `OGU_BFF_KEY`가 비어 있으면 안 되고,
  카카오·구글 client id/secret이 모두 있어야 하고, 허용 redirect URI는 전부
  `https`여야 하고, `prod`와 `e2e` 프로필을 동시에 켤 수 없다(e2e의 비밀 값은
  `infra/compose.e2e.yaml`에 커밋된 고정 값이라 prod에 같이 켜면 토큰을 위조할 수 있다).

## Core loop (`post`, `ai`, `emotion`, `monster`, `feed`)

003-core-loop에서 모듈 다섯 개가 생겼다. 허용 의존은 각 모듈의 `package-info.java`에
적혀 있고, 다른 모듈에 보이는 것은 아래 루트 타입뿐이다.

| 모듈 | 공개 파사드와 타입 | 발행 이벤트 | 받는 이벤트 |
|---|---|---|---|
| `post` | `PostApi`(`find`, `page`, `likedPostIds`, `attacksSoFar`), `PostSummary`, `PostPage`, `Attack`, `AttackAction` | `PostCreated`(커밋 후), `PostLiked`, `CommentCreated`, `CommentLiked`(같은 트랜잭션) | - |
| `ai` | `EmotionAnalyzer`, `EmotionClassification`, `EmotionAnalysisFailed` | - | - |
| `emotion` | `EmotionApi`(`findByPostIds`), `EmotionView`, `AnalysisStatus` | `EmotionAnalyzed` | `PostCreated` |
| `monster` | `MonsterApi`(`findByPostIds`, `hasCountedComment`), `MonsterView` | `MonsterDefeated` | `EmotionAnalyzed`, `PostLiked`, `CommentCreated`, `CommentLiked` |
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
- **운영 기동 조건.** AI 키 검사는 `shared/config/ProdAiSettingsCheck`에 있다.
  인증 쪽 검사(`ProdAuthSettingsCheck`)는 `member`에 있으니 둘을 함께 본다.
  `prod`에서 `AI_API_KEY`가 비어 있으면 앱이 뜨지 않는다.
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
