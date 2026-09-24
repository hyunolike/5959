# M0 기반(001-foundation) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 템플릿을 `apps/api`, `apps/web`으로 이식하고, Spec Kit, CI, 배포, 백업, 관측성까지 갖춰 "빈 서비스가 운영 URL에서 동작하는 상태"를 만든다.

**Architecture:** 모노레포 하나에 Kotlin Spring Modulith API와 Next.js FSD 웹을 둔다. 브라우저는 Vercel의 BFF 라우트(`/api/health`)를 거쳐 Oracle VM의 API 헬스 체크를 조회한다. API는 release-please 태그가 생기면 GHCR 이미지로 빌드되어 VM에 배포되고, 헬스 체크가 실패하면 이전 태그로 롤백한다.

**Tech Stack:** Kotlin 2.2, Spring Boot 4.1, Spring Modulith 2.1, PostgreSQL 17 + pgvector, Flyway, Next.js 16, React 19, TanStack Query 5, steiger, Vitest, Playwright, pnpm 10 workspace, GitHub Actions, release-please, Docker, Caddy, OpenTelemetry Java agent, rclone + Cloudflare R2, GitHub Spec Kit.

**Spec:** `docs/architecture/overview.md` (전체 설계), `specs/001-foundation/spec.md` (Task 1에서 작성)

## Global Constraints

- 루트 패키지: `com.ogu`. API 경로: `/api/v1/**`. 응답 포맷: 템플릿의 `ApiResponse<T>`.
- JDK 21 (Gradle 툴체인이 자동 설치한다. 로컬 JDK가 17이어도 된다).
- DB 이미지: `pgvector/pgvector:pg17` (로컬, 테스트, 운영 모두 같다).
- 스키마는 Flyway만 바꾼다. `spring.jpa.hibernate.ddl-auto`는 모든 프로필에서 `validate`.
- 이벤트 저장소: `spring-modulith-starter-jdbc`. 스키마는 Modulith 2.1 공식 v2 Postgres 스키마.
- 패키지 매니저: `pnpm@10.33.0` (루트 `packageManager`). 워크스페이스 패키지 이름: `web`.
- 테스트 이름에 인수 조건 ID를 넣는다. 형식: `US{n}-AC{m} 설명`. Kotlin은 백틱 한국어 문장, TS는 한국어 문자열.
- 커밋 메시지와 PR 제목은 Conventional Commits.
- 컨테이너 이미지: `ghcr.io/hyunolike/5959-api`, 플랫폼 `linux/amd64,linux/arm64`.
- 운영 VM 경로: `/opt/ogu` (`.env`), `/opt/ogu/repo` (저장소 clone).
- 월 인프라 비용 0원. 유료 서비스를 추가하지 않는다.
- `webbb-be/`, `webbb-fe/`는 수정하지 않는다.
- 템플릿 원본 위치(작업 전에 clone): `TPL=/tmp/ogu-tpl` 아래 `api`(kotlin-spring-modulith-template, `develop`), `web`(nextjs-fsd-template, `dev`).

```bash
rm -rf /tmp/ogu-tpl && mkdir -p /tmp/ogu-tpl
git clone -q https://github.com/hyunolike/kotlin-spring-modulith-template /tmp/ogu-tpl/api
git clone -q -b dev https://github.com/hyunolike/nextjs-fsd-template /tmp/ogu-tpl/web
```

## 작업 브랜치

`docs/architecture/overview.md`와 이 계획이 `develop`에 머지된 뒤, Spec Kit 규칙대로 `001-foundation` 브랜치에서 작업한다.

```bash
git switch develop && git pull
git switch -c 001-foundation
```

## 로컬 서버를 끄는 방법

여러 Task에서 API와 웹을 백그라운드로 띄운다. 끌 때는 아래 명령을 쓴다. `bootRun`은 별도 JVM을 띄우므로 Gradle 프로세스만 죽이면 앱이 남는다.

```bash
pkill -f "com.ogu.OguApplication"; pkill -f "gradlew bootRun"; pkill -f "next dev"; true
```

## M0에서 하지 않는 것

- API 계약 검사(설계 7장): M0에는 도메인 API가 없다. 첫 API가 생기는 M1 plan에서 `contracts/openapi.yaml`과 함께 추가한다.
- 프론트엔드 Sentry: M1에서 첫 사용자 기능과 함께 추가한다(Task 10 Step 4에서 설계 문서에 반영).
- Redis: 처음 필요한 M3에서 추가한다.

## 파일 구조

| 경로 | 책임 |
|---|---|
| `.specify/`, `.claude/skills/speckit-*` | Spec Kit 설정과 명령 (Task 1, 생성물) |
| `.specify/memory/constitution.md` | 프로젝트 원칙 (Task 1) |
| `specs/001-foundation/spec.md` | M0 스펙, 인수 조건 ID의 원천 (Task 1) |
| `specs/001-foundation/quickstart.md` | 운영 환경 수동 설정 절차 (Task 8, 9) |
| `apps/api/**` | Kotlin API (Task 2, 6) |
| `apps/api/src/main/resources/db/migration/V1__init.sql` | pgvector 확장, 이벤트 테이블 (Task 2) |
| `apps/api/Dockerfile` | 런타임 이미지 + OTel 에이전트 (Task 6) |
| `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml` | 루트 워크스페이스 (Task 3) |
| `.husky/`, `commitlint.config.mjs`, `.lintstagedrc.json` | 로컬 커밋 훅 (Task 3) |
| `apps/web/**` | Next.js 웹 (Task 3, 4) |
| `apps/web/src/shared/api/api-health.ts` | API 헬스 조회 순수 함수 (Task 4) |
| `apps/web/src/app/api/health/route.ts` | BFF 헬스 라우트 (Task 4) |
| `apps/web/src/widgets/service-status/**` | 서버 상태 위젯 (Task 4) |
| `.github/workflows/ci.yml` | 경로 필터 CI + `ci-ok` 집계 (Task 5) |
| `infra/compose.prod.yaml`, `infra/Caddyfile` | 운영 구성 (Task 7) |
| `infra/scripts/deploy.sh`, `infra/tests/deploy_test.sh` | 배포와 롤백, 그 테스트 (Task 7) |
| `infra/scripts/bootstrap-vm.sh` | VM 초기 설정 (Task 7) |
| `release-please-config.json`, `.release-please-manifest.json`, `.github/workflows/release.yml` | 릴리즈와 배포 (Task 8) |
| `infra/scripts/backup.sh`, `infra/RESTORE.md` | 백업과 복구 (Task 9) |
| `README.md`, `CLAUDE.md`, `apps/*/AGENTS.md` | 문서 (Task 10) |

---

### Task 1: Spec Kit 초기화, constitution, M0 스펙

**Files:**
- Create: `.specify/**`, `.claude/skills/speckit-*/SKILL.md` (CLI 생성물)
- Modify: `.specify/memory/constitution.md`
- Create: `specs/001-foundation/spec.md`

**Interfaces:**
- Produces: 인수 조건 ID `US1-AC1`, `US1-AC2`, `US2-AC1`~`US2-AC3`, `US3-AC1`, `US3-AC2`, `US4-AC1`, `US5-AC1`, `US5-AC2`. 이후 모든 Task의 테스트 이름이 이 ID를 쓴다.

- [ ] **Step 1: Spec Kit 초기화**

```bash
uvx --from git+https://github.com/github/spec-kit.git@v1.0.11 specify init --here --force --non-interactive --integration claude
git status --short | head -30
```

Expected: `.specify/`와 `.claude/skills/speckit-*` 10개가 생긴다. 기존 파일(`README.md`, `docs/`, `webbb-*`)은 변경되지 않는다(`git status`에 `M`이 없어야 한다).

- [ ] **Step 2: constitution 작성**

`.specify/memory/constitution.md`를 아래 내용으로 덮어쓴다.

```markdown
# 오구오구 Constitution

## Core Principles

### I. 경계는 테스트로 강제한다
백엔드 모듈 경계는 `ApplicationModules.verify()`가, 프론트엔드 레이어 규칙은 steiger가 CI에서 검사한다.
검사를 느슨하게 만들어 통과시키지 않는다. 코드를 고친다.

### II. 계약이 코드보다 먼저다
API를 추가하거나 바꾸는 스펙은 plan 단계에서 `contracts/openapi.yaml`을 먼저 작성한다.
백엔드 구현과 프론트엔드 생성 타입은 계약과 일치해야 한다.

### III. 인수 조건은 곧 테스트다
스펙의 인수 조건에는 `US{n}-AC{m}` ID를 붙이고, 그 조건을 검증하는 테스트 이름에 같은 ID를 넣는다.
자동화할 수 없는 조건은 `quickstart.md`에 수동 검증 절차로 적는다.

### IV. 사용자 안전이 기능보다 먼저다
위기 신호 감지와 대응은 사용자 참여를 늘리는 기능보다 먼저 출시한다.
안전 판단은 AI 장애 때문에 빠지지 않도록 규칙 기반 대체 경로를 둔다.

### V. AI 장애가 핵심 흐름을 막지 않는다
AI 호출은 비동기 이벤트 뒤에 둔다. AI가 실패해도 사용자의 쓰기 요청은 성공하고, 이벤트는 재처리된다.

### VI. 무료 인프라 안에서 운영한다
월 인프라 비용은 AI 사용료를 빼고 0원이다. 비용이 드는 선택은 ADR(`docs/adr/`)로 근거를 남긴다.

## 개발 흐름

기능마다 `speckit-specify` → `speckit-clarify` → `speckit-plan` → `speckit-tasks` → `speckit-analyze` → `speckit-implement` 순서로 진행한다.
PR 본문에는 스펙 링크와 인수 조건 체크리스트를 넣는다. 커밋 메시지와 PR 제목은 Conventional Commits를 따른다.

## Governance

이 문서는 다른 모든 관행보다 우선한다. 원칙을 바꾸려면 PR에서 이유를 적고 버전을 올린다.
plan 단계의 Constitution Check는 여기 적힌 원칙을 기준으로 한다.

**Version**: 1.0.0 | **Ratified**: 2026-09-24 | **Last Amended**: 2026-09-24
```

- [ ] **Step 3: M0 스펙 작성**

`specs/001-foundation/spec.md`를 만든다.

