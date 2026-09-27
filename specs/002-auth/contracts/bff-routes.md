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
- API가 `401`이면 `ogu_rt`로 `POST /api/v1/auth/refresh`를 한 번 부르고 원래 요청을 다시 보낸다. 응답의 `refreshToken`이 `null`(유예 구간)이면 `ogu_rt`를 건드리지 않는다. refresh도 실패하면 세 쿠키를 지우고 `401`을 돌려준다.
- `ogu_at`이 만료돼 없어도 `ogu_rt`가 있으면 먼저 refresh한 뒤 요청한다.

## 라우트 가드 (`proxy.ts`)

| 경로 | 조건 | 동작 |
|---|---|---|
| `/` | `ogu_rt` 있음 | `302 /home` |
| `/home`, `/write`, `/my`, `/settings` 이하 | `ogu_rt` 없음 | `302 /login?next=<원래 경로>` |
| 같은 경로 | `ogu_rt` 있고 `ogu_ob` 없음 | `302 /onboarding` |
| `/onboarding` | `ogu_rt` 없음 | `302 /login` |
| `/onboarding` | `ogu_ob` 있음 | `302 /home` |
| `/login`, `/signup` | `ogu_rt`, `ogu_ob` 모두 있음 | `302 /home` |

`next`는 `/`로 시작하고 `//`나 `/\`로 시작하지 않을 때만 따른다. 아니면 `/home`으로 보낸다(스펙 경계 상황).

## 서버 전용 환경변수

| 이름 | 용도 |
|---|---|
| `API_ORIGIN` | API 주소(M0) |
| `BFF_API_KEY` | `X-Ogu-Bff-Key` 값. API의 `OGU_BFF_KEY`와 같아야 한다 |
| `APP_ORIGIN` | Origin 검사와 OAuth `redirect_uri`의 기준 주소 |
| `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID` | 인가 URL 생성용(공개 값). 시크릿은 API만 가진다 |
| `NEXT_PUBLIC_SENTRY_DSN` | 설정했을 때만 오류 수집을 켠다 |
