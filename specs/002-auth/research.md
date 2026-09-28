# Research: 인증과 회원 (002-auth)

스펙의 요구사항을 구현 결정으로 옮기면서 검토한 내용이다. 형식은 결정, 근거, 검토한 대안 순서다.

## R1. 토큰과 세션 구조

**결정**: access 토큰은 15분짜리 JWT(HS256)이고, `sub`(회원 ID), `sid`(세션 ID), `onboarded` 클레임을 담는다. refresh 토큰은 256비트 난수 문자열이며, 서버에는 SHA-256 해시만 `auth_session`에 저장한다. API는 모든 보호 요청에서 JWT 서명을 검증하고, `sid`로 세션이 살아 있는지(`revoked_at IS NULL`, 만료 전) 확인한다.

**근거**:
- SC-004는 "로그아웃한 세션으로 요청하면 100% 거부"를 요구한다. JWT만 쓰면 로그아웃 뒤에도 최대 15분 동안 토큰이 유효하므로, 요청마다 세션 상태를 확인해야 한다. PK 조회 한 번이라 이 규모에서는 비용이 작다.
- 그런데도 JWT를 쓰는 이유는, 클레임에 회원 ID와 온보딩 여부가 들어 있어 다른 모듈(post, feed 등)이 회원 모듈을 부르지 않고 인가 판단을 할 수 있기 때문이다.
- refresh 토큰을 해시로만 저장하므로, DB가 유출돼도 세션을 탈취할 수 없다.

**검토한 대안**:
- 순수 JWT(세션 확인 없음): SC-004를 만족하지 못한다.
- 불투명 세션 ID 하나만 사용: 단순하지만, 다른 모듈이 매번 회원 정보를 조회해야 한다.
- RS256: 토큰을 발급하고 검증하는 주체가 API 하나라 비대칭 키가 필요 없다.

## R2. 세션 만료와 갱신 (FR-010, US4-AC1~AC3)

**결정**: refresh할 때마다 토큰을 교체(rotation)하고, 만료 시각을 `min(now + 14일, 로그인 시각 + 30일)`로 다시 계산한다. 교체 직후 30초 동안은 직전 토큰도 받아 준다. 이때는 새 access 토큰만 발급하고 refresh 토큰은 새로 주지 않는다. 30초가 지난 뒤 직전 토큰이 다시 오면 탈취로 보고 세션을 무효로 만든다.

**근거**:
- 여러 탭이 동시에 만료되면(스펙 경계 상황), 두 요청이 같은 refresh 토큰을 거의 동시에 보낸다. 쿠키는 브라우저 안에서 공유되므로 먼저 끝난 요청이 새 refresh 쿠키를 심는다. 늦은 요청은 access 토큰만 받으면 되고, 로그아웃되지 않는다.
- 재사용 감지로 refresh 토큰 탈취를 알아챌 수 있다(OAuth 2.0 Security BCP의 refresh token rotation).

**검토한 대안**:
- 교체하지 않는 refresh 토큰: 탈취되면 30일 동안 감지할 수 없다.
- BFF에서 동시 요청을 하나로 묶기: Vercel 함수는 요청마다 다른 인스턴스에서 돌 수 있어서 보장이 안 된다.

## R3. 브라우저 쿠키 (FR-012, US4-AC6)

**결정**:

| 쿠키 | 내용 | 속성 |
|---|---|---|
| `__Host-ogu_at` | access 토큰 | HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=15분 |
| `__Host-ogu_rt` | refresh 토큰 | HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=세션 만료까지 |
| `__Host-ogu_ob` | 온보딩 완료 여부(`1`) | HttpOnly, Secure, SameSite=Lax, Path=/ |

BFF는 상태를 바꾸는 요청(POST, PUT, PATCH, DELETE)의 `Origin` 헤더가 서비스 출처와 다르면 403으로 거절한다.

**근거**:
- `__Host-` 접두사를 쓰면 하위 도메인이 쿠키를 덮어쓸 수 없다.
- SameSite=Lax에 Origin 검사를 더해 CSRF를 막는다. OAuth 콜백은 최상위 GET 이동이라 Lax 쿠키가 함께 전송된다.
- `__Host-ogu_ob`는 라우트 가드(`proxy.ts`)가 API를 부르지 않고 온보딩 여부를 판단하는 데만 쓴다. 최종 판단은 API가 JWT의 `onboarded` 클레임으로 한다.
- Chrome과 Playwright의 Chromium은 `http://localhost`에서도 Secure 쿠키를 허용해서, 로컬에서도 같은 속성을 쓸 수 있다.