```markdown
# Feature Specification: 서비스 기반

**Feature Branch**: `001-foundation`
**Created**: 2026-09-24
**Status**: Draft
**Input**: 두 템플릿을 모노레포로 이식하고, CI, 배포, 백업, 관측성을 갖춘 빈 서비스를 운영 URL에 띄운다.

## User Scenarios & Testing

### User Story 1 - 방문자가 서비스와 서버 상태를 확인한다 (Priority: P1)

채용 담당자가 운영 URL에 접속하면 오구오구 첫 화면과 서버 상태를 본다.

**Why this priority**: 이후 모든 기능이 올라갈 배포 경로(브라우저 → BFF → API)가 실제로 연결됐다는 증거다.

**Independent Test**: 운영 URL을 열어 첫 화면과 "서버 정상" 표시를 확인한다.

**Acceptance Scenarios**:

1. **US1-AC1** **Given** API가 정상일 때, **When** 첫 화면을 열면, **Then** "서버 정상"이 표시된다.
2. **US1-AC2** **Given** API가 응답하지 않을 때, **When** 첫 화면을 열면, **Then** 페이지는 정상적으로 뜨고 "서버 점검 중"이 표시된다.

### User Story 2 - 개발자의 PR이 자동으로 검사된다 (Priority: P1)

**Why this priority**: 아키텍처 규칙을 사람이 아니라 CI가 지키게 하는 것이 이 프로젝트의 핵심 주장이다.

**Independent Test**: 규칙을 어기는 커밋을 올린 PR에서 `ci-ok`가 실패하는지 본다.

**Acceptance Scenarios**:

1. **US2-AC1** **Given** `apps/api`만 바뀐 PR일 때, **When** CI가 돌면, **Then** API 검사만 실행되고 웹 검사는 건너뛰며 `ci-ok`는 성공한다.
2. **US2-AC2** **Given** 한 모듈이 다른 모듈의 내부 패키지를 참조할 때, **When** CI가 돌면, **Then** `ModularityTests`가 실패하고 `ci-ok`도 실패한다.
3. **US2-AC3** **Given** PR 제목이 Conventional Commits 형식이 아닐 때, **When** CI가 돌면, **Then** `ci-ok`가 실패한다.

### User Story 3 - 릴리즈가 자동으로 배포되고, 실패하면 롤백된다 (Priority: P2)

**Independent Test**: release-please 릴리즈 PR을 머지하고 운영 헬스 체크를 확인한다.

**Acceptance Scenarios**:

1. **US3-AC1** **Given** release-please가 API 릴리즈를 만들었을 때, **When** 워크플로가 끝나면, **Then** 새 태그 이미지가 GHCR에 올라가고 운영 `/actuator/health`가 `UP`이다.
2. **US3-AC2** **Given** 새 이미지가 헬스 체크를 통과하지 못할 때, **When** 배포 스크립트가 돌면, **Then** 이전 태그로 되돌리고 실패로 끝난다.

### User Story 4 - 운영자가 요청을 추적한다 (Priority: P3)

**Acceptance Scenarios**:

1. **US4-AC1** **Given** 운영 API에 요청을 보냈을 때, **When** Grafana Cloud에서 서비스 `ogu-api`를 조회하면, **Then** 해당 요청의 트레이스와 trace ID가 붙은 로그가 보인다. (수동 검증: `quickstart.md`)

### User Story 5 - 운영 DB를 백업하고 복구할 수 있다 (Priority: P3)

**Acceptance Scenarios**:

1. **US5-AC1** **Given** 백업 크론이 설정됐을 때, **When** 하루가 지나면, **Then** R2의 `postgres/` 아래에 그날의 덤프가 있다. (수동 검증)
2. **US5-AC2** **Given** 최신 덤프가 있을 때, **When** `infra/RESTORE.md`의 연습 절차를 따르면, **Then** 빈 컨테이너에 복구되고 `flyway_schema_history` 행 수가 운영과 같다. (수동 검증)

### Edge Cases

- API가 느리게 응답하면 BFF는 3초 뒤 포기하고 `DOWN`으로 응답한다.
- 롤백한 이전 태그도 헬스 체크에 실패하면 스크립트는 실패로 끝나고 로그에 두 태그를 모두 남긴다.

## Requirements

### Functional Requirements

- **FR-001**: 저장소는 `apps/api`, `apps/web`을 가진 모노레포여야 한다.
- **FR-002**: API는 `com.ogu` 패키지에서 Spring Modulith 모듈 검증을 테스트와 기동 시점 모두에서 수행해야 한다.
- **FR-003**: API 스키마는 Flyway로만 관리하며, V1에서 `vector` 확장과 `event_publication` 테이블을 만든다.
- **FR-004**: 웹은 `/api/health` BFF 라우트로만 API 상태를 조회한다. 브라우저가 API 도메인을 직접 호출하지 않는다.
- **FR-005**: CI는 바뀐 경로의 검사만 실행하고, 모든 결과를 `ci-ok` 한 개의 체크로 모은다.
- **FR-006**: `main`에 API 릴리즈가 생기면 멀티 아키텍처 이미지를 GHCR에 올리고 VM에 배포한다.
- **FR-007**: 배포 스크립트는 헬스 체크 실패 시 이전 태그로 롤백한다.
- **FR-008**: 운영 API는 OpenTelemetry로 트레이스, 메트릭, 로그를 Grafana Cloud로 보낸다.
- **FR-009**: 운영 DB는 매일 R2로 백업하고, 복구 절차가 문서로 있어야 한다.

## Success Criteria

- **SC-001**: 운영 URL 첫 화면이 "서버 정상"을 표시한다.
- **SC-002**: `develop` 브랜치 보호 규칙의 필수 체크가 `ci-ok` 하나다.
- **SC-003**: 롤백 시나리오 테스트(`infra/tests/deploy_test.sh`)가 CI에서 통과한다.
- **SC-004**: 월 인프라 청구액 0원.
```

- [ ] **Step 4: 커밋**

```bash
git add .specify .claude/skills specs/001-foundation/spec.md
git commit -m "chore: Spec Kit 초기화와 constitution, M0 스펙 작성"
```

---

### Task 2: apps/api 템플릿 이식과 정리

**Files:**
- Create: `apps/api/**` (템플릿 복사)
- Modify: `apps/api/settings.gradle.kts`, `apps/api/build.gradle.kts`, `apps/api/compose.yaml`, `apps/api/src/main/resources/application.yml`, `apps/api/src/main/kotlin/com/ogu/shared/error/ErrorCode.kt`, `apps/api/src/test/kotlin/com/ogu/TestcontainersConfiguration.kt`, `apps/api/src/test/kotlin/com/ogu/shared/error/GlobalExceptionHandlerTest.kt`
- Create: `apps/api/src/main/resources/db/migration/V1__init.sql`, `apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt`
- Delete: 샘플 모듈 `member`, `order`와 그 테스트

**Interfaces:**
- Produces: `com.ogu.OguApplication`, `com.ogu.TestcontainersConfiguration` (pgvector 컨테이너 빈), `bootJar` 산출물 `apps/api/build/libs/api.jar`

- [ ] **Step 1: 템플릿 복사와 패키지 이름 변경**

```bash
mkdir -p apps
rsync -a --exclude .git --exclude .github --exclude LICENSE --exclude README.md --exclude README.ko.md /tmp/ogu-tpl/api/ apps/api/
cd apps/api
mv src/main/kotlin/com/template src/main/kotlin/com/ogu
mv src/main/java/com/template src/main/java/com/ogu
mv src/test/kotlin/com/template src/test/kotlin/com/ogu
rm -rf src/main/kotlin/com/ogu/member src/main/kotlin/com/ogu/order \
       src/main/java/com/ogu/member src/main/java/com/ogu/order \
       src/test/kotlin/com/ogu/member src/test/kotlin/com/ogu/order
grep -rl "com\.template" src | xargs sed -i '' 's/com\.template/com.ogu/g'
mv src/main/kotlin/com/ogu/TemplateApplication.kt src/main/kotlin/com/ogu/OguApplication.kt
mv src/test/kotlin/com/ogu/TemplateApplicationTests.kt src/test/kotlin/com/ogu/OguApplicationTests.kt
grep -rl "TemplateApplication" src | xargs sed -i '' 's/TemplateApplication/OguApplication/g'
cd ../..
```

(Linux에서 실행한다면 `sed -i ''`를 `sed -i`로 바꾼다.)

- [ ] **Step 2: Gradle 설정 변경**

`apps/api/settings.gradle.kts`의 `rootProject.name`을 바꾼다.

```kotlin
rootProject.name = "api"
```

`apps/api/build.gradle.kts`에서 세 곳을 바꾼다.

```kotlin
group = "com.ogu"
```

`dependencies` 블록에서 `spring-modulith-starter-jpa` 줄을 아래 세 줄로 바꾼다.

```kotlin
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
```

`plugins` 블록에 커버리지 플러그인을 추가한다.

```kotlin
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
```

파일 끝에 추가한다.

```kotlin
tasks.bootJar {
    archiveFileName.set("api.jar")
}

tasks.jar {
    enabled = false
}
```

- [ ] **Step 3: 샘플 에러 코드 정리**

`apps/api/src/main/kotlin/com/ogu/shared/error/ErrorCode.kt`의 enum 본문을 공통 코드만 남긴다.

```kotlin
enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
}
```

`apps/api/src/test/kotlin/com/ogu/shared/error/GlobalExceptionHandlerTest.kt`의 첫 번째 테스트를 바꾼다.

```kotlin
    @Test
    fun `BusinessException을 ErrorCode에 정의된 HTTP 상태로 변환한다`() {
        val response = handler.handleBusinessException(BusinessException(ErrorCode.INVALID_REQUEST))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.success).isFalse()
        assertThat(response.body!!.error!!.code).isEqualTo("INVALID_REQUEST")
    }
```

- [ ] **Step 4: 남은 샘플 참조가 없는지 확인**

```bash
grep -rnwE "template|Template|member|Member|members|orders" apps/api/src apps/api/*.kts apps/api/compose.yaml || echo "clean"
```

Expected: `clean`. (`@Order` 어노테이션은 필터 순서 지정이라 대상이 아니다. 위 패턴에 걸리지 않는다.) 무언가 나오면(예: `OpenApiConfig`의 제목, `application.yml`의 앱 이름) `ogu`/`오구오구 API`로 바꾸고 다시 실행한다. `application.yml`의 `spring.application.name`은 `ogu-api`로 한다.

- [ ] **Step 5: Flyway 검증 테스트 작성 (실패 확인용)**

`apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt`

```kotlin
package com.ogu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FlywayMigrationTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `V1 마이그레이션이 pgvector 확장을 설치한다`() {
        val count =
            jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'",
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `V1 마이그레이션이 이벤트 발행 테이블을 만든다`() {
        val count =
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'event_publication'",
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }
}
```

