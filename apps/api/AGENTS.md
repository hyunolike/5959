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