## R4. OAuth 흐름 (FR-005, FR-006, US3)

**결정**: 제공자가 웹 도메인으로 돌아오는 BFF 주도 인가 코드 흐름을 쓴다.

1. 브라우저가 `GET /api/auth/oauth/{provider}`(BFF)로 이동한다.
2. BFF는 `state`와 PKCE `code_verifier`를 만들어 10분짜리 `__Host-ogu_oauth` 쿠키에 담고, 제공자 인가 URL로 302 리다이렉트한다.
3. 제공자는 `https://<web>/api/auth/oauth/{provider}/callback`으로 돌아온다.
4. BFF는 `state`를 대조한 뒤 `code`, `code_verifier`, `redirect_uri`를 API `POST /api/v1/auth/oauth/{provider}`로 보낸다.
5. API가 제공자와 코드를 교환하고(클라이언트 시크릿은 API만 가진다), 회원을 찾거나 만든 뒤 토큰을 돌려준다.
6. BFF가 쿠키를 심고 온보딩 또는 원래 가려던 화면으로 보낸다.

사용자가 동의를 취소하면(`error=access_denied`) BFF는 `/login?error=oauth_cancelled`로 보낸다(US3-AC4).

**근거**:
- 사용자는 웹 도메인만 보고, API 도메인은 계속 브라우저에 노출되지 않는다(ADR-0002).
- API가 상태를 들고 있지 않는다. 원본은 API의 Spring OAuth2 Client가 흐름을 처리하고 일회용 교환 코드를 발급했는데, 그러려면 인가 요청 상태를 API에 저장하고 교환 코드용 저장소를 따로 둬야 했다.
- 구글은 OpenID Connect `id_token`의 `sub`, `email`, `email_verified`를 쓴다. 카카오는 토큰으로 `/v2/user/me`를 호출해 `id`와 선택 동의 항목인 이메일을 받는다.

**확인 완료**: 카카오는 PKCE(`code_challenge`/`code_challenge_method`, `code_verifier`)를 지원하지 않는다. 카카오 공식 문서(REST API)의 인가 코드 요청 파라미터 목록에는 `client_id`, `redirect_uri`, `response_type`, `scope`, `prompt`, `login_hint`, `service_terms`, `state`, `nonce`만 있고, 토큰 요청 파라미터에도 `code_verifier`가 없다. 따라서 카카오는 `state`만 쓴다. 기밀 클라이언트라 `state`만으로도 CSRF는 막을 수 있다.

- 지원 여부: 미지원(공식 문서에 PKCE 관련 파라미터 없음)
- 확인한 문서: [카카오 로그인 REST API](https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api) (인가 코드 받기, 토큰 받기 섹션)
- 확인 날짜: 2026-09-28

**검토한 대안**:
- 원본 방식(API가 Spring OAuth2 Client로 흐름 처리 + 일회용 교환 코드): 위 근거대로 상태 저장이 늘어나고, 사용자가 API 도메인을 거친다.
- 프론트엔드에서 제공자 SDK로 토큰 획득: 클라이언트 시크릿을 쓰지 않는 공개 클라이언트가 되고, 토큰이 브라우저 스크립트에 노출된다.

## R5. 외부 계정과 이메일 계정의 충돌 (FR-006, US3-AC3)

**결정**: 외부 계정으로 처음 로그인할 때, 제공자가 준 이메일(구글은 `email_verified=true`인 경우만)이 이메일 가입 회원과 같으면 `409 EMAIL_REGISTERED_WITH_OTHER_METHOD`로 거절한다. 이미 연결된 외부 계정은 이메일과 관계없이 로그인된다.

**근거**: 스펙 명확화에 따라 자동 병합을 하지 않는다. 이메일을 검증하지 않는 제공자 정보로 병합하면 계정 탈취가 가능하다.

## R6. 로그인 실패 제한 (FR-004, US2-AC3~AC4)

**결정**: Redis 대신 Postgres `login_attempt` 테이블을 쓴다. 키는 두 종류다.
- `ip:{ip}|email:{email}`: 15분 창, 5회, 15분 차단
- `email:{email}`: 1시간 창, 20회, 1시간 차단

실패할 때 `INSERT ... ON CONFLICT DO UPDATE`로 원자적으로 늘리고, 성공하면 IP와 이메일 키만 지운다. 차단 중에는 비밀번호를 검증하지 않고 `429 LOGIN_THROTTLED`와 남은 초(`retryAfterSeconds`)를 돌려준다. 가입되지 않은 이메일도 같은 규칙으로 센다(계정 존재 여부를 드러내지 않기 위해).