- [ ] **Step 6: 테스트 컨테이너와 로컬 compose를 pgvector 이미지로 변경**

`apps/api/src/test/kotlin/com/ogu/TestcontainersConfiguration.kt`

```kotlin
package com.ogu

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"),
        )
}
```

`apps/api/compose.yaml`

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: ogu
      POSTGRES_USER: ogu
      POSTGRES_PASSWORD: ogu
    ports:
      - "5433:5432" # 호스트 5432 충돌 방지. spring-boot-docker-compose가 매핑 포트를 자동 감지한다
```

`apps/api/src/main/resources/application.yml`의 `ddl-auto`를 바꾼다.

```yaml
    hibernate:
      ddl-auto: validate # 스키마는 Flyway만 바꾼다
```

- [ ] **Step 7: 테스트 실행해서 실패 확인**

```bash
cd apps/api && ./gradlew test --tests "com.ogu.FlywayMigrationTests"; cd ../..
```

Expected: FAIL. 마이그레이션 파일이 없어 `vector` 확장 수가 0이다(또는 JDBC 이벤트 저장소가 테이블을 찾지 못해 컨텍스트 로드 실패).

- [ ] **Step 8: V1 마이그레이션 작성**

`apps/api/src/main/resources/db/migration/V1__init.sql`

```sql
-- 추천 기능(M6)의 임베딩 컬럼에 쓴다
CREATE EXTENSION IF NOT EXISTS vector;

-- Spring Modulith 2.1 Event Publication Registry 공식 스키마(v2, PostgreSQL)
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
```

- [ ] **Step 9: 전체 빌드 실행**

```bash
cd apps/api && ./gradlew clean build; cd ../..
```

그다음 커버리지 리포트를 만든다.

```bash
cd apps/api && ./gradlew koverHtmlReport && ls build/reports/kover/html/index.html; cd ../..
```

Expected (build): BUILD SUCCESSFUL. `ModularityTests`(모듈이 `shared` 하나여도 통과), `OguApplicationTests`, `FlywayMigrationTests`, `GlobalExceptionHandlerTest`, ktlint, detekt가 모두 통과한다. `apps/api/build/libs/api.jar`가 생긴다. ktlint가 실패하면 `./gradlew ktlintFormat` 후 다시 실행한다.

- [ ] **Step 10: 로컬 기동 확인**

```bash
cd apps/api && (./gradlew bootRun > /tmp/ogu-api.log 2>&1 &) && sleep 40 && curl -s localhost:8080/actuator/health; cd ../..
pkill -f "com.ogu.OguApplication"; pkill -f "gradlew bootRun"; true
```

Expected: `{"status":"UP"...}`. compose가 Postgres를 자동으로 띄운다.

- [ ] **Step 11: 커밋**

```bash
git add apps/api
git commit -m "feat(api): Kotlin Spring Modulith 템플릿 이식과 Flyway 기본 스키마"
```

---

### Task 3: apps/web 템플릿 이식, 루트 워크스페이스 전환, 데모 제거

**Files:**
- Create: `apps/web/**` (템플릿 복사), `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `commitlint.config.mjs`, `.lintstagedrc.json`, `.husky/pre-commit`, `.husky/commit-msg`
- Modify: `apps/web/package.json`, `apps/web/src/shared/config/env.ts`, `apps/web/src/shared/config/constants.ts`, `apps/web/src/shared/config/index.ts`, `apps/web/src/shared/api/index.ts`, `apps/web/src/shared/lib/index.ts`, `apps/web/src/core/providers/app-providers.tsx`, `apps/web/src/app/layout.tsx`, `apps/web/src/app/page.tsx`, `apps/web/.env.example`, `apps/web/steiger.config.ts`, `.gitignore`
- Delete: todo/auth 데모 전체, mock 백엔드, `proxy.ts`, 토큰 저장소, axios 클라이언트

**Interfaces:**
- Produces: `QUERY_KEYS.apiHealth: readonly ["api-health"]`, `env.API_ORIGIN: string` (서버 전용), 워크스페이스 필터 이름 `web`

- [ ] **Step 1: 템플릿 복사**

```bash
rsync -a --exclude .git --exclude .github --exclude .husky --exclude LICENSE \
  --exclude commitlint.config.js --exclude .lintstagedrc.json \
  --exclude pnpm-lock.yaml --exclude pnpm-workspace.yaml \
  /tmp/ogu-tpl/web/ apps/web/
```

- [ ] **Step 2: 데모 코드 삭제**

```bash
cd apps/web
rm -rf "src/app/(auth)" src/app/todos src/app/api \
       src/entities src/features src/widgets/header src/widgets/todo-board \
       src/shared/server src/shared/lib/token-storage.ts src/shared/api/http-client.ts \
       src/core/providers/session-bootstrap.tsx src/proxy.ts e2e/todos.spec.ts \
       public/file.svg public/globe.svg public/next.svg public/vercel.svg public/window.svg
cd ../..
```

- [ ] **Step 3: shared, core 정리**

`apps/web/src/shared/lib/index.ts`

```ts
export { cn } from "./cn";
export { formatDate } from "./format-date";
export { useDebouncedValue } from "./use-debounced-value";
```

`apps/web/src/shared/api/index.ts`

```ts
export { createQueryClient } from "./query-client";
export { ApiError } from "./api-error";
export type { ApiErrorBody } from "./api-error";
```

`apps/web/src/shared/config/constants.ts`

```ts
export const QUERY_KEYS = {
  apiHealth: ["api-health"] as const,
};
```

`apps/web/src/shared/config/index.ts`

```ts
export { env } from "./env";
export { QUERY_KEYS } from "./constants";
```

`apps/web/src/shared/config/env.ts`

```ts
import { createEnv } from "@t3-oss/env-nextjs";
import { z } from "zod";

/**
 * Type-safe environment variables, validated at build/boot time.
 * Add new variables here instead of reading `process.env` directly elsewhere.
 */
export const env = createEnv({
  server: {
    NODE_ENV: z
      .enum(["development", "test", "production"])
      .default("development"),
    // BFF 라우트만 읽는다. 브라우저는 API 도메인을 직접 호출하지 않는다.
    API_ORIGIN: z.string().url().default("http://localhost:8080"),
  },
  client: {},
  experimental__runtimeEnv: {},
});
```

`apps/web/.env.example`

```bash
# Copy to .env.local. See src/shared/config/env.ts for the full schema.

# Server-only. Origin of apps/api, called by BFF route handlers.
API_ORIGIN=http://localhost:8080
```

`apps/web/src/core/providers/app-providers.tsx`

```tsx
import type { ReactNode } from "react";

import { QueryProvider } from "./query-provider";

/**
 * Every provider the app needs, composed in one place. Route segments under
 * `src/app` should stay thin and never register providers directly.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return <QueryProvider>{children}</QueryProvider>;
}
```

`apps/web/steiger.config.ts`에서 두 번째 override 블록(`files: ["./src/features/**", "./src/entities/**"]`와 그 주석)을 삭제한다. 해당 슬라이스가 이제 없다.

- [ ] **Step 4: 레이아웃과 첫 화면을 임시 뼈대로 교체**

`apps/web/src/app/layout.tsx`

```tsx
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";

import "@/core/styles/globals.css";
import { AppProviders } from "@/core/providers/app-providers";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "오구오구",
  description: "감정을 나누고 함께 이겨내는 서비스",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col bg-neutral-50">
        <AppProviders>
          <main className="flex flex-1 flex-col items-center px-6 py-12">
            {children}
          </main>
        </AppProviders>
      </body>
    </html>
  );
}
```

`apps/web/src/app/page.tsx`

```tsx
import { Card } from "@/shared/ui";

export default function HomePage() {
  return (
    <Card className="w-full max-w-xl text-center">
      <h1 className="text-2xl font-semibold text-neutral-900">오구오구</h1>
      <p className="mt-2 text-sm text-neutral-500">
        감정을 나누고 함께 이겨내는 서비스
      </p>
    </Card>
  );
}
```

- [ ] **Step 5: 루트 pnpm 워크스페이스로 전환**

루트 `package.json`

```json
{
  "name": "ogu",
  "private": true,
  "packageManager": "pnpm@10.33.0",
  "scripts": {
    "prepare": "husky"
  },
  "devDependencies": {
    "@commitlint/cli": "^21.2.1",
    "@commitlint/config-conventional": "^21.2.0",
    "husky": "^9.1.7",
    "lint-staged": "^17.0.8"
  }
}
```

루트 `pnpm-workspace.yaml`

```yaml
packages:
  - apps/web

ignoredBuiltDependencies:
  - sharp
  - unrs-resolver
```

루트 `commitlint.config.mjs`

```js
export default {
  extends: ["@commitlint/config-conventional"],
};
```

루트 `.lintstagedrc.json`

```json
{
  "apps/web/**/*.{js,jsx,ts,tsx}": ["pnpm --dir apps/web exec eslint --fix"],
  "apps/web/**/*.{js,jsx,ts,tsx,css,md,json}": [
    "pnpm --dir apps/web exec prettier --write"
  ]
}
```

`.husky/pre-commit`

```bash
pnpm exec lint-staged
```

`.husky/commit-msg`

```bash
pnpm exec commitlint --edit "$1"
```

웹 패키지에서 루트로 옮긴 도구와 쓰지 않는 axios를 뺀다.

```bash
cd apps/web
npm pkg set name=web
npm pkg delete scripts.prepare packageManager
cd ../..
pnpm install
pnpm --filter web remove husky lint-staged @commitlint/cli @commitlint/config-conventional axios
```

루트 `.gitignore`에 추가한다(없으면 만든다).

```gitignore
node_modules/
.next/
apps/web/playwright-report/
apps/web/test-results/
apps/api/build/
apps/api/.gradle/
.env
.env.local
```

- [ ] **Step 6: 웹 검사 전체 실행**

```bash
pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web test && pnpm --filter web build
```

Expected: 모두 통과. `cn.test.ts`만 남아 Vitest는 테스트 2개가 통과한다. 삭제한 모듈을 참조하는 import가 남아 있으면 typecheck에서 파일 이름이 나오므로 해당 import를 지운다.

- [ ] **Step 7: 커밋 훅 동작 확인과 커밋**

```bash
git add package.json pnpm-workspace.yaml pnpm-lock.yaml commitlint.config.mjs .lintstagedrc.json .husky .gitignore apps/web
git commit -m "잘못된 메시지" || echo "commitlint가 막음: 정상"
git commit -m "feat(web): Next.js FSD 템플릿 이식과 루트 pnpm 워크스페이스 전환"
```

Expected: 첫 커밋은 commitlint가 거부하고, 두 번째 커밋은 성공한다.

---

### Task 4: 서버 상태 확인 (BFF 헬스 라우트와 위젯)

**Files:**
- Create: `apps/web/src/shared/api/api-health.ts`, `apps/web/src/shared/api/api-health.test.ts`
- Modify: `apps/web/src/shared/api/index.ts`
- Create: `apps/web/src/app/api/health/route.ts`
- Create: `apps/web/src/widgets/service-status/index.ts`, `apps/web/src/widgets/service-status/api/use-api-health-query.ts`, `apps/web/src/widgets/service-status/ui/service-status-view.tsx`, `apps/web/src/widgets/service-status/ui/service-status-view.test.tsx`, `apps/web/src/widgets/service-status/ui/service-status.tsx`
- Modify: `apps/web/src/app/page.tsx`, `apps/web/playwright.config.ts`
- Create: `apps/web/e2e/landing.spec.ts`, `apps/web/vercel.json`

**Interfaces:**
- Consumes: `QUERY_KEYS.apiHealth`, `env.API_ORIGIN` (Task 3)
- Produces:
  - `type ApiHealthStatus = "UP" | "DOWN"`
  - `interface ApiHealth { status: ApiHealthStatus }`
  - `fetchApiHealth(apiOrigin: string, fetchImpl?: typeof fetch, timeoutMs?: number): Promise<ApiHealth>`
  - `GET /api/health` → `200 { "status": "UP" | "DOWN" }`
  - `<ServiceStatus />` (클라이언트 컴포넌트), `<ServiceStatusView status="LOADING" | "UP" | "DOWN" />`

- [ ] **Step 1: 헬스 조회 함수 테스트 작성**

`apps/web/src/shared/api/api-health.test.ts` (서버에서 도는 코드라 jsdom 대신 node 환경에서 테스트한다)

```ts
// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

