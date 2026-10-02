# Implementation Plan: 인증과 회원

**Branch**: `002-auth` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-auth/spec.md`

## Summary

이메일 가입과 로그인, 카카오와 구글 로그인, 온보딩(닉네임, 직군, 경력), 로그아웃, 세션 유지, 보호 화면 차단, 화면 오류 수집을 만든다.

API는 `member` 모듈 하나에 회원, 외부 계정 연결, 세션, 로그인 실패 기록을 둔다. access 토큰은 15분짜리 JWT로 발급하고, 요청마다 세션이 살아 있는지 확인해서 로그아웃을 즉시 반영한다. refresh 토큰은 교체하면서 재사용을 감지하되, 여러 탭의 동시 갱신을 위해 30초 유예를 둔다.

웹은 ADR-0002의 BFF 패턴을 따른다. 토큰은 `__Host-` httpOnly 쿠키에만 두고, 라우트 핸들러가 가입, 로그인, OAuth 콜백, 온보딩, 범용 프록시를 맡는다. OAuth는 제공자가 웹 도메인으로 돌아오고 API가 코드를 교환하는 방식이다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1, Spring Security(`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`의 Nimbus JWT), Spring `RestClient`(제공자 호출) / Next.js 16, TanStack Query 5, React Hook Form, Zod 4, `@sentry/nextjs`, `openapi-typescript`

**Storage**: PostgreSQL 17(M0와 같은 인스턴스). Flyway `V2__member_auth.sql`로 `member`, `oauth_identity`, `auth_session`, `login_attempt` 테이블을 만든다

**Testing**: JUnit 5, `@ApplicationModuleTest`, MockMvc, Testcontainers(pgvector/pg17), 계약 테스트(springdoc vs `contracts/openapi.yaml`) / Vitest, Testing Library, Playwright(BFF 단독 `test:e2e`, 전체 `test:e2e:full`)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 로그인과 가입 p95 500ms 이하(bcrypt 약 100ms 포함), 보호 API의 세션 확인 추가 비용 p95 5ms 이하. T070에서 로컬 측정으로 확인하고 결과를 quickstart에 남긴다

**Constraints**: 토큰이 브라우저 스크립트에 노출되지 않는다(FR-012). 로그아웃은 즉시 반영된다(SC-004). 새 유료 서비스는 없다(constitution VI)

**Scale/Scope**: 회원 1만 명 이하, 동시 세션 수천 개, 화면 6개(`/login`, `/signup`, `/onboarding`, OAuth 콜백 처리, `/home` 자리 표시, `/my` 자리 표시)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 확인 | 결과 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈은 `member` 하나다. 다른 모듈은 루트의 `MemberApi`, `MemberInfo`, `AuthenticatedMember`만 쓴다. 보안 설정도 `member` 안(`member.infrastructure.security`)에 두어 `shared → member` 역방향 의존을 만들지 않는다. 웹은 새 슬라이스를 FSD 레이어에 맞게 두고 steiger가 검사한다 | 통과 |
| II. 계약이 코드보다 먼저다 | `contracts/openapi.yaml`을 이 plan에서 먼저 확정했다. 구현 첫 작업이 계약 테스트와 `openapi-typescript` 타입 생성이다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 스펙의 인수 조건 24개에 테스트 이름 ID를 붙인다. 자동화하지 못하는 US5-AC1, US5-AC2와 OAuth 실제 제공자 연동은 quickstart에 수동 절차로 적었다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 이번 마일스톤에는 해당 없음. 로그인 실패 제한과 계정 자동 병합 금지로 계정 안전을 챙겼다 | 해당 없음 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI 호출 없음. 같은 원칙으로, 외부 로그인 제공자 장애가 이메일 로그인을 막지 않는다(`502 OAUTH_PROVIDER_UNAVAILABLE`) | 해당 없음 |
| VI. 무료 인프라 안에서 운영한다 | 카카오, 구글 로그인은 무료이고 Sentry는 무료 플랜을 쓴다. 로그인 실패 제한은 Redis 대신 기존 Postgres로 한다 | 통과 |

**Phase 1 설계 후 재확인**: 데이터 모델의 테이블 4개는 모두 `member` 모듈 소유이며 다른 모듈과 조인하지 않는다. 계약의 모든 응답은 `ApiResponse` 봉투를 따른다. 위반 사항이 없어 Complexity Tracking은 비워 둔다.

## Project Structure

### Documentation (this feature)

```text
specs/002-auth/
├── spec.md
├── plan.md              # 이 문서
├── research.md          # 결정 R1~R11
├── data-model.md        # 테이블 4개, 세션 갱신 흐름, JWT 클레임
├── quickstart.md        # 검증 절차와 운영 준비
├── contracts/
│   ├── openapi.yaml     # API 계약 (8개 엔드포인트)
│   └── bff-routes.md    # 브라우저가 부르는 BFF 라우트, 라우트 가드
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks에서 만든다
```

### Source Code (repository root)

```text
apps/api/src/main/kotlin/com/ogu/
├── OguApplication.kt
├── shared/                            # 기존. ErrorCode에 인증 오류 코드 추가
└── member/
    ├── MemberApi.kt                   # 공개 파사드
    ├── MemberInfo.kt                  # 공개 DTO
    ├── AuthenticatedMember.kt         # 공개. 컨트롤러 인자 타입
    ├── JobRole.kt, CareerYear.kt      # 공개 enum
    ├── domain/                        # Member, OAuthIdentity, AuthSession, LoginAttempt, 리포지토리
    ├── application/                   # SignupService, LoginService, OAuthLoginService,
    │                                  # SessionService, OnboardingService, LoginThrottle
    ├── infrastructure/
    │   ├── security/                  # SecurityConfig, JwtIssuer, SessionCheckFilter, BffClientIpResolver
    │   └── oauth/                     # OAuthProviderClient, KakaoClient, GoogleClient, FakeOAuthProviderClient(e2e 프로필)
    └── presentation/                  # AuthController, MemberController, DTO
