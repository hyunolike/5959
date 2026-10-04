# BFF 라우트 계약 (apps/web)

브라우저가 부르는 같은 출처 주소다. API 계약(`openapi.yaml`)과 쿠키 규칙(research R3)을 브라우저 쪽에서 본 모습이다. 상태를 바꾸는 요청은 `Origin`이 서비스 출처와 다르면 `403`이다.

## 인증 라우트

| 메서드, 경로 | 요청 | 성공 | 실패 | 쿠키 변화 |
|---|---|---|---|---|
| `POST /api/auth/signup` | `{ email, password }` | `201 { member }` | API 오류를 그대로 전달(`400`, `409`) | `ogu_at`, `ogu_rt` 설정, `ogu_ob` 삭제 |
| `POST /api/auth/login` | `{ email, password }` | `200 { member }` | `401`, `429`(`retryAfterSeconds` 포함) | `ogu_at`, `ogu_rt` 설정, 온보딩했으면 `ogu_ob` 설정 |
| `POST /api/auth/logout` | 없음 | `204` | 없음(API가 실패해도 쿠키는 지운다) | 세 쿠키 모두 삭제 |
| `GET /api/auth/oauth/{provider}?next=` | 없음 | `302` 제공자 인가 URL | 지원하지 않는 제공자면 `302 /login` | `ogu_oauth`(state, code_verifier, next) 10분 설정 |
| `GET /api/auth/oauth/{provider}/callback?code&state` | 제공자가 호출 | `302` 온보딩 또는 `next` | state 불일치, 동의 취소, 제공자 오류: `302 /login?error=...` | `ogu_at`, `ogu_rt`(,`ogu_ob`) 설정, `ogu_oauth` 삭제 |
| `PUT /api/auth/onboarding` | `{ nickname, jobRole, careerYear }` | `200 { member }` | `400`, `409` | `ogu_at` 교체, `ogu_ob` 설정 |

- 응답 본문에는 토큰이 절대 들어가지 않는다. 쿠키로만 전달한다.
- 쿠키 이름은 실제로 `__Host-` 접두사가 붙는다(`__Host-ogu_at` 등).
- `/login?error=` 값: `oauth_cancelled`(US3-AC4), `oauth_failed`, `email_registered`(US3-AC3).

## 범용 프록시 `/api/[...path]`

- `ogu_at`을 `Authorization: Bearer`로 바꿔 `API_ORIGIN/api/v1/...`로 전달한다. `X-Ogu-Bff-Key`와 `X-Ogu-Client-Ip`를 붙인다.
- API가 세션 오류 `401`(`UNAUTHORIZED`, `SESSION_EXPIRED`)이면 `ogu_rt`로 `POST /api/v1/auth/refresh`를 한 번 부르고 원래 요청을 다시 보낸다. 다른 `401` 코드는 쿠키를 건드리지 않고 그대로 전달한다. 응답의 `refreshToken`이 `null`(유예 구간)이면 `ogu_rt`를 건드리지 않는다. 갱신한 회원의 `onboarded`에 맞춰 `ogu_ob`를 설정하거나 지운다. refresh가 거절되거나(4xx) 갱신한 토큰으로도 `401`이면 세 쿠키를 지우고 `401 SESSION_EXPIRED`를 돌려준다. refresh가 API 장애(5xx, 연결 실패)로 실패하면 쿠키를 지우지 않고 그 오류를 그대로 돌려준다.
- 요청 하나에서 refresh는 많아야 한 번이다(갱신 반복 방지). `PUT /api/auth/onboarding`도 같은 규칙(`shared/server/session-refresh.ts`의 `callWithSessionRefresh`)을 따른다. `POST /api/auth/logout`은 `ogu_at`이 없고 `ogu_rt`만 있으면 먼저 refresh해 서버 세션을 무효로 만들고, 결과와 관계없이 세 쿠키를 지우고 `204`다. 앱의 조회가 `401`로 끝나면 브라우저는 지금 화면을 `next`로 들고 `/login`으로 간다.
- `ogu_at`이 만료돼 없어도 `ogu_rt`가 있으면 먼저 refresh한 뒤 요청한다.

### 캐치올이 절대 그대로 넘기지 않는 경로

API 응답 본문에는 `accessToken`, `refreshToken`이 그대로 들어 있을 수 있다
(`AuthResult`, `OnboardingResult`). 캐치올이 이런 응답을 그대로 넘기면
토큰이 브라우저 스크립트에 노출된다(FR-012 위반). 그래서 캐치올은 아래
경로를 절대 API로 넘기지 않고 `404 NOT_FOUND`로 막는다 — 이 경로들은 위
"인증 라우트" 표처럼 토큰을 쿠키로 바꾸고 본문에서 지우는 전용 라우트에서만
다룬다.

| 막는 경로 | 비고 |
|---|---|
| 첫 세그먼트가 `auth`인 모든 경로(`/api/auth/**`) | 로그인, 가입, refresh, OAuth 시작·콜백 |
| `/api/members/me/onboarding` | 온보딩 완료(`PUT`). 온보딩 완료 응답(`OnboardingResult`)에 새 `accessToken`이 들어 있다 |

