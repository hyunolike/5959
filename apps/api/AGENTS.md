# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project Overview

오구오구 백엔드: Kotlin + Spring Boot + Spring Modulith 모듈러 모놀리스.
kotlin-spring-modulith-template에서 이식했다. 단일 Gradle 모듈이며 루트 패키지는
`com.ogu`. 모듈은 `shared`(OPEN) 하나에서 시작한다. 모듈 목록과 의존 방향은
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
  (파사드, `getMember(memberId)`), `MemberInfo`(온보딩 전에는 `nickname`/`jobRole`/
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