apps/api/src/main/java/com/ogu/member/package-info.java
apps/api/src/main/resources/db/migration/V2__member_auth.sql
apps/api/src/test/kotlin/com/ogu/
├── ContractTests.kt                   # springdoc 스펙과 contracts/openapi.yaml 비교
└── member/                            # 모듈 테스트, 컨트롤러 통합 테스트, 도메인 단위 테스트

apps/web/src/
├── app/
│   ├── (auth)/login/page.tsx, (auth)/signup/page.tsx
│   ├── onboarding/page.tsx
│   ├── home/page.tsx, my/page.tsx     # 자리 표시(M2, M3에서 채운다)
│   ├── debug/error-probe/page.tsx     # ENABLE_ERROR_PROBE=1일 때만
│   └── api/
│       ├── auth/signup, login, logout, onboarding/route.ts
│       ├── auth/oauth/[provider]/route.ts, auth/oauth/[provider]/callback/route.ts
│       └── [...path]/route.ts         # 범용 프록시
├── proxy.ts                           # 라우트 가드 (bff-routes.md 표)
├── entities/member/                   # 타입, useMeQuery, 프로필 UI
├── features/auth/                     # email-signup, email-login, oauth-buttons, logout
├── features/onboarding/               # 온보딩 폼, 닉네임 확인
├── widgets/                           # 기존 service-status
└── shared/
    ├── api/generated.ts               # openapi-typescript 산출물
    ├── server/                        # 서버 전용: api-client, auth-cookies, oauth-state, origin-guard, client-ip
    └── config/env.ts                  # BFF_API_KEY, APP_ORIGIN, OAuth client id, Sentry DSN 추가
apps/web/instrumentation.ts, instrumentation-client.ts, sentry.*.config.ts
apps/web/e2e-full/*.spec.ts             # API까지 띄운 인증 흐름. 기존 apps/web/e2e/는 M0 랜딩 테스트만 둔다
infra/compose.e2e.yaml                 # CI e2e-full: Postgres + API(e2e 프로필)
```

**Structure Decision**: M0의 모노레포 구조를 그대로 쓴다. API는 Modulith 모듈 `member`를 추가하고, 웹은 FSD의 `entities/member`, `features/auth`, `features/onboarding`과 서버 전용 코드를 모은 `shared/server`를 추가한다. `shared/server`는 route handler와 `proxy.ts`에서만 import하도록 `server-only` 패키지로 막는다.

## Complexity Tracking

위반 사항 없음.