import { fetchApiHealth } from "./api-health";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

describe("fetchApiHealth", () => {
  it("US1-AC1 액추에이터가 UP이면 UP을 돌려준다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ status: "UP" }));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "UP",
    });
    expect(fetchImpl).toHaveBeenCalledWith(
      "http://api/actuator/health",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("US1-AC2 액추에이터가 DOWN이면 DOWN을 돌려준다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ status: "DOWN" }, 503));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "DOWN",
    });
  });

  it("US1-AC2 연결에 실패하면 예외 대신 DOWN을 돌려준다", async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError("fetch failed"));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "DOWN",
    });
  });

  it("US1-AC2 제한 시간이 지나면 DOWN을 돌려준다", async () => {
    const fetchImpl = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("timeout", "TimeoutError")),
          );
        }),
    );

    await expect(
      fetchApiHealth("http://api", fetchImpl as typeof fetch, 10),
    ).resolves.toEqual({ status: "DOWN" });
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
pnpm --filter web exec vitest run src/shared/api/api-health.test.ts
```

Expected: FAIL, `Failed to resolve import "./api-health"`.

- [ ] **Step 3: 헬스 조회 함수 구현**

`apps/web/src/shared/api/api-health.ts`

```ts
export type ApiHealthStatus = "UP" | "DOWN";

export interface ApiHealth {
  status: ApiHealthStatus;
}

/**
 * apps/api의 액추에이터 헬스를 조회한다. 어떤 실패도 예외로 던지지 않고
 * DOWN으로 바꿔서, 호출하는 화면이 항상 렌더링되게 한다.
 */