캐치올은 각 경로 세그먼트도 검증한다. 비어 있거나 `.`, `..`인 세그먼트,
`/`, `\`, `?`, `#`을 포함한 세그먼트는 막는다(그 세그먼트를 한 번 더
퍼센트 디코딩했을 때 이런 값이 나오는 경우도 막는다 — Next.js가 세그먼트를
나눈 뒤 디코딩하므로 `auth%2Flogin`처럼 이중 인코딩된 슬래시가 세그먼트
안에 리터럴로 남을 수 있다). 통과한 세그먼트는 `encodeURIComponent`로 다시
인코딩해 붙이고, 최종 경로가 `/api/v1/`로 시작하는지 한 번 더 확인한 뒤에만
호출한다. `..`이나 인코딩된 슬래시로 `/actuator/health` 같은 다른 경로를
부르는 시도를 막기 위해서다.

## BFF 전용 오류 코드

apps/web(BFF)이 apps/api를 거치지 않고 직접 만드는 오류 코드다. `ApiResponse`
오류 봉투(`{ success:false, data:null, error:{ code, message } }`)로 나간다.

| 코드 | 상태 | 언제, 어디서 |
|---|---|---|
| `FORBIDDEN_ORIGIN` | 403 | `origin-guard.ts`. 상태를 바꾸는 요청의 `Origin`이 `APP_ORIGIN`과 다르거나 없을 때. apps/api의 `ErrorCode.FORBIDDEN_ORIGIN`과 이름은 같지만 독립적으로 만든다 |
| `NOT_FOUND` | 404 | `/api/[...path]`. 위 "캐치올이 절대 그대로 넘기지 않는 경로"를 막거나 세그먼트 검증에 실패했을 때 |
| `API_UNAVAILABLE` | 502 | `api-client.ts`(`callApi`). `API_ORIGIN` 호출이 네트워크 오류로 실패했을 때 |
| `API_UNAVAILABLE` | 504 | `api-client.ts`(`callApi`). `API_ORIGIN` 호출이 15초 안에 끝나지 않았을 때(타임아웃) |

apps/api가 JSON이 아닌 본문을 돌려주면(예상 밖의 5xx 오류 페이지 등),
`callApi`는 원래 상태 코드는 유지한 채 5xx는 apps/api `ErrorCode.INTERNAL_ERROR`,
그 외 4xx는 `ErrorCode.INVALID_REQUEST`와 같은 코드·메시지로 감싼다. `204`나
빈 본문 응답은 상태만 그대로 돌려주고 본문은 만들지 않는다.

## 라우트 가드 (`proxy.ts`)

| 경로 | 조건 | 동작 |
|---|---|---|
| `/` | `ogu_rt` 있음 | `302 /home` |
| 보호 경로(`/home`, `/write`, `/post`, `/my`, `/settings` 이하) | `ogu_rt` 없음 | `302 /login?next=<원래 경로>` |
| 같은 경로 | `ogu_rt` 있고 `ogu_ob` 없음 | `302 /onboarding` |
| `/onboarding` | `ogu_rt` 없음 | `302 /login` |
| `/onboarding` | `ogu_ob` 있음 | `302 /home`(검증한 `next`가 있으면 그곳) |
| `/login`, `/signup` | `ogu_rt`, `ogu_ob` 모두 있음 | `302 /home`(검증한 `next`가 있으면 그곳) |

보호 경로 목록은 `shared/server/route-guard.ts`의 `PROTECTED_PATH_PREFIXES`에 있다. 003-core-loop에서 `/write`(글쓰기)와 `/post`(글 상세 `/post/{id}`, 글 수정 `/post/{id}/edit`)가 들어왔다. 글 화면을 부르는 API는 온보딩을 마친 회원에게만 열려 있어서, 화면이 401이나 403을 받기 전에 가드가 로그인이나 온보딩으로 보낸다.

`next`는 `/`로 시작하고 `//`나 `/\`로 시작하지 않을 때만 따른다. 한 번 퍼센트 디코딩한 값(`/%2F%2Fevil`, `/%5Cevil`)과 제어 문자도 같은 규칙으로 막는다. 아니면 `/home`으로 보낸다(스펙 경계 상황). 검증은 `shared/lib/next-path.ts` 한 곳에서 한다. 로그인, 가입, OAuth 성공 뒤에는 이 `next`로 가고, 온보딩이 남았으면 `/onboarding?next=`로 넘겨 온보딩 뒤 그곳으로 간다. `/login?error=` 리다이렉트에도 검증한 `next`를 남긴다.

## 서버 전용 환경변수

| 이름 | 용도 |
|---|---|
| `API_ORIGIN` | API 주소(M0) |
| `BFF_API_KEY` | `X-Ogu-Bff-Key` 값. API의 `OGU_BFF_KEY`와 같아야 한다 |
| `APP_ORIGIN` | Origin 검사와 OAuth `redirect_uri`의 기준 주소 |
| `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID` | 인가 URL 생성용(공개 값). 시크릿은 API만 가진다 |
| `OAUTH_STATE_SECRET` | `__Host-ogu_oauth` 쿠키 HMAC 서명 키(32자 이상). Vercel 배포(`VERCEL_ENV`가 있으면 미리보기 포함)와 `APP_ENV=production`에서는 필수 |
| `APP_ENV` | `development`, `e2e`, `production`. `e2e`면 OAuth 시작 라우트가 제공자 대신 자기 콜백으로 바로 보낸다. `VERCEL_ENV=production`과 함께 쓰면 env 검증이 빌드와 기동을 막는다 |
| `NEXT_PUBLIC_SENTRY_DSN` | 설정했을 때만 오류 수집을 켠다 |
