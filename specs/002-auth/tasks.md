---

description: "Task list for 002-auth (인증과 회원)"
---

# Tasks: 인증과 회원

**Input**: Design documents from `/specs/002-auth/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, contracts/bff-routes.md, quickstart.md

**Tests**: 포함한다. constitution III(인수 조건은 곧 테스트)에 따라 각 스토리의 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다(예: `` `US2-AC3 ...` ``). ID가 없는 보조 테스트는 한국어 설명만 쓴다.

**Organization**: 사용자 스토리별로 묶었다. 각 스토리는 따로 구현하고 검증할 수 있다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 미완료 작업에 의존하지 않아 병렬로 할 수 있다
- **[Story]**: 해당 사용자 스토리(US1~US5)

## Path Conventions

- API: `apps/api/src/main/kotlin/com/ogu/`, 테스트 `apps/api/src/test/kotlin/com/ogu/`, 리소스 `apps/api/src/main/resources/`
- 웹: `apps/web/src/`, E2E `apps/web/e2e/`(BFF 단독), `apps/web/e2e-full/`(API 포함)
- 아래 경로의 `member/`는 `apps/api/src/main/kotlin/com/ogu/member/`를 줄인 것이다

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 의존성, 설정, 타입 생성 도구

- [x] T001 카카오 PKCE(`code_challenge`, `S256`) 지원 여부를 카카오 공식 문서로 확인하고 결과(지원 여부, 확인한 문서 URL, 확인 날짜)를 specs/002-auth/research.md R4의 "확인 필요" 문단에 적는다
- [x] T002 apps/api/build.gradle.kts에 `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-security-test`(testImplementation), `swagger-parser`(testImplementation, 계약 테스트용) 의존성을 추가하고 `./gradlew build`가 통과하는지 확인한다
- [x] T003 [P] apps/api/src/main/resources/application.yml에 `ogu.auth` 설정 블록을 추가한다: `jwt.secret`(`${JWT_SECRET}`), `jwt.access-token-ttl: 15m`, `session.idle-ttl: 14d`, `session.absolute-ttl: 30d`, `session.rotation-grace: 30s`, `bff-key`(`${OGU_BFF_KEY:}`), `oauth.allowed-redirect-uris`(`${OAUTH_ALLOWED_REDIRECT_URIS:http://localhost:3000/api/auth/oauth/kakao/callback,http://localhost:3000/api/auth/oauth/google/callback}`), `oauth.kakao.client-id/client-secret`, `oauth.google.client-id/client-secret`(각각 환경변수). application-local.yml에는 개발용 `jwt.secret`과 `bff-key: local-bff-key`를 둔다
- [x] T004 [P] apps/web에 `@sentry/nextjs`, `server-only`, `openapi-typescript`(devDependency)를 추가하고, apps/web/package.json에 `"gen:api": "openapi-typescript ../../specs/002-auth/contracts/openapi.yaml -o src/shared/api/generated.ts"`와 `"test:e2e:full": "playwright test --config playwright.full.config.ts"` 스크립트를 추가한다
- [x] T005 [P] infra/.env.example과 apps/web/.env.example에 새 환경변수를 추가한다: API 쪽 `JWT_SECRET`, `OGU_BFF_KEY`, `OAUTH_ALLOWED_REDIRECT_URIS`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` / 웹 쪽 `BFF_API_KEY`, `APP_ORIGIN`, `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_SENTRY_DSN`, `ENABLE_ERROR_PROBE`. infra/compose.prod.yaml의 api 서비스 environment에도 API 쪽 변수를 연결한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 쓰는 스키마, 계약 검증, 보안 뼈대, BFF 공통 코드

**⚠️ CRITICAL**: 이 단계가 끝나야 스토리 작업을 시작한다

### 스키마와 모듈

- [x] T006 apps/api/src/main/resources/db/migration/V2__member_auth.sql을 data-model.md 그대로 작성한다. `member`(`auth_method varchar(10) NOT NULL` 값 `EMAIL/KAKAO/GOOGLE`, `email varchar(254) NULL`, `password_hash varchar(100) NULL`, `nickname varchar(10) NULL`, `nickname_key varchar(10) NULL UNIQUE`, `job_role varchar(20)`, `career_year varchar(20)`, `onboarded_at timestamptz`, 부분 유일 인덱스 `UNIQUE (email) WHERE auth_method = 'EMAIL'`, 체크 제약 2개), `oauth_identity`(`UNIQUE (provider, provider_user_id)`, `UNIQUE (member_id, provider)`), `auth_session`(`id uuid PK`, `refresh_token_hash char(64) NOT NULL UNIQUE`, `previous_refresh_token_hash char(64) NULL UNIQUE`, `rotated_at`, `expires_at`, `absolute_expires_at`, `revoked_at`, `revoke_reason varchar(20)`, `member_id` 인덱스), `login_attempt`(`scope_key varchar(300) PK`, `window_started_at`, `failure_count`, `blocked_until`)
- [x] T007 apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt에 V2 테스트를 추가한다: 네 테이블이 있고, `member`의 이메일 부분 유일 인덱스가 `auth_method='EMAIL'` 행끼리만 중복을 막으며, `onboarded_at`이 있는데 `nickname`이 NULL인 행은 체크 제약으로 거부됨을 확인한다
- [x] T008 apps/api/src/main/java/com/ogu/member/package-info.java에 `member` 모듈 설명을 쓰고, 공개 타입 member/MemberApi.kt(`getMember(memberId: Long): MemberInfo`), member/MemberInfo.kt(`id, nickname, jobRole, careerYear`), member/AuthenticatedMember.kt(`memberId: Long, sessionId: UUID, onboarded: Boolean`), member/JobRole.kt(`PLANNING, DESIGN, DEVELOPMENT, MARKETING, SALES, HR, GENERAL_AFFAIRS, PRODUCTION, ACCOUNTING, OTHER`), member/CareerYear.kt(`NEWCOMER, YEAR_1 … YEAR_6, YEAR_7_PLUS`)를 만든다
- [x] T009 [P] apps/api/src/main/kotlin/com/ogu/shared/error/ErrorCode.kt에 계약의 오류 코드를 추가한다: `UNAUTHORIZED`(401), `SESSION_EXPIRED`(401), `ONBOARDING_REQUIRED`(403), `FORBIDDEN_ORIGIN`(403), `EMAIL_ALREADY_REGISTERED`(409), `INVALID_CREDENTIALS`(401), `LOGIN_THROTTLED`(429), `OAUTH_CODE_INVALID`(401), `EMAIL_REGISTERED_WITH_OTHER_METHOD`(409), `OAUTH_PROVIDER_UNAVAILABLE`(502), `NICKNAME_TAKEN`(409), `ALREADY_ONBOARDED`(409). `ErrorResponse`에 nullable `retryAfterSeconds`를 추가한다

### 계약 검증 (constitution II)

- [x] T010 apps/api/src/test/kotlin/com/ogu/ContractTests.kt를 작성한다: 앱을 띄워 `/v3/api-docs`를 받고, specs/002-auth/contracts/openapi.yaml과 경로, 메서드, 응답 상태 코드 집합이 같은지 비교한다(스키마 세부는 비교하지 않는다). 지금은 구현이 없으므로 실패해야 한다. 각 스토리가 끝날 때마다 해당 경로가 통과하는지 본다. `@Disabled`로 두지 말고, 아직 없는 경로 목록을 테스트 안의 `pendingPaths` 집합으로 관리하다가 T066에서 빈 집합이 되게 한다
- [x] T011 [P] `pnpm --filter web gen:api`로 apps/web/src/shared/api/generated.ts를 만들고 커밋한다. .github/workflows/ci.yml의 web job에 `pnpm --filter web gen:api && git diff --exit-code apps/web/src/shared/api/generated.ts` 단계를 추가해 계약과 타입이 어긋나면 실패하게 한다

### API 보안 뼈대

- [x] T012 [P] member/infrastructure/security/JwtIssuer.kt와 그 단위 테스트 apps/api/src/test/kotlin/com/ogu/member/infrastructure/security/JwtIssuerTest.kt를 작성한다(TDD). HS256, 클레임 `sub`, `sid`, `onboarded`, `iss=ogu-api`, 만료 `ogu.auth.jwt.access-token-ttl`. 테스트: 발급한 토큰을 디코더로 검증하면 클레임이 같다, 만료된 토큰은 거부된다, 다른 비밀키로 서명한 토큰은 거부된다
- [x] T013 [P] member/infrastructure/security/BffClientIpResolver.kt와 테스트를 작성한다(TDD): `X-Ogu-Bff-Key`가 `ogu.auth.bff-key`와 같을 때만 `X-Ogu-Client-Ip`를 쓰고, 키가 없거나 다르거나 설정 값이 비어 있으면 원격 주소를 쓴다. 비교는 상수 시간 비교(`MessageDigest.isEqual`)로 한다
- [x] T014 member/domain/AuthSession.kt(엔티티), member/domain/AuthSessionRepository.kt, member/application/SessionService.kt의 `issue(memberId): IssuedTokens`를 작성한다. refresh 토큰은 `SecureRandom` 32바이트 base64url, 저장은 SHA-256 hex. `expires_at = now + 14일`, `absolute_expires_at = now + 30일`. 단위 테스트는 member/application/SessionServiceTest.kt에 둔다
- [x] T015 member/infrastructure/security/SecurityConfig.kt와 SessionCheckFilter.kt를 작성한다: stateless, CSRF 비활성(API는 브라우저가 직접 부르지 않음), 공개 경로는 `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/oauth/**`, `/api/v1/auth/refresh`, `/actuator/health`, 그리고 springdoc이 켜져 있을 때 `/v3/api-docs/**`(운영은 M0에서 springdoc을 껐으므로 노출되지 않는다. 프로필 이름으로 판단하지 않는다). 나머지 `/api/v1/**`는 JWT 필요. JWT 검증 뒤 `SessionCheckFilter`가 `sid`의 세션이 유효(`revoked_at IS NULL AND now() < expires_at`)한지 확인하고, 아니면 `401 SESSION_EXPIRED`. 인증 결과를 `AuthenticatedMember`로 컨트롤러에 넘기는 `HandlerMethodArgumentResolver`를 등록한다. 401, 403 응답도 `ApiResponse` 봉투로 쓴다
- [x] T016 member/infrastructure/security/OnboardingGuard.kt: 인증 필요 경로 중 허용 목록(`/api/v1/members/me`, `/api/v1/members/nickname-availability`, `/api/v1/members/me/onboarding`, `/api/v1/auth/logout`)이 아니면 `onboarded=false` 토큰에 `403 ONBOARDING_REQUIRED`를 돌려준다. 테스트: 허용 목록 경로는 통과, 가짜 보호 경로(테스트 전용 컨트롤러)는 403
- [x] T017 apps/api/src/test/kotlin/com/ogu/member/security/SecurityIntegrationTests.kt(Testcontainers, MockMvc): 토큰 없이 보호 경로 401, 무효화된 세션의 토큰 401 `SESSION_EXPIRED`, 만료 세션 401, 공개 경로는 토큰 없이 접근 가능

### 웹 공통 (BFF)

- [x] T018 [P] apps/web/src/shared/config/env.ts에 서버 변수 `BFF_API_KEY`(min 1, 기본값 `local-bff-key`), `APP_ORIGIN`(url, 기본값 `http://localhost:3000`), `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID`(선택), `ENABLE_ERROR_PROBE`(선택), `APP_ENV`(`development`/`e2e`/`production`, 기본 `development`)와 클라이언트 변수 `NEXT_PUBLIC_SENTRY_DSN`(선택)을 추가한다
- [x] T019 [P] apps/web/src/shared/server/auth-cookies.ts와 테스트(TDD): research R3 표대로 `__Host-ogu_at`(Max-Age 900), `__Host-ogu_rt`(Max-Age = refreshTokenExpiresAt까지 초), `__Host-ogu_ob`(값 `1`) 설정, 읽기, 전부 삭제 함수. 모든 쿠키는 HttpOnly, Secure, SameSite=Lax, Path=/. `refreshToken`이 null이면 `ogu_rt`를 바꾸지 않는다. 파일 첫 줄은 `import "server-only"`
- [x] T020 [P] apps/web/src/shared/server/origin-guard.ts와 테스트: POST, PUT, PATCH, DELETE에서 `Origin`이 `APP_ORIGIN`과 다르거나 없으면 `403 FORBIDDEN_ORIGIN` 응답을 만든다. GET, HEAD는 통과
- [x] T021 [P] apps/web/src/shared/server/client-ip.ts와 테스트: `x-forwarded-for`의 첫 값, 없으면 `x-real-ip`, 없으면 `unknown`
- [x] T022 apps/web/src/shared/server/api-client.ts와 테스트: `API_ORIGIN`으로 요청하면서 `X-Ogu-Bff-Key`, `X-Ogu-Client-Ip`를 붙이고, 응답을 generated.ts 타입의 `ApiResponse`로 파싱한다. 네트워크 실패는 `502`와 `ApiResponse` 오류 봉투로 바꾼다
- [x] T023 apps/web/src/app/api/[...path]/route.ts(범용 프록시): `__Host-ogu_at`을 `Authorization: Bearer`로 바꿔 `API_ORIGIN/api/v1/{path}`로 전달한다. 상태 변경 메서드에는 origin-guard를 적용한다. refresh 재시도는 US4(T058)에서 추가한다. 기존 `/api/health` 라우트가 계속 동작하는지 확인한다
- [x] T024 [P] apps/web/src/entities/member/: `model/types.ts`(generated.ts에서 `MemberProfile`, `JobRole`, `CareerYear` 재공개, 직군과 경력의 한국어 라벨 맵), `api/queries.ts`(`useMeQuery` → `GET /api/members/me`), `index.ts`
- [ ] T025 infra/compose.e2e.yaml(Postgres `pgvector/pgvector:pg17` + API 이미지, `SPRING_PROFILES_ACTIVE=e2e`, 고정 `JWT_SECRET`, `OGU_BFF_KEY=e2e-bff-key`)과 apps/web/playwright.full.config.ts(`testDir: ./e2e-full`, 웹 서버 env에 `API_ORIGIN=http://localhost:18080`, `BFF_API_KEY=e2e-bff-key`)를 만들고, .github/workflows/ci.yml에 `e2e-full` job(api bootJar → 이미지 빌드 → compose.e2e 기동 → 헬스 대기 → `pnpm --filter web test:e2e:full` → 정리)을 추가한다. `ci-ok`의 needs에 넣는다. 첫 테스트로 apps/web/e2e-full/smoke.spec.ts(첫 화면에 "서버 정상")를 둔다

**Checkpoint**: 스키마, 계약 검증, 보안 뼈대, BFF 공통 코드, 전체 E2E 환경이 준비됨

---

## Phase 3: User Story 1 - 이메일로 가입하고 온보딩을 마친다 (Priority: P1) 🎯 MVP

**Goal**: 새 방문자가 이메일로 가입하고 닉네임, 직군, 경력을 입력해 홈에 도착한다

**Independent Test**: 새 이메일로 가입 → 온보딩 → `/home` 도착, 브라우저를 다시 열어도 로그인 유지

### Tests for User Story 1 ⚠️ (먼저 쓰고 실패 확인)

- [ ] T026 [P] [US1] apps/api/src/test/kotlin/com/ogu/member/domain/MemberTest.kt: 이메일 정규화(앞뒤 공백 제거, 소문자), 닉네임 검증 `^[가-힣A-Za-z0-9]{1,10}$`(앞뒤 공백 제거 후), `nickname_key = lower(nickname)`, 온보딩 두 번 호출 시 예외
- [ ] T027 [P] [US1] apps/api/src/test/kotlin/com/ogu/member/presentation/SignupApiTests.kt(MockMvc, Testcontainers): `US1-AC1 새 이메일과 규칙에 맞는 비밀번호로 가입하면 201과 onboarded=false 토큰을 받는다`, `US1-AC2 이미 가입된 이메일은 409 EMAIL_ALREADY_REGISTERED`(대소문자만 다른 이메일 포함), 외부 계정 회원이 쓰는 이메일로 가입하면 409 `EMAIL_REGISTERED_WITH_OTHER_METHOD`, `US1-AC3 비밀번호 규칙 위반은 400`(7자, 21자, 숫자 없음, 영문 없음 각각), 동시 가입 요청 두 개 중 하나만 성공
- [ ] T028 [P] [US1] apps/api/src/test/kotlin/com/ogu/member/presentation/OnboardingApiTests.kt: `US1-AC4 온보딩을 마치면 프로필이 저장되고 onboarded=true인 새 access 토큰을 받는다`, `US1-AC5 대소문자만 다른 닉네임도 409 NICKNAME_TAKEN`, `US1-AC6 공백, 특수문자, 이모지가 든 닉네임은 400`, 닉네임 확인 API가 `INVALID_FORMAT`/`TAKEN`/사용 가능을 구분한다, 이미 온보딩한 회원은 409 `ALREADY_ONBOARDED`, 두 회원이 같은 닉네임으로 동시에 온보딩하면 한 명만 성공하고 다른 한 명은 409 `NICKNAME_TAKEN`, `US1-AC7 온보딩 전 토큰으로 보호 API를 부르면 403 ONBOARDING_REQUIRED`
- [ ] T029 [P] [US1] apps/web/src/features/onboarding/model/schema.test.ts: Zod 스키마가 API와 같은 닉네임 규칙을 적용한다(허용, 거부 사례)
- [ ] T030 [P] [US1] apps/web/e2e-full/signup-onboarding.spec.ts: `US1-AC1`, `US1-AC4`(가입 → `/onboarding` → 완료 → `/home`), `US1-AC5`(중복 닉네임 안내), `US1-AC7`(온보딩 전 `/home` 접근 시 `/onboarding`으로 이동)

### Implementation for User Story 1

- [ ] T031 [US1] member/domain/Member.kt, MemberRepository.kt: 필드와 제약은 data-model.md `member` 표 그대로, 팩토리 `registerWithEmail(email, passwordHash)`, `completeOnboarding(nickname, jobRole, careerYear, now)`. `BaseTimeEntity`를 상속한다
- [ ] T032 [US1] member/application/SignupService.kt: 비밀번호 규칙 검증(8~20자, 영문 1자 이상, 숫자 1자 이상), `DelegatingPasswordEncoder`(bcrypt) 해시, 가입과 `SessionService.issue`를 한 트랜잭션으로 처리, 가입 전에 가입 방법과 관계없이 같은 `email`의 회원이 있는지 확인해 외부 계정 회원이면 `EMAIL_REGISTERED_WITH_OTHER_METHOD`, 이메일 회원이면 `EMAIL_ALREADY_REGISTERED`로 거절하고, 동시 가입으로 인한 유일 제약 위반도 `EMAIL_ALREADY_REGISTERED`로 바꾼다
- [ ] T033 [US1] member/application/OnboardingService.kt: 닉네임 확인(`checkNickname`)과 온보딩 완료. 닉네임 유일 제약 위반을 `NICKNAME_TAKEN`으로 바꾸고, 완료 후 `JwtIssuer`로 `onboarded=true` 토큰을 발급한다(세션 ID 유지)
- [ ] T034 [US1] member/presentation/AuthController.kt의 `POST /api/v1/auth/signup`과 member/presentation/MemberController.kt의 `GET /api/v1/members/me`, `GET /api/v1/members/nickname-availability`, `PUT /api/v1/members/me/onboarding`, 요청과 응답 DTO(member/presentation/dto/). springdoc 어노테이션은 계약의 operationId, 응답 코드와 맞춘다. ContractTests의 `pendingPaths`에서 이 네 경로를 뺀다
- [ ] T035 [US1] member/application/MemberQueryService.kt가 `MemberApi`를 구현한다
- [ ] T036 [P] [US1] apps/web/src/app/api/auth/signup/route.ts와 apps/web/src/app/api/auth/onboarding/route.ts: origin-guard → api-client 호출 → 성공 시 auth-cookies로 쿠키 설정(가입은 `ogu_ob` 삭제, 온보딩은 `ogu_at` 교체와 `ogu_ob` 설정) → 본문에는 `member`만 반환
- [ ] T037 [P] [US1] apps/web/src/features/auth/email-signup/(model/schema.ts, api/use-signup-mutation.ts, ui/signup-form.tsx, index.ts)와 apps/web/src/app/(auth)/signup/page.tsx. 성공하면 `/onboarding`으로 이동, 409는 "이미 가입된 이메일" 안내, 400은 비밀번호 규칙 안내
- [ ] T038 [P] [US1] apps/web/src/features/onboarding/(model/schema.ts, api/use-nickname-check.ts(입력 뒤 400ms 디바운스), api/use-onboarding-mutation.ts, ui/onboarding-form.tsx, index.ts)와 apps/web/src/app/onboarding/page.tsx. 직군과 경력은 entities/member의 라벨 맵으로 선택지를 만든다. 완료하면 `/home`으로 이동
- [ ] T039 [US1] apps/web/src/proxy.ts를 bff-routes.md 가드 표대로 다시 쓴다(US1 범위: `ogu_ob` 없으면 `/onboarding`, `/onboarding`에 `ogu_ob` 있으면 `/home`). 가드 판단 로직은 순수 함수 apps/web/src/shared/server/route-guard.ts로 분리하고 표의 모든 행을 route-guard.test.ts로 검증한다
- [ ] T040 [P] [US1] apps/web/src/app/home/page.tsx(닉네임 인사와 "고민 쓰기는 준비 중" 자리 표시, `useMeQuery`)와 apps/web/src/app/my/page.tsx(닉네임, 직군, 경력 표시 – FR-015)

**Checkpoint**: 이메일 가입과 온보딩이 단독으로 동작한다 (MVP)

---

## Phase 4: User Story 2 - 이메일로 로그인하고 로그아웃한다 (Priority: P1)

**Goal**: 가입한 사용자가 로그인하고 로그아웃한다. 로그인 실패 제한이 동작한다

**Independent Test**: US1 계정으로 로그아웃 → 로그인 → `/home`, 틀린 비밀번호 5번 뒤 차단

### Tests for User Story 2 ⚠️

- [ ] T041 [P] [US2] apps/api/src/test/kotlin/com/ogu/member/application/LoginThrottleTest.kt(Testcontainers, 시계 주입): IP+이메일 키 15분 창 5회 → 15분 차단, 이메일 키 1시간 창 20회 → 1시간 차단, 창이 지나면 초기화, 성공 시 IP+이메일 키만 삭제, 동시 실패 10건이 정확히 10으로 집계
- [ ] T042 [P] [US2] apps/api/src/test/kotlin/com/ogu/member/presentation/LoginApiTests.kt: `US2-AC1 올바른 이메일과 비밀번호로 로그인하면 200`, `US2-AC2 틀린 비밀번호와 없는 이메일은 같은 401 INVALID_CREDENTIALS와 같은 메시지`, `US2-AC3 한 IP에서 15분 안에 5번 실패하면 올바른 비밀번호도 429이고 다른 IP는 로그인된다`, `US2-AC4 여러 IP에서 1시간 안에 20번 실패하면 모든 IP에서 429`, 429 응답에 `Retry-After`와 `retryAfterSeconds`, BFF 키가 틀리면 `X-Ogu-Client-Ip`를 무시한다
- [ ] T043 [P] [US2] apps/api/src/test/kotlin/com/ogu/member/presentation/LogoutApiTests.kt: `US2-AC5 로그아웃하면 204이고 같은 access 토큰으로 보호 API를 부르면 401 SESSION_EXPIRED`, 이미 무효인 세션의 토큰으로 로그아웃하면 401 `SESSION_EXPIRED`(계약과 같음)
- [ ] T044 [P] [US2] apps/web/e2e-full/login-logout.spec.ts: `US2-AC1`, `US2-AC5`(로그아웃 후 `/login`, 뒤로 가기로 `/home`에 가도 이전 데이터가 보이지 않음)

### Implementation for User Story 2

- [ ] T045 [US2] member/domain/LoginAttempt.kt, LoginAttemptRepository.kt(네이티브 `INSERT ... ON CONFLICT (scope_key) DO UPDATE`로 원자적 증가), member/application/LoginThrottle.kt(data-model.md `login_attempt` 표의 창, 한도, 차단 값), 하루 지난 행을 지우는 `@Scheduled` 작업
- [ ] T046 [US2] member/application/LoginService.kt: 차단 확인(차단 중이면 비밀번호 검증 없이 `LOGIN_THROTTLED`) → 이메일 정규화 → 회원 조회 → 비밀번호 검증(회원이 없어도 더미 해시로 한 번 검증해 응답 시간 차이를 줄인다) → 실패 기록 또는 성공 처리 → `SessionService.issue`
- [ ] T047 [US2] member/application/SessionService.kt에 `revoke(sessionId, reason)` 추가, AuthController에 `POST /api/v1/auth/login`, `POST /api/v1/auth/logout` 추가, `LOGIN_THROTTLED` 응답에 `Retry-After` 헤더를 붙이는 예외 처리. ContractTests `pendingPaths`에서 두 경로를 뺀다
- [ ] T048 [P] [US2] apps/web/src/app/api/auth/login/route.ts와 apps/web/src/app/api/auth/logout/route.ts: 로그인 성공 시 온보딩 여부에 따라 `ogu_ob` 설정, 로그아웃은 API 결과와 관계없이 세 쿠키 삭제 후 204
- [ ] T049 [P] [US2] apps/web/src/features/auth/email-login/(schema, use-login-mutation, ui/login-form.tsx, index.ts), apps/web/src/features/auth/logout/(use-logout-mutation, ui/logout-button.tsx, index.ts), apps/web/src/app/(auth)/login/page.tsx. 429는 남은 시간을 분 단위로 안내한다. 로그아웃하면 TanStack Query 캐시를 비우고 `/login`으로 이동한다. apps/web/src/app/home/page.tsx와 my/page.tsx에 로그아웃 버튼을 둔다

**Checkpoint**: US1과 US2가 각각 단독으로 동작한다

---

## Phase 5: User Story 3 - 카카오나 구글 계정으로 시작한다 (Priority: P2)

**Goal**: 외부 계정으로 가입하고 로그인한다

**Independent Test**: e2e 프로필의 가짜 제공자로 첫 로그인 → 온보딩 → `/home`, 다시 로그인 → 바로 `/home`

### Tests for User Story 3 ⚠️

- [ ] T050 [P] [US3] apps/api/src/test/kotlin/com/ogu/member/presentation/OAuthLoginApiTests.kt(가짜 `OAuthProviderClient` 빈): `US3-AC1 처음 쓰는 외부 계정은 newMember=true, onboarded=false`, `US3-AC2 온보딩한 외부 계정은 onboarded=true`, `US3-AC3 이메일 가입 회원과 같은 이메일이면 409 EMAIL_REGISTERED_WITH_OTHER_METHOD`(구글 `email_verified=false`면 충돌로 보지 않고 새 회원), 허용 목록에 없는 `redirectUri`는 400, 제공자가 코드를 거절하면 401 `OAUTH_CODE_INVALID`, 제공자 타임아웃은 502, 이메일 없는 카카오 계정도 가입된다
- [ ] T051 [P] [US3] apps/api/src/test/kotlin/com/ogu/member/infrastructure/oauth/KakaoClientTest.kt, GoogleClientTest.kt(`MockRestServiceServer`): 토큰 교환 요청 형식(PKCE 사용 여부는 T001 결과대로), 사용자 정보 파싱, 오류 응답을 `OAUTH_CODE_INVALID`/`OAUTH_PROVIDER_UNAVAILABLE`로 변환. 구글은 `id_token`의 `iss`, `aud`, `exp`를 검증한다
- [ ] T052 [P] [US3] apps/web/src/shared/server/oauth-state.test.ts: state와 code_verifier 생성(32바이트 base64url), `code_challenge = base64url(SHA-256(verifier))`, 쿠키 직렬화와 대조, 10분 만료, `next` 검증
- [ ] T053 [P] [US3] apps/web/e2e-full/oauth.spec.ts(가짜 제공자): `US3-AC1`, `US3-AC2`, `US3-AC4`(동의 취소 → `/login?error=oauth_cancelled`, 오류 화면 없음)

### Implementation for User Story 3

- [ ] T054 [US3] member/infrastructure/oauth/OAuthProviderClient.kt(인터페이스: `exchange(code, redirectUri, codeVerifier): OAuthUserInfo(providerUserId, email, emailVerified)`), KakaoClient.kt, GoogleClient.kt(`RestClient`, 연결 3초, 읽기 5초 타임아웃), FakeOAuthProviderClient.kt(`@Profile("e2e")`, 코드 문자열에서 사용자 ID와 이메일을 만든다. 코드가 `denied`면 거절). `e2e`와 `prod` 프로필이 함께 켜지면 기동을 실패시키는 검사(member/infrastructure/oauth/E2eProfileGuard.kt)와 그 테스트를 둔다
- [ ] T055 [US3] member/domain/OAuthIdentity.kt, OAuthIdentityRepository.kt, Member 팩토리 `registerWithOAuth(provider, email)`, member/application/OAuthLoginService.kt(research R5 규칙: 연결된 계정이면 로그인, 아니면 이메일 충돌 확인 후 새 회원과 연결 생성, `redirectUri` 허용 목록 대조), AuthController `POST /api/v1/auth/oauth/{provider}`. ContractTests `pendingPaths`에서 이 경로를 뺀다
- [ ] T056 [US3] apps/web/src/shared/server/oauth-state.ts와 apps/web/src/app/api/auth/oauth/[provider]/route.ts(시작), apps/web/src/app/api/auth/oauth/[provider]/callback/route.ts(콜백): bff-routes.md 표대로. 카카오와 구글 인가 URL, scope(구글 `openid email`, 카카오 `account_email`은 선택 동의), `redirect_uri = APP_ORIGIN + /api/auth/oauth/{provider}/callback`. e2e 프로필에서는 제공자 대신 `/api/auth/oauth/{provider}/callback?code=fake-...&state=...`로 바로 보내는 분기를 `APP_ENV=e2e`일 때만 켠다. `VERCEL_ENV=production`인데 `APP_ENV=e2e`면 env.ts 검증에서 빌드와 기동을 실패시키고, 이 규칙을 env 테스트로 확인한다
- [ ] T057 [P] [US3] apps/web/src/features/auth/oauth-buttons/(ui/oauth-buttons.tsx, index.ts): 카카오, 구글 버튼은 `<a href="/api/auth/oauth/{provider}?next=...">`로 이동한다. login과 signup 페이지에 넣고, `/login?error=`의 세 값(`oauth_cancelled`, `oauth_failed`, `email_registered`)에 맞는 안내를 로그인 페이지에 표시한다

**Checkpoint**: 외부 계정 로그인이 단독으로 동작한다

---

## Phase 6: User Story 4 - 로그인 상태가 끊기지 않고, 보호된 화면은 막힌다 (Priority: P2)

**Goal**: 세션 갱신, 유예, 재사용 감지, 최대 기간, 보호 화면 차단과 복귀

**Independent Test**: access 토큰 만료 뒤에도 보호 기능이 동작하고, 로그아웃 상태에서 `/my`로 가면 로그인 후 `/my`로 돌아온다

### Tests for User Story 4 ⚠️

- [ ] T058 [P] [US4] apps/api/src/test/kotlin/com/ogu/member/application/SessionRefreshTest.kt(시계 주입): data-model.md refresh 흐름도의 모든 분기 — 정상 교체와 `expires_at = min(now+14일, absolute)`, `US4-AC2 마지막 활동 뒤 14일이 지나면 SESSION_EXPIRED`, `US4-AC3 로그인 30일 뒤에는 활동 중이어도 SESSION_EXPIRED`, 교체 후 30초 안의 직전 토큰은 access만 발급(refreshToken null), 30초 뒤 직전 토큰은 세션 무효화(`REUSE_DETECTED`), 같은 토큰으로 동시 요청 두 건이 모두 로그아웃 없이 끝난다
- [ ] T059 [P] [US4] apps/web/src/app/api/[...path]/route.test.ts: API 401 → refresh 성공 → 원래 요청 재시도 성공, refresh 응답의 `refreshToken`이 null이면 `ogu_rt` 유지, refresh 실패 → 세 쿠키 삭제와 401, `ogu_at`이 없고 `ogu_rt`만 있으면 먼저 refresh
- [ ] T060 [P] [US4] apps/web/e2e-full/session.spec.ts(API e2e 프로필의 access TTL을 `ogu.auth.jwt.access-token-ttl=5s`로 줄인다): `US4-AC1 인증 유효 시간이 지나도 보호 기능이 그대로 동작한다`, `US4-AC4 로그아웃 상태로 /my에 들어가면 로그인 화면으로 이동한다`, `US4-AC5 로그인하면 /my로 돌아온다`, `US4-AC6 document.cookie에 ogu_ 쿠키가 없다`, 외부 주소 `next`는 `/home`으로

### Implementation for User Story 4

- [ ] T061 [US4] member/application/SessionService.kt에 `refresh(refreshToken)` 추가(data-model.md 흐름도, `SELECT ... FOR UPDATE`, 유예 `ogu.auth.session.rotation-grace`), AuthController `POST /api/v1/auth/refresh`. ContractTests `pendingPaths`에서 이 경로를 뺀다
- [ ] T062 [US4] apps/web/src/app/api/[...path]/route.ts에 refresh 재시도를 추가하고, auth 라우트와 공유하는 refresh 호출을 apps/web/src/shared/server/session-refresh.ts로 분리한다
- [ ] T063 [US4] apps/web/src/shared/server/route-guard.ts와 proxy.ts에 bff-routes.md 표의 나머지 행(`/` → `/home`, 보호 경로 → `/login?next=`, `/login`·`/signup` → `/home`)과 `next` 검증(`/`로 시작, `//`와 `/\`로 시작하지 않음)을 추가하고 route-guard.test.ts를 보강한다. 로그인, 가입, OAuth 성공 뒤 `next`로 이동하도록 T037, T049, T056의 이동 처리를 고친다

**Checkpoint**: 세션 유지와 보호 화면 차단이 모두 동작한다

---

## Phase 7: User Story 5 - 운영자가 화면 오류를 수집한다 (Priority: P3)

**Goal**: 운영 환경의 화면 오류를 민감 정보 없이 Sentry로 보낸다

**Independent Test**: quickstart.md 6~7번(운영 수동 검증)

### Tests for User Story 5 ⚠️

- [ ] T064 [P] [US5] apps/web/src/shared/lib/scrub-event.test.ts: `US5-AC2 오류 이벤트에서 쿠키, Authorization 헤더, password 필드를 지운다`(요청 헤더, 요청 본문 JSON 문자열, breadcrumbs의 fetch 데이터 각각)

### Implementation for User Story 5

- [ ] T065 [US5] apps/web/src/shared/lib/scrub-event.ts, apps/web/instrumentation.ts, apps/web/instrumentation-client.ts, apps/web/sentry.server.config.ts, apps/web/sentry.edge.config.ts: `NEXT_PUBLIC_SENTRY_DSN`이 있을 때만 초기화, `beforeSend`와 `beforeBreadcrumb`에 scrub-event 적용, `sendDefaultPii: false`, 소스맵 업로드 없음. apps/web/src/app/debug/error-probe/page.tsx는 `ENABLE_ERROR_PROBE !== "1"`이면 `notFound()`, 켜져 있으면 버튼을 눌러 오류를 던진다

**Checkpoint**: 모든 스토리 완료

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T066 ContractTests의 `pendingPaths`가 비어 있고 계약의 8개 경로가 모두 일치하는지 확인한다
- [ ] T067 [P] `grep -rn "US[1-5]-AC[0-9]" apps/`로 스펙의 인수 조건 24개가 모두 테스트 이름에 있는지 확인하고, 자동화하지 않는 US5-AC1은 quickstart.md에 수동 절차가 있는지 확인한다. 빠진 ID가 있으면 해당 테스트를 추가한다
- [ ] T068 [P] apps/api/AGENTS.md에 `member` 모듈의 공개 타입, 보안 설정 위치, 온보딩 가드 허용 목록을 추가하고, apps/web/docs/ARCHITECTURE.md의 BFF 절에 인증 라우트, 쿠키, 라우트 가드를 반영한다
- [ ] T069 [P] docs/architecture/overview.md 5.1 모듈 표의 `member` 행과 6.3 인증 절을 구현과 맞춘다(Postgres 기반 로그인 제한, refresh 교체와 30초 유예)
- [ ] T070 specs/002-auth/quickstart.md의 "수동 검증 시나리오"를 로컬에서 끝까지 실행하고, 다른 결과가 나오면 문서나 코드를 고친다. 로그인 API 100회와 보호 API 100회(세션 확인 포함, 미포함 비교)의 p95를 재서 plan.md 성능 목표와 비교하고 결과를 quickstart.md에 표로 남긴다
- [ ] T071 `/speckit-analyze`로 spec, plan, tasks의 일관성을 확인하고 PR을 연다(PR 본문에 스펙 링크와 인수 조건 체크리스트, quickstart의 운영 준비 항목)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음. T001은 T051, T056보다 먼저 끝나야 한다
- **Foundational (Phase 2)**: Setup 뒤. 모든 스토리를 막는다
- **US1 (Phase 3)**: Foundational 뒤. 다른 스토리에 의존하지 않는다 (MVP)
- **US2 (Phase 4)**: Foundational 뒤. 테스트 데이터는 직접 만들어 US1 없이도 검증할 수 있다. 웹의 홈 화면(T040)에 로그아웃 버튼을 붙이는 T049만 US1 뒤에 한다
- **US3 (Phase 5)**: Foundational 뒤. 온보딩 화면(T038)을 재사용하므로 웹 E2E(T053)는 US1 뒤에 돌린다
- **US4 (Phase 6)**: Foundational 뒤. T063의 이동 처리 수정은 T037, T049, T056 뒤에 한다
- **US5 (Phase 7)**: Foundational 뒤. 다른 스토리와 독립
- **Polish (Phase 8)**: 모든 스토리 뒤

### Within Each User Story

- 테스트를 먼저 쓰고 실패를 확인한다
- 도메인 → 애플리케이션 서비스 → 컨트롤러 → BFF 라우트 → 화면 순서
- 컨트롤러를 추가할 때마다 ContractTests의 `pendingPaths`를 줄인다

### Parallel Opportunities

- Setup: T003, T004, T005
- Foundational: T009, T011, T012, T013, T018, T019, T020, T021, T024 (T006 뒤에 T007, T014 → T015 → T016 → T017은 순차)
- 스토리 사이: Foundational이 끝나면 US1, US2(API 부분), US5는 동시에 할 수 있다

---

## Parallel Example: User Story 1

```bash
# 테스트를 한꺼번에 작성
Task: "T026 MemberTest (도메인 규칙)"
Task: "T027 SignupApiTests"
Task: "T028 OnboardingApiTests"
Task: "T029 온보딩 Zod 스키마 테스트"
Task: "T030 e2e-full 가입과 온보딩"

# API 구현 뒤 웹 화면을 병렬로
Task: "T036 signup, onboarding BFF 라우트"
Task: "T037 가입 화면"
Task: "T038 온보딩 화면"
Task: "T040 홈, 마이페이지 자리 표시"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1
3. **멈추고 검증**: e2e-full의 가입과 온보딩 시나리오, quickstart 수동 시나리오 1~4
4. 필요하면 이 상태로 배포(로그인은 가입 직후 세션만 유지)

### Incremental Delivery

1. US1 → US2(로그인, 로그아웃) → 배포: 이메일 사용자만으로 서비스 이용 가능
2. US4(세션 유지와 보호 화면) → 배포: 14~30일 세션과 안전한 복귀
3. US3(외부 로그인) → 운영 키 등록 후 배포
4. US5(오류 수집) → Sentry DSN 설정 후 배포

### Notes

- 커밋 메시지와 PR 제목은 Conventional Commits
- 각 작업이나 논리적 묶음이 끝나면 커밋한다
- `webbb-be/`, `webbb-fe/`는 수정하지 않는다