**근거**: 이 규모에서는 Postgres로 충분하다. Redis는 SSE 팬아웃이 필요한 M3에서 들인다(overview 4장). 차단 중 비밀번호를 검증하지 않으므로 bcrypt 연산을 이용한 부하 공격도 줄어든다.

**클라이언트 IP**: API는 Vercel 함수에서 오는 요청만 받으므로 원격 주소는 항상 Vercel이다. BFF가 원래 IP를 `X-Ogu-Client-Ip`에 담고, 비밀 키 `X-Ogu-Bff-Key`를 함께 보낸다. API는 키가 맞을 때만 이 헤더를 믿고, 아니면 원격 주소를 쓴다. 누군가 API에 직접 요청하며 IP를 위조하는 것을 막기 위해서다.

## R7. 비밀번호 저장 (FR-002)

**결정**: Spring Security `DelegatingPasswordEncoder` 기본값(bcrypt, 강도 10)을 쓴다. 저장 값은 `{bcrypt}...` 형식이라 나중에 argon2로 바꿔도 기존 해시를 그대로 검증할 수 있다.

**검토한 대안**: argon2id는 BouncyCastle 의존성이 추가된다. 지금 규모에서는 bcrypt로 충분하다.

## R8. 이메일과 닉네임 정규화 (FR-001, FR-008)

**결정**:
- 이메일: 앞뒤 공백을 빼고 소문자로 바꿔서 저장하고 비교한다.
- 닉네임: 앞뒤 공백을 빼고 `^[가-힣A-Za-z0-9]{1,10}$`로 검증한다. 입력한 그대로 표시하고, 중복 판단용으로 소문자로 바꾼 `nickname_key`를 따로 저장해 유일 제약을 건다.
- 완성형 한글만 허용한다. 자모(ㄱ, ㅏ)는 받지 않는다.

**근거**: 이메일 대소문자는 사실상 구분하지 않는 것이 관례다(명확화에서 남은 항목). 닉네임 규칙은 명확화 4번을 따른다.

## R9. 온보딩 강제 (FR-007, US1-AC7)

**결정**:
- API: 온보딩 전 회원의 JWT는 `onboarded=false`다. 인증이 필요한 API 중 온보딩 API, `GET /members/me`, 닉네임 확인, 로그아웃을 뺀 나머지는 `403 ONBOARDING_REQUIRED`를 돌려준다. 온보딩을 마치면 API가 새 access 토큰을 발급해 BFF가 쿠키를 갈아 끼운다.
- 웹: `proxy.ts`가 `__Host-ogu_rt`가 없으면 `/login?next=...`로, `__Host-ogu_ob`가 없으면 `/onboarding`으로 보낸다.

## R10. 프론트엔드 오류 수집 (FR-016, US5)

**결정**: `@sentry/nextjs`를 Sentry 무료 플랜과 함께 쓴다. `NEXT_PUBLIC_SENTRY_DSN`이 있을 때만 켜고, `beforeSend`에서 요청 헤더의 쿠키와 `Authorization`, 요청 본문의 `password` 필드를 지운다. 소스맵 업로드는 인증 토큰이 필요해 이번에는 하지 않는다.

**검토한 대안**: Grafana Faro는 이미 쓰는 Grafana Cloud와 묶을 수 있지만, Next.js 통합 자료가 적다.

## R11. 테스트 전략

**결정**:
- API: 모듈 테스트(`@ApplicationModuleTest`)와 MockMvc 통합 테스트를 Testcontainers Postgres로 돌린다. OAuth 제공자는 `OAuthProviderClient` 인터페이스 뒤에 두고, 테스트에서는 가짜 구현을 쓴다.
- 계약: springdoc이 만든 OpenAPI와 `contracts/openapi.yaml`의 경로, 메서드, 응답 코드가 같은지 테스트로 비교한다(constitution II).
- 웹: 쿠키와 리다이렉트 로직은 Vitest로, 사용자 흐름은 Playwright로 검증한다.
- 전체 흐름 E2E: CI에 `e2e-full` job을 두고, Postgres와 API(`e2e` 프로필, 가짜 OAuth 제공자)를 컨테이너로 띄운 뒤 Playwright로 가입, 온보딩, 로그인, 로그아웃, 보호 화면 차단을 실제로 확인한다.

**근거**: 인증은 BFF 쿠키, API 세션, 라우트 가드가 함께 맞물려야 동작한다. 목(mock)만으로는 이 연결을 증명할 수 없다.