export async function fetchApiHealth(
  apiOrigin: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<ApiHealth> {
  try {
    const response = await fetchImpl(`${apiOrigin}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      return { status: "DOWN" };
    }
    const body = (await response.json()) as { status?: string };
    return { status: body.status === "UP" ? "UP" : "DOWN" };
  } catch {
    return { status: "DOWN" };
  }
}
```

`apps/web/src/shared/api/index.ts`에 추가한다.

```ts
export { fetchApiHealth } from "./api-health";
export type { ApiHealth, ApiHealthStatus } from "./api-health";
```

- [ ] **Step 4: 통과 확인**

```bash
pnpm --filter web exec vitest run src/shared/api/api-health.test.ts
```

Expected: PASS (4 tests).

- [ ] **Step 5: 위젯 표시 컴포넌트 테스트 작성**

`apps/web/src/widgets/service-status/ui/service-status-view.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ServiceStatusView } from "./service-status-view";

describe("ServiceStatusView", () => {
  it("US1-AC1 UP이면 서버 정상을 보여 준다", () => {
    render(<ServiceStatusView status="UP" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 정상");
  });

  it("US1-AC2 DOWN이면 서버 점검 중을 보여 준다", () => {
    render(<ServiceStatusView status="DOWN" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 점검 중");
  });

  it("확인 중에는 확인 중 문구를 보여 준다", () => {
    render(<ServiceStatusView status="LOADING" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 상태 확인 중");
  });
});
```

- [ ] **Step 6: 실패 확인**

```bash
pnpm --filter web exec vitest run src/widgets/service-status
```

Expected: FAIL, `Failed to resolve import "./service-status-view"`.

- [ ] **Step 7: 위젯 구현**

`apps/web/src/widgets/service-status/ui/service-status-view.tsx`

```tsx
import type { ApiHealthStatus } from "@/shared/api";
import { cn } from "@/shared/lib";

type Status = ApiHealthStatus | "LOADING";

const LABEL: Record<Status, string> = {
  LOADING: "서버 상태 확인 중",
  UP: "서버 정상",
  DOWN: "서버 점검 중",
};

const DOT: Record<Status, string> = {
  LOADING: "bg-neutral-300",
  UP: "bg-emerald-500",
  DOWN: "bg-amber-500",
};

export function ServiceStatusView({ status }: { status: Status }) {
  return (
    <p
      role="status"
      className="inline-flex items-center gap-2 text-sm text-neutral-600"
    >
      <span
        aria-hidden
        className={cn("size-2 rounded-full", DOT[status])}
      />
      {LABEL[status]}
    </p>
  );
}
```

`apps/web/src/widgets/service-status/api/use-api-health-query.ts`

```ts
import { useQuery } from "@tanstack/react-query";

import type { ApiHealth } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

async function getApiHealth(): Promise<ApiHealth> {
  const response = await fetch("/api/health", { cache: "no-store" });
  if (!response.ok) {
    return { status: "DOWN" };
  }
  return (await response.json()) as ApiHealth;
}

export function useApiHealthQuery() {
  return useQuery({
    queryKey: QUERY_KEYS.apiHealth,
    queryFn: getApiHealth,
    refetchInterval: 30_000,
  });
}
```

`apps/web/src/widgets/service-status/ui/service-status.tsx`

```tsx
"use client";

import { useApiHealthQuery } from "../api/use-api-health-query";
import { ServiceStatusView } from "./service-status-view";

export function ServiceStatus() {
  const { data, isPending } = useApiHealthQuery();
  return <ServiceStatusView status={isPending ? "LOADING" : (data?.status ?? "DOWN")} />;
}
```

`apps/web/src/widgets/service-status/index.ts`

```ts
export { ServiceStatus } from "./ui/service-status";
```

- [ ] **Step 8: 통과 확인**

```bash
pnpm --filter web test
```

Expected: PASS (cn 2개, api-health 4개, service-status-view 3개).

- [ ] **Step 9: BFF 라우트와 첫 화면 연결**

`apps/web/src/app/api/health/route.ts`

```ts
import { NextResponse } from "next/server";

import { fetchApiHealth } from "@/shared/api";
import { env } from "@/shared/config";

export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json(await fetchApiHealth(env.API_ORIGIN));
}
```

`apps/web/src/app/page.tsx`

```tsx
import { Card } from "@/shared/ui";
import { ServiceStatus } from "@/widgets/service-status";

export default function HomePage() {
  return (
    <Card className="w-full max-w-xl text-center">
      <h1 className="text-2xl font-semibold text-neutral-900">오구오구</h1>
      <p className="mt-2 text-sm text-neutral-500">
        감정을 나누고 함께 이겨내는 서비스
      </p>
      <div className="mt-6">
        <ServiceStatus />
      </div>
    </Card>
  );
}
```

- [ ] **Step 10: E2E 테스트 작성**

`apps/web/playwright.config.ts`의 `webServer`에 `env`를 추가한다. 닫힌 포트를 가리켜서 BFF가 실제로 연결 실패를 겪게 한다.

```ts
  webServer: {
    command: "pnpm build && pnpm start",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      API_ORIGIN: process.env.API_ORIGIN ?? "http://127.0.0.1:9",
    },
  },
```

`apps/web/e2e/landing.spec.ts`

```ts
import { expect, test } from "@playwright/test";

test("US1-AC1 API가 정상이면 서버 정상을 보여 준다", async ({ page }) => {
  await page.route("**/api/health", (route) =>
    route.fulfill({ json: { status: "UP" } }),
  );

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("서버 정상");
});

test("US1-AC2 API가 응답하지 않으면 서버 점검 중을 보여 준다", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("서버 점검 중");
});
```

`apps/web/vercel.json` (Vercel 함수를 서울 리전에서 실행해 VM과의 왕복을 줄인다)

```json
{
  "regions": ["icn1"]
}
```

- [ ] **Step 11: 전체 웹 검사와 E2E 실행**

```bash
pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web test
pnpm --filter web exec playwright install chromium
pnpm --filter web test:e2e
```

Expected: 모두 통과. E2E 2개 PASS.

- [ ] **Step 12: 실제 API와 연결 확인**

```bash
cd apps/api && (./gradlew bootRun > /tmp/ogu-api.log 2>&1 &) && cd ../..
sleep 40
(cd apps/web && API_ORIGIN=http://localhost:8080 pnpm dev > /tmp/ogu-web.log 2>&1 &)
sleep 10
curl -s localhost:3000/api/health
pkill -f "com.ogu.OguApplication"; pkill -f "gradlew bootRun"; pkill -f "next dev"; true
```

Expected: `{"status":"UP"}`

- [ ] **Step 13: 커밋**

```bash
git add apps/web
git commit -m "feat(web): BFF 헬스 라우트와 서버 상태 위젯"
```

---

### Task 5: 경로 필터 CI와 `ci-ok` 집계

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/dependabot.yml`

**Interfaces:**
- Produces: 필수 체크 이름 `ci-ok`. Task 7의 `infra` 필터를 이 파일에 추가한다.

- [ ] **Step 1: 워크플로 작성**

`.github/workflows/ci.yml`

```yaml
name: CI

on:
  pull_request:
    types: [opened, edited, synchronize, reopened]
  push:
    branches: [main, develop]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read
  pull-requests: read

jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      api: ${{ steps.filter.outputs.api }}
      web: ${{ steps.filter.outputs.web }}
    steps:
      - uses: actions/checkout@v7
      - uses: dorny/paths-filter@v4
        id: filter
        with:
          filters: |
            api:
              - 'apps/api/**'
              - '.github/workflows/ci.yml'
            web:
              - 'apps/web/**'
              - 'package.json'
              - 'pnpm-lock.yaml'
              - 'pnpm-workspace.yaml'
              - '.github/workflows/ci.yml'

  api:
    needs: changes
    if: needs.changes.outputs.api == 'true'
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: apps/api
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v6
      # 컴파일 + 테스트(Testcontainers) + ktlint + detekt + 모듈 경계 검증
      - run: ./gradlew build koverXmlReport
      - uses: actions/upload-artifact@v7
        if: success()
        with:
          name: api-coverage
          path: apps/api/build/reports/kover/report.xml
          retention-days: 30
      - uses: actions/upload-artifact@v7
        if: failure()
        with:
          name: api-test-reports
          path: apps/api/build/reports/tests/test
          retention-days: 7
      - uses: actions/upload-artifact@v7
        if: success()
        with:
          name: modulith-docs
          path: apps/api/build/spring-modulith-docs
          retention-days: 30

  web:
    needs: changes
    if: needs.changes.outputs.web == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: pnpm/action-setup@v6
      - uses: actions/setup-node@v7
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - run: pnpm --filter web typecheck
      - run: pnpm --filter web lint
      - run: pnpm --filter web lint:fsd
      - run: pnpm --filter web test
      - run: pnpm --filter web build

  web-e2e:
    needs: [changes, web]
    if: needs.changes.outputs.web == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: pnpm/action-setup@v6
      - uses: actions/setup-node@v7
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - run: pnpm --filter web exec playwright install --with-deps chromium
      - run: pnpm --filter web test:e2e
        env:
          CI: true
      - uses: actions/upload-artifact@v7
        if: always()
        with:
          name: playwright-report
          path: apps/web/playwright-report/
          retention-days: 7

  pr-title:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: amannn/action-semantic-pull-request@v6
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  # 브랜치 보호의 필수 체크는 이 job 하나다. 경로 필터로 건너뛴 job은 skipped로 두고,
  # 실패하거나 취소된 job이 하나라도 있으면 실패한다.
  ci-ok:
    needs: [changes, api, web, web-e2e, pr-title]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - name: 필요한 job 결과 확인
        run: |
          echo '${{ toJSON(needs) }}'
          if [[ "${{ contains(needs.*.result, 'failure') || contains(needs.*.result, 'cancelled') }}" == "true" ]]; then
            exit 1
          fi
```

- [ ] **Step 2: Dependabot 설정**

템플릿 설정을 모노레포 경로에 맞게 옮긴다. `.github/dependabot.yml`

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /apps/api
    target-branch: develop
    schedule:
      interval: weekly
    groups:
      # 관련 의존성을 하나의 PR로 묶어 리뷰 부담을 줄인다
      kotlin:
        patterns:
          - "org.jetbrains.kotlin*"
      spring:
        patterns:
          - "org.springframework*"

  - package-ecosystem: npm
    directory: /
    target-branch: develop
    schedule:
      interval: weekly
    groups:
      next-react:
        patterns:
          - "next"
          - "react"
          - "react-dom"
          - "eslint-config-next"

  - package-ecosystem: github-actions
    directory: /
    target-branch: develop
    schedule:
      interval: weekly
```

- [ ] **Step 3: 커밋하고 PR로 동작 확인**

```bash
git add .github/workflows/ci.yml .github/dependabot.yml
git commit -m "ci: 경로 필터 CI와 ci-ok 집계 job 추가"
git push -u origin HEAD
gh pr create --base develop --title "chore: M0 기반 구축" --body "specs/001-foundation/spec.md"
gh pr checks --watch
```

Expected: `api`, `web`, `web-e2e`, `pr-title`, `ci-ok`가 모두 성공한다.

- [ ] **Step 4: US2-AC1~AC3 확인 (임시 PR, 머지하지 않음)**

임시 PR의 base를 `develop`이 아니라 `001-foundation`으로 둔다. 그래야 PR diff에 API 변경만 담겨 경로 필터를 확인할 수 있다.

(a) US2-AC1: 규칙을 지키는 API 변경만 올린다.

```bash
git switch -c tmp/ci-check
mkdir -p apps/api/src/main/kotlin/com/ogu/alpha/internal
cat > apps/api/src/main/kotlin/com/ogu/alpha/internal/Secret.kt <<'EOF'
package com.ogu.alpha.internal

class Secret
EOF
git add apps/api && git commit -m "test: CI 경로 필터 확인용 모듈"
git push -u origin tmp/ci-check
gh pr create --base 001-foundation --title "test: CI 검사 확인" --body "US2-AC1~AC3 확인용. 머지 금지."
gh pr checks --watch
```

Expected: `api` 성공, `web`과 `web-e2e`는 skipped, `ci-ok` 성공.

(b) US2-AC2: 다른 모듈의 내부 패키지를 참조한다.

```bash
mkdir -p apps/api/src/main/kotlin/com/ogu/beta
cat > apps/api/src/main/kotlin/com/ogu/beta/Leak.kt <<'EOF'
package com.ogu.beta

import com.ogu.alpha.internal.Secret

class Leak(
    val secret: Secret,
)
EOF
git add apps/api && git commit -m "test: 모듈 경계 위반"
git push
gh pr checks --watch || true
```

Expected: `api` job의 `ModularityTests` 실패, `ci-ok` 실패.

(c) US2-AC3: PR 제목을 규칙에 어긋나게 바꾼다.

```bash
gh pr edit --title "CI 검사 확인"
sleep 20 && gh pr checks --watch || true
```

Expected: `pr-title` 실패, `ci-ok` 실패.

정리한다.

```bash
gh pr close --delete-branch
git switch 001-foundation
git branch -D tmp/ci-check
```

- [ ] **Step 5: 필수 체크 설정 (수동, 저장소 관리자)**

```bash
gh api -X PUT repos/hyunolike/5959/branches/develop/protection \
  -H "Accept: application/vnd.github+json" \
  -F "required_status_checks[strict]=false" \
  -f "required_status_checks[contexts][]=ci-ok" \
  -F "enforce_admins=false" \
  -F "required_pull_request_reviews=null" \
  -F "restrictions=null"
```

Expected: 응답 JSON의 `required_status_checks.contexts`가 `["ci-ok"]`. (SC-002)

---

### Task 6: API 컨테이너 이미지와 OpenTelemetry 에이전트

**Files:**
- Create: `apps/api/Dockerfile`, `apps/api/.dockerignore`

**Interfaces:**
- Consumes: `apps/api/build/libs/api.jar` (Task 2)
- Produces: 이미지 진입점. `JAVA_TOOL_OPTIONS=-javaagent:/app/otel/opentelemetry-javaagent.jar`를 주면 OTel이 켜지고, 주지 않으면 꺼진다.

- [ ] **Step 1: Dockerfile 작성**

`apps/api/Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1
# 빌드는 CI의 Gradle이 한다(bootJar). 이미지는 JAR만 담아서 arm64 빌드에 QEMU 컴파일이 필요 없게 한다.
FROM eclipse-temurin:21-jre

ARG OTEL_AGENT_VERSION=2.31.1
WORKDIR /app

# 운영에서만 JAVA_TOOL_OPTIONS로 켠다. 설정은 표준 OTEL_* 환경변수로 한다.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar

RUN groupadd --system app && useradd --system --gid app app
COPY build/libs/api.jar /app/app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

`apps/api/.dockerignore`

```
*
!build/libs/api.jar
```

- [ ] **Step 2: 이미지 빌드와 기동 확인 (OTel 꺼진 상태)**

```bash
cd apps/api && ./gradlew bootJar && DOCKER_BUILDKIT=1 docker build -t ogu-api:local . && cd ../..
docker network create ogu-test
docker run -d --rm --name ogu-pg --network ogu-test -e POSTGRES_DB=ogu -e POSTGRES_USER=ogu -e POSTGRES_PASSWORD=ogu pgvector/pgvector:pg17
until docker exec ogu-pg pg_isready -U ogu -d ogu > /dev/null 2>&1; do sleep 1; done
docker run -d --rm --name ogu-api --network ogu-test -p 18080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=jdbc:postgresql://ogu-pg:5432/ogu -e DB_USERNAME=ogu -e DB_PASSWORD=ogu \
  ogu-api:local
sleep 25
curl -s localhost:18080/actuator/health
```

Expected: `{"status":"UP"...}`. 실패하면 `docker logs ogu-api`로 원인을 본다.

- [ ] **Step 3: OTel 에이전트 켠 상태로 기동 확인**

```bash
docker rm -f ogu-api
docker run -d --rm --name ogu-api --network ogu-test -p 18080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=jdbc:postgresql://ogu-pg:5432/ogu -e DB_USERNAME=ogu -e DB_PASSWORD=ogu \
  -e JAVA_TOOL_OPTIONS=-javaagent:/app/otel/opentelemetry-javaagent.jar \
  -e OTEL_SERVICE_NAME=ogu-api -e OTEL_TRACES_EXPORTER=console -e OTEL_METRICS_EXPORTER=none -e OTEL_LOGS_EXPORTER=none \
  ogu-api:local
sleep 30
curl -s localhost:18080/actuator/health
docker logs ogu-api 2>&1 | grep -m3 -E "opentelemetry-javaagent|actuator/health"
docker rm -f ogu-api ogu-pg && docker network rm ogu-test
```

Expected: 헬스가 `UP`이고, 로그에 에이전트 버전 줄과 콘솔 exporter가 찍은 `GET /actuator/health` span이 보인다. 에이전트가 `console` exporter를 모른다는 경고를 내면 `OTEL_TRACES_EXPORTER=logging`으로 바꿔 다시 실행한다.

- [ ] **Step 4: 커밋**

```bash
git add apps/api/Dockerfile apps/api/.dockerignore
git commit -m "build(api): 런타임 이미지와 OpenTelemetry 에이전트 추가"
```

---

### Task 7: 운영 구성과 롤백하는 배포 스크립트

**Files:**
- Create: `infra/compose.prod.yaml`, `infra/Caddyfile`, `infra/.env.example`, `infra/scripts/deploy.sh`, `infra/tests/deploy_test.sh`, `infra/scripts/bootstrap-vm.sh`
- Modify: `.github/workflows/ci.yml` (infra 필터와 job 추가)

**Interfaces:**
- Consumes: 이미지 `ghcr.io/hyunolike/5959-api:<tag>` (Task 6, 8)
- Produces: `infra/scripts/deploy.sh <tag>`. 환경변수 `DEPLOY_DIR`(기본 `/opt/ogu`), `COMPOSE_FILE_PATH`(기본 `$DEPLOY_DIR/repo/infra/compose.prod.yaml`), `HEALTH_URL`, `HEALTH_RETRIES`, `HEALTH_INTERVAL`. 종료 코드 0 = 배포 성공, 1 = 롤백함.

- [ ] **Step 1: 롤백 테스트 작성**

`infra/tests/deploy_test.sh`

```bash
#!/usr/bin/env bash
# deploy.sh를 docker, curl 스텁으로 실행해 성공과 롤백 경로를 검증한다.
# assert의 조건식은 eval로 나중에 평가하므로 작은따옴표로 넘긴다.
# shellcheck disable=SC2016
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/scripts/deploy.sh"
failures=0

setup() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'API_TAG=v1\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
  touch "$WORK/compose.prod.yaml"

  # docker 스텁: 호출 기록만 남긴다
  cat > "$WORK/bin/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/docker.log"
EOF
  # curl 스텁: 현재 .env의 태그가 bad면 실패, 아니면 UP
  cat > "$WORK/bin/curl" <<EOF
#!/usr/bin/env bash
if grep -q '^API_TAG=bad' "$WORK/.env"; then exit 22; fi
echo '{"status":"UP"}'
EOF
  chmod +x "$WORK/bin/docker" "$WORK/bin/curl"
}

run_deploy() {
  PATH="$WORK/bin:$PATH" DEPLOY_DIR="$WORK" COMPOSE_FILE_PATH="$WORK/compose.prod.yaml" \
    HEALTH_RETRIES=2 HEALTH_INTERVAL=0 bash "$DEPLOY" "$1" > "$WORK/out.log" 2>&1
}

assert() {
  if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; cat "$WORK/out.log"; failures=$((failures + 1)); fi
}

setup
run_deploy v2 && status=0 || status=$?
assert "US3-AC1 헬스 체크를 통과하면 새 태그로 배포한다" \
  '[[ $status -eq 0 ]] && grep -q "^API_TAG=v2$" "$WORK/.env"'

setup
run_deploy bad && status=0 || status=$?
assert "US3-AC2 헬스 체크에 실패하면 이전 태그로 롤백하고 실패한다" \
  '[[ $status -eq 1 ]] && grep -q "^API_TAG=v1$" "$WORK/.env" && [[ $(grep -c "up -d --pull always api" "$WORK/docker.log") -eq 2 ]]'

exit "$failures"
```

- [ ] **Step 2: 실패 확인**

```bash
bash infra/tests/deploy_test.sh
```

Expected: FAIL 2개(`deploy.sh`가 없음).

- [ ] **Step 3: 배포 스크립트 구현**

`infra/scripts/deploy.sh`

```bash
#!/usr/bin/env bash
# 사용법: deploy.sh <이미지 태그>
# 새 태그로 api 컨테이너를 교체하고, 헬스 체크에 실패하면 이전 태그로 되돌린 뒤 실패로 끝낸다.
set -euo pipefail

NEW_TAG="${1:?이미지 태그가 필요합니다}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/ogu}"
COMPOSE_FILE_PATH="${COMPOSE_FILE_PATH:-$DEPLOY_DIR/repo/infra/compose.prod.yaml}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-2}"
ENV_FILE="$DEPLOY_DIR/.env"

current_tag() { grep -E '^API_TAG=' "$ENV_FILE" | cut -d= -f2; }

set_tag() {
  sed -i.bak "s/^API_TAG=.*/API_TAG=$1/" "$ENV_FILE"
  rm -f "$ENV_FILE.bak"
}

up_api() {
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" up -d --pull always api
  # 첫 배포에서 Caddy를 띄운다. 이미 떠 있으면 아무것도 하지 않는다(이미지를 새로 받지 않는다)
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" up -d caddy
}

wait_healthy() {
  local i
  for ((i = 1; i <= HEALTH_RETRIES; i++)); do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
  done
  return 1
}

PREV_TAG="$(current_tag)"
set_tag "$NEW_TAG"
up_api

if wait_healthy; then
  echo "배포 성공: $NEW_TAG"
  exit 0
fi

echo "헬스 체크 실패: $NEW_TAG, $PREV_TAG 로 롤백합니다" >&2
set_tag "$PREV_TAG"
up_api
if ! wait_healthy; then
  echo "롤백한 $PREV_TAG 도 헬스 체크에 실패했습니다" >&2
fi
exit 1
```

```bash
chmod +x infra/scripts/deploy.sh infra/tests/deploy_test.sh
```

- [ ] **Step 4: 통과 확인**

```bash
bash infra/tests/deploy_test.sh
```

Expected: `PASS US3-AC1 ...`, `PASS US3-AC2 ...`, 종료 코드 0.

- [ ] **Step 5: 운영 compose, Caddy, 환경변수 예시 작성**

`infra/compose.prod.yaml`

```yaml
services:
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    environment:
      API_DOMAIN: ${API_DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    depends_on:
      - api

  api:
    image: ghcr.io/hyunolike/5959-api:${API_TAG}
    restart: unless-stopped
    ports:
      - "127.0.0.1:8080:8080" # deploy.sh 헬스 체크용. 외부 노출은 Caddy만 한다
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DB_USERNAME: ${POSTGRES_USER}
      DB_PASSWORD: ${POSTGRES_PASSWORD}
      JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-}
      OTEL_SERVICE_NAME: ogu-api
      OTEL_EXPORTER_OTLP_PROTOCOL: http/protobuf
      OTEL_EXPORTER_OTLP_ENDPOINT: ${OTEL_EXPORTER_OTLP_ENDPOINT:-}
      OTEL_EXPORTER_OTLP_HEADERS: ${OTEL_EXPORTER_OTLP_HEADERS:-}
      OTEL_RESOURCE_ATTRIBUTES: deployment.environment.name=production,service.version=${API_TAG}
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    image: pgvector/pgvector:pg17
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - pg-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      retries: 10

volumes:
  caddy-data:
  caddy-config:
  pg-data:
```

`infra/Caddyfile`

```
{$API_DOMAIN} {
	encode zstd gzip
	reverse_proxy api:8080
}
```

`infra/.env.example` (VM의 `/opt/ogu/.env`로 복사해서 채운다. 저장소에는 실제 값을 두지 않는다)

```bash
API_TAG=latest
# 도메인이 없으면 sslip.io를 쓴다. 예: api.203-0-113-10.sslip.io
API_DOMAIN=

POSTGRES_DB=ogu
POSTGRES_USER=ogu
POSTGRES_PASSWORD=

# OpenTelemetry → Grafana Cloud (Connections > OpenTelemetry에서 발급)
JAVA_TOOL_OPTIONS=-javaagent:/app/otel/opentelemetry-javaagent.jar
OTEL_EXPORTER_OTLP_ENDPOINT=
OTEL_EXPORTER_OTLP_HEADERS=

# 백업 (rclone이 환경변수로 R2 원격을 구성한다)
R2_BUCKET=ogu-backup
RCLONE_CONFIG_R2_TYPE=s3
RCLONE_CONFIG_R2_PROVIDER=Cloudflare
RCLONE_CONFIG_R2_ACCESS_KEY_ID=
RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=
RCLONE_CONFIG_R2_ENDPOINT=
```

- [ ] **Step 6: compose 문법 검증**

```bash
cp infra/.env.example /tmp/ogu.env && sed -i.bak 's/^API_DOMAIN=$/API_DOMAIN=api.example.com/; s/^POSTGRES_PASSWORD=$/POSTGRES_PASSWORD=x/' /tmp/ogu.env
docker compose -f infra/compose.prod.yaml --env-file /tmp/ogu.env config > /dev/null && echo "compose ok"
```

Expected: `compose ok`

- [ ] **Step 7: VM 초기 설정 스크립트 작성**

`infra/scripts/bootstrap-vm.sh`

```bash
#!/usr/bin/env bash
# Oracle Cloud Ubuntu 24.04 (ARM) VM에서 한 번 실행한다. 사용자: ubuntu
set -euo pipefail

# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"

# 백업 업로드 도구
sudo apt-get update && sudo apt-get install -y rclone

# Oracle Ubuntu 이미지는 iptables가 80/443을 막고 있다. VCN 보안 목록과 별개로 열어야 한다.
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

# 배포 디렉터리
sudo mkdir -p /opt/ogu && sudo chown "$USER":"$USER" /opt/ogu
git clone https://github.com/hyunolike/5959.git /opt/ogu/repo
cp /opt/ogu/repo/infra/.env.example /opt/ogu/.env
chmod 600 /opt/ogu/.env

echo "다음: /opt/ogu/.env를 채운다. 첫 배포는 Release 워크플로가 한다(specs/001-foundation/quickstart.md 6번)."
```

```bash
chmod +x infra/scripts/bootstrap-vm.sh
```

- [ ] **Step 8: CI에 infra 검사 추가**

`.github/workflows/ci.yml`의 `changes` job에서 `outputs`에 한 줄, `filters`에 블록을 추가한다.

```yaml
      infra: ${{ steps.filter.outputs.infra }}
```

```yaml
            infra:
              - 'infra/**'
              - '.github/workflows/ci.yml'
```

`jobs`에 추가한다.

```yaml
  infra:
    needs: changes
    if: needs.changes.outputs.infra == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - run: bash infra/tests/deploy_test.sh
      - run: shellcheck infra/scripts/*.sh infra/tests/*.sh
```

`ci-ok`의 `needs`를 `[changes, api, web, web-e2e, infra, pr-title]`로 바꾼다.

- [ ] **Step 9: shellcheck 로컬 실행**

```bash
command -v shellcheck || brew install shellcheck
shellcheck infra/scripts/*.sh infra/tests/*.sh
```

Expected: 경고 없음. 경고가 나오면 안내대로 고친 뒤 Step 4를 다시 실행한다.

- [ ] **Step 10: 커밋**

```bash
git add infra .github/workflows/ci.yml
git commit -m "feat(infra): 운영 compose와 롤백하는 배포 스크립트"
```

---

### Task 8: 릴리즈와 자동 배포, 운영 환경 준비

**Files:**
- Create: `release-please-config.json`, `.release-please-manifest.json`, `.github/workflows/release.yml`, `specs/001-foundation/quickstart.md`

**Interfaces:**
- Consumes: `infra/scripts/deploy.sh <version>` (Task 7), `apps/api/Dockerfile` (Task 6)
- Produces: 태그 형식 `api-v<version>`, 이미지 태그 `<version>`과 `latest`

- [ ] **Step 1: release-please 설정**

`release-please-config.json`

```json
{
  "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
  "packages": {
    "apps/api": {
      "release-type": "simple",
      "component": "api",
      "include-component-in-tag": true
    }
  }
}
```

`.release-please-manifest.json`

```json
{
  "apps/api": "0.0.0"
}
```

- [ ] **Step 2: 릴리즈 워크플로 작성**

`.github/workflows/release.yml`

```yaml
name: Release

on:
  push:
    branches: [main]

permissions:
  contents: write
  pull-requests: write
  packages: write

jobs:
  release-please:
    runs-on: ubuntu-latest
    outputs:
      api_release: ${{ steps.rp.outputs['apps/api--release_created'] }}
      api_version: ${{ steps.rp.outputs['apps/api--version'] }}
    steps:
      - uses: googleapis/release-please-action@v5
        id: rp
        with:
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json

  api-image:
    needs: release-please
    if: needs.release-please.outputs.api_release == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew bootJar
        working-directory: apps/api
      - uses: docker/setup-qemu-action@v4
      - uses: docker/setup-buildx-action@v4
      - uses: docker/login-action@v4
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v7
        with:
          context: apps/api
          platforms: linux/amd64,linux/arm64
          push: true
          tags: |
            ghcr.io/hyunolike/5959-api:${{ needs.release-please.outputs.api_version }}
            ghcr.io/hyunolike/5959-api:latest

  deploy:
    needs: [release-please, api-image]
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: appleboy/ssh-action@v1.2.5
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          script: |
            set -e
            cd /opt/ogu/repo
            git fetch --tags --force
            git checkout --force "api-v${{ needs.release-please.outputs.api_version }}"
            infra/scripts/deploy.sh "${{ needs.release-please.outputs.api_version }}"
```

- [ ] **Step 3: 운영 환경 준비 절차 작성**

`specs/001-foundation/quickstart.md`

````markdown
# M0 운영 환경 준비

코드로 할 수 없는 계정 설정과 수동 검증 절차다. 위에서부터 순서대로 한다.

## 1. main 브랜치

```bash
git switch develop && git pull && git switch -c main && git push -u origin main
```

`main`은 `develop`에서 PR로만 갱신한다. release-please가 `main`에 릴리즈 PR을 만든다.

## 2. Oracle Cloud VM

1. Oracle Cloud 무료 계정을 만들고 홈 리전을 서울(ap-seoul-1) 또는 춘천(ap-chuncheon-1)으로 고른다. 홈 리전은 나중에 바꿀 수 없다.
2. Compute > Instances에서 `VM.Standard.A1.Flex`(4 OCPU, 24GB), Ubuntu 24.04 이미지로 인스턴스를 만든다. SSH 공개키를 등록한다.
3. VCN 보안 목록에 TCP 80, 443 인바운드를 추가한다.
4. VM에 접속해서 초기 설정을 한다.

```bash
ssh ubuntu@<VM_IP>
curl -fsSL https://raw.githubusercontent.com/hyunolike/5959/develop/infra/scripts/bootstrap-vm.sh | bash
```

5. `/opt/ogu/.env`를 채운다. `API_DOMAIN`은 `api.<VM_IP의 점을 대시로>.sslip.io` 형식으로 쓴다(예: `api.203-0-113-10.sslip.io`). `POSTGRES_PASSWORD`는 `openssl rand -base64 24`로 만든다.

## 3. GitHub 설정

1. Settings > Environments에서 `production` 환경을 만든다.
2. `production` 환경 시크릿을 등록한다.

```bash
gh secret set VM_HOST --env production --body "<VM_IP>"
gh secret set VM_USER --env production --body "ubuntu"
gh secret set VM_SSH_KEY --env production < ~/.ssh/<배포용_개인키>
```

3. 첫 릴리즈 뒤 GHCR 패키지 `5959-api`의 Package settings에서 visibility를 Public으로 바꾼다. VM이 인증 없이 이미지를 받게 된다.

## 4. Grafana Cloud (US4-AC1)

1. Grafana Cloud 무료 스택을 만든다.
2. Connections > Add new connection > OpenTelemetry (OTLP)에서 토큰을 만들고, 화면에 나온 `OTEL_EXPORTER_OTLP_ENDPOINT`와 `OTEL_EXPORTER_OTLP_HEADERS` 값을 `/opt/ogu/.env`에 넣는다.
3. `docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env up -d api`로 다시 띄운다.
4. **수동 검증**: `curl https://<API_DOMAIN>/actuator/health`를 몇 번 호출한 뒤 Grafana의 Explore > Tempo에서 `service.name = ogu-api`로 트레이스를 찾는다. 같은 trace ID로 Loki에서 로그가 나오면 US4-AC1 통과다.

## 5. Vercel

1. Vercel에서 `hyunolike/5959`를 Import하고 Root Directory를 `apps/web`으로 지정한다.
2. Production Branch를 `main`으로 바꾼다.
3. 환경변수 `API_ORIGIN=https://<API_DOMAIN>`을 Production과 Preview에 넣는다.

## 6. 첫 배포 (US3-AC1)

1. `develop` → `main` PR을 머지한다. release-please가 `chore(main): release api 0.1.0` PR을 만든다.
2. 그 PR을 머지하면 `Release` 워크플로가 이미지를 올리고 VM에 배포한다.
3. **검증**: `curl https://<API_DOMAIN>/actuator/health`가 `UP`이고, Vercel 운영 URL 첫 화면에 "서버 정상"이 보이면 US3-AC1과 SC-001 통과다.

## 7. 백업 (US5-AC1, US5-AC2)

`infra/RESTORE.md`를 따른다.
````

- [ ] **Step 4: 워크플로 문법 확인**

```bash
command -v actionlint || brew install actionlint
actionlint .github/workflows/*.yml
```

Expected: 출력 없음.

- [ ] **Step 5: 커밋**

```bash
git add release-please-config.json .release-please-manifest.json .github/workflows/release.yml specs/001-foundation/quickstart.md
git commit -m "ci: release-please 릴리즈와 VM 자동 배포 워크플로"
```

- [ ] **Step 6: 운영 환경 준비와 첫 배포 (사람이 수행)**

`specs/001-foundation/quickstart.md`의 1~6번을 수행한다. 계정 생성과 시크릿 등록은 저장소 소유자만 할 수 있다.

Expected: 운영 URL에 "서버 정상"이 표시된다.

---

### Task 9: DB 백업과 복구 절차

**Files:**
- Create: `infra/scripts/backup.sh`, `infra/RESTORE.md`

**Interfaces:**
- Consumes: `/opt/ogu/.env`의 `POSTGRES_*`, `R2_BUCKET`, `RCLONE_CONFIG_R2_*` (Task 7)
- Produces: R2 객체 `postgres/ogu-<UTC 타임스탬프>.dump` (pg_dump custom 포맷)

- [ ] **Step 1: 백업 스크립트 작성**

`infra/scripts/backup.sh`

```bash
#!/usr/bin/env bash
# 운영 DB를 pg_dump custom 포맷으로 떠서 R2에 올린다. 크론에서 매일 실행한다.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/ogu}"
COMPOSE_FILE_PATH="${COMPOSE_FILE_PATH:-$DEPLOY_DIR/repo/infra/compose.prod.yaml}"

set -a
# shellcheck disable=SC1091
source "$DEPLOY_DIR/.env"
set +a

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$(mktemp "/tmp/ogu-$STAMP.XXXX.dump")"
trap 'rm -f "$FILE"' EXIT

docker compose -f "$COMPOSE_FILE_PATH" --env-file "$DEPLOY_DIR/.env" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$FILE"

rclone copyto "$FILE" "r2:$R2_BUCKET/postgres/ogu-$STAMP.dump"
echo "백업 완료: postgres/ogu-$STAMP.dump ($(du -h "$FILE" | cut -f1))"
```

```bash
chmod +x infra/scripts/backup.sh
shellcheck infra/scripts/backup.sh
```

Expected: shellcheck 경고 없음.

- [ ] **Step 2: 복구 문서 작성**

`infra/RESTORE.md`

````markdown
# 운영 DB 백업과 복구

## 백업 설정 (한 번)

1. Cloudflare R2에 버킷 `ogu-backup`을 만든다.
2. 버킷 Settings > Object lifecycle rules에서 `postgres/` 접두사 객체를 14일 뒤 삭제하는 규칙을 추가한다.
3. R2 API 토큰(해당 버킷 Object Read & Write)을 만들고 `/opt/ogu/.env`의 `RCLONE_CONFIG_R2_*`를 채운다. `RCLONE_CONFIG_R2_ENDPOINT`는 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`이다.
4. 수동으로 한 번 실행해 본다.

```bash
/opt/ogu/repo/infra/scripts/backup.sh
```

5. 크론에 등록한다. UTC 18:30은 한국 시간 03:30이다.

```bash
(crontab -l 2>/dev/null; echo '30 18 * * * /opt/ogu/repo/infra/scripts/backup.sh >> /opt/ogu/backup.log 2>&1') | crontab -
```

**US5-AC1 검증**: 다음 날 아래 명령에 그날 날짜의 덤프가 보이면 통과다.

```bash
set -a; source /opt/ogu/.env; set +a
rclone lsl "r2:$R2_BUCKET/postgres/" | tail -3
```

## 복구 연습 (분기마다, US5-AC2)

운영 DB를 건드리지 않고 임시 컨테이너에 복구해 본다.

```bash
set -a; source /opt/ogu/.env; set +a
LATEST="$(rclone lsf "r2:$R2_BUCKET/postgres/" | sort | tail -1)"
rclone copyto "r2:$R2_BUCKET/postgres/$LATEST" /tmp/restore.dump

docker run -d --rm --name ogu-restore -e POSTGRES_PASSWORD=restore pgvector/pgvector:pg17
until docker exec ogu-restore pg_isready -U postgres > /dev/null 2>&1; do sleep 1; done
docker exec -i ogu-restore pg_restore -U postgres -d postgres --no-owner < /tmp/restore.dump
docker exec ogu-restore psql -U postgres -tAc "select count(*) from flyway_schema_history"
docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select count(*) from flyway_schema_history"

docker rm -f ogu-restore && rm /tmp/restore.dump
```

두 숫자가 같으면 통과다. 날짜와 결과를 이 문서 맨 아래 기록 표에 남긴다.

## 실제 복구 (장애 시)

1. API를 멈춘다: `docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env stop api`
2. 위 연습 절차로 덤프를 받는다.
3. 운영 DB에 덮어쓴다.

```bash
docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner < /tmp/restore.dump
```

4. API를 띄우고 헬스를 확인한다: `... start api && curl -s http://127.0.0.1:8080/actuator/health`

## 복구 연습 기록

| 날짜 | 덤프 | flyway_schema_history (복구/운영) | 결과 |
|---|---|---|---|
````

- [ ] **Step 3: 로컬에서 백업 명령 검증**

R2 대신 로컬 경로로 rclone을 시험한다.

```bash
command -v rclone || brew install rclone
docker run -d --rm --name ogu-pg -e POSTGRES_DB=ogu -e POSTGRES_USER=ogu -e POSTGRES_PASSWORD=ogu pgvector/pgvector:pg17
until docker exec ogu-pg pg_isready -U ogu -d ogu > /dev/null 2>&1; do sleep 1; done
docker exec ogu-pg psql -U ogu -d ogu -c "create table t(id int); insert into t values (1),(2);"
docker exec ogu-pg pg_dump -U ogu -d ogu -Fc > /tmp/t.dump
docker run -d --rm --name ogu-restore -e POSTGRES_PASSWORD=restore pgvector/pgvector:pg17
until docker exec ogu-restore pg_isready -U postgres > /dev/null 2>&1; do sleep 1; done
docker exec -i ogu-restore pg_restore -U postgres -d postgres --no-owner < /tmp/t.dump
docker exec ogu-restore psql -U postgres -tAc "select count(*) from t"
docker rm -f ogu-pg ogu-restore; rm /tmp/t.dump
```

Expected: `2`. RESTORE.md의 `pg_restore` 옵션이 실제로 동작함을 확인한다.

- [ ] **Step 4: 커밋**

```bash
git add infra/scripts/backup.sh infra/RESTORE.md
git commit -m "feat(infra): R2 일일 백업과 복구 절차"
```

- [ ] **Step 5: 운영 VM에서 백업 설정 (사람이 수행)**

`infra/RESTORE.md`의 "백업 설정"과 "복구 연습"을 수행하고 기록 표에 결과를 남긴다.

---

### Task 10: 문서 정리

**Files:**
- Modify: `README.md`, `apps/api/AGENTS.md`, `apps/api/CLAUDE.md`, `apps/web/AGENTS.md`, `apps/web/CLAUDE.md`, `docs/architecture/overview.md`
- Create: `CLAUDE.md`

- [ ] **Step 1: 앱별 에이전트 지침 갱신**

`apps/api/AGENTS.md`와 `apps/api/CLAUDE.md`에서 다음을 바꾼다.
- `com.template` → `com.ogu`, `TemplateApplication` → `OguApplication`
- "Top-level packages ... `shared` (OPEN), `member`, `order`" 문단을 "`shared` (OPEN) 하나에서 시작한다. 모듈 목록과 의존 방향은 `docs/architecture/overview.md` 5.1절을 따른다."로 바꾼다.
- `order → member` 예시 문장을 "의존 방향은 `docs/architecture/overview.md` 5.1절의 그래프를 따른다"로 바꾼다.
- 명령 예시의 경로 앞에 `cd apps/api &&`를 붙인다.
- Conventions에 한 줄 추가: "스키마 변경은 `src/main/resources/db/migration`의 Flyway 스크립트로만 한다. `ddl-auto`는 `validate`다."
- Conventions에 한 줄 추가: "테스트 이름 앞에 스펙 인수 조건 ID를 붙인다. 예: `` `US1-AC2 ...` ``"

`apps/web/AGENTS.md`와 `apps/web/CLAUDE.md`에서 다음을 바꾼다.
- todo/login 데모 설명과 mock 백엔드 설명을 지운다.
- 명령을 루트 기준으로 바꾼다: `pnpm --filter web <script>`.
- 인증 설명을 "BFF 패턴(ADR-0002). 브라우저는 `/api/*` 라우트 핸들러만 호출하고, 라우트가 서버 전용 `API_ORIGIN`으로 전달한다."로 바꾼다.
- 테스트 이름 규칙 한 줄 추가(위와 같음).

```bash
grep -rniE "todo|mock|template|localStorage" apps/web/AGENTS.md apps/web/CLAUDE.md apps/api/AGENTS.md apps/api/CLAUDE.md || echo "clean"
```

Expected: `clean` (남은 줄이 있으면 문맥에 맞게 고친다).

- [ ] **Step 2: 루트 CLAUDE.md 작성**

`CLAUDE.md`

```markdown
# 오구오구 (5959)

모노레포다. 작업 전에 해당 앱의 AGENTS.md를 읽는다.

- `apps/api`: Kotlin, Spring Boot 4, Spring Modulith. 지침은 `apps/api/AGENTS.md`
- `apps/web`: Next.js 16, FSD. 지침은 `apps/web/AGENTS.md`
- `infra`: 운영 compose, 배포와 백업 스크립트. 테스트는 `bash infra/tests/deploy_test.sh`
- `webbb-be`, `webbb-fe`: 원본 참고용 subtree. **수정하지 않는다.**

## 설계와 스펙

- 전체 설계: `docs/architecture/overview.md`, 결정 기록: `docs/adr/`
- 원칙: `.specify/memory/constitution.md`
- 기능 스펙: `specs/NNN-이름/`. 새 기능은 `speckit-specify` 스킬부터 시작한다.

## 규칙

- 커밋 메시지와 PR 제목은 Conventional Commits.
- 테스트 이름에 스펙 인수 조건 ID(`US1-AC2`)를 넣는다.
- PR은 `develop`으로 보낸다. 필수 체크는 `ci-ok`.
```

- [ ] **Step 3: 루트 README 갱신**

`README.md`의 "📁 저장소 구성" 절을 아래로 교체한다. 나머지 절(소개, 기술 스택, 시스템 구조 등)은 그대로 둔다.

````markdown
## 📁 저장소 구성

```
.
├── apps/
│   ├── api/        Kotlin · Spring Boot 4 · Spring Modulith
│   └── web/        Next.js 16 · Feature-Sliced Design
├── infra/          운영 compose, 배포와 백업 스크립트
├── specs/          GitHub Spec Kit 기능 스펙
├── docs/           아키텍처 설계와 ADR
├── webbb-be/       원본 백엔드 (DDD-13-WEBBB_BE, 참고용 subtree)
└── webbb-fe/       원본 프론트엔드 (DDD-13-WEBBB-FE, 참고용 subtree)
```

새로 만드는 코드는 `apps/`에 있습니다. 설계는 [`docs/architecture/overview.md`](docs/architecture/overview.md)에서, 개발 원칙은 [`.specify/memory/constitution.md`](.specify/memory/constitution.md)에서 볼 수 있습니다.

### 로컬 실행

```bash
cd apps/api && ./gradlew bootRun        # PostgreSQL은 compose로 자동 기동
pnpm install && pnpm --filter web dev   # http://localhost:3000
```

원본 저장소에 올라온 변경을 다시 받아오려면 아래 명령을 실행합니다.

```bash
git remote add webbb-be https://github.com/DDD-Community/DDD-13-WEBBB_BE.git   # 처음 한 번만
git remote add webbb-fe https://github.com/DDD-Community/DDD-13-WEBBB-FE.git   # 처음 한 번만

git subtree pull --prefix=webbb-be webbb-be main --squash
git subtree pull --prefix=webbb-fe webbb-fe main --squash
```
````

같은 파일의 "🚀 로컬 실행" 절(원본 `webbb-be`, `webbb-fe` 실행 방법)은 제목을 "🚀 원본 로컬 실행"으로 바꾼다.

- [ ] **Step 4: 설계 문서에 M0 구현 결정 반영**

`docs/architecture/overview.md`에서 다음을 바꾼다.
- 8장 표의 "관측성" 행: `OpenTelemetry → Grafana Cloud 무료, 프론트엔드는 Sentry 무료`를 `OpenTelemetry Java 에이전트 → Grafana Cloud 무료 (프론트엔드 Sentry는 M1에서 추가)`로 바꾼다.
- 4장 시스템 구조 아래에 한 줄 추가: "Redis는 처음 필요한 M3(SSE 팬아웃)에서 추가한다."
- 5장 첫 문단 뒤에 한 줄 추가: "이벤트 저장소는 `spring-modulith-starter-jdbc`를 쓰고 스키마는 Flyway V1에서 만든다."

- [ ] **Step 5: 커밋과 PR 갱신**

```bash
git add README.md CLAUDE.md apps/api/AGENTS.md apps/api/CLAUDE.md apps/web/AGENTS.md apps/web/CLAUDE.md docs/architecture/overview.md
git commit -m "docs: 모노레포 구성과 M0 구현 결정 반영"
git push
gh pr checks --watch
```

Expected: `ci-ok` 성공.

---

## 인수 조건 추적표

| ID | 검증 | 위치 |
|---|---|---|
| US1-AC1 | Vitest, Playwright | `apps/web/src/shared/api/api-health.test.ts`, `apps/web/src/widgets/service-status/ui/service-status-view.test.tsx`, `apps/web/e2e/landing.spec.ts` |
| US1-AC2 | Vitest, Playwright | 위와 같음 |
| US2-AC1 | 임시 PR | Task 5 Step 4 (a) |
| US2-AC2 | 임시 PR | Task 5 Step 4 (b) |
| US2-AC3 | 임시 PR | Task 5 Step 4 (c) |
| US3-AC1 | 셸 테스트 + 운영 검증 | `infra/tests/deploy_test.sh`, quickstart 6 |
| US3-AC2 | 셸 테스트 | `infra/tests/deploy_test.sh` |
| US4-AC1 | 수동 | quickstart 4 |
| US5-AC1 | 수동 | `infra/RESTORE.md` |
| US5-AC2 | 수동 | `infra/RESTORE.md` |
