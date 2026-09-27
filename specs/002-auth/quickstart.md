# Quickstart: 인증과 회원 검증 (002-auth)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/openapi.yaml](contracts/openapi.yaml)과 [contracts/bff-routes.md](contracts/bff-routes.md), 데이터는 [data-model.md](data-model.md)를 본다.

## 준비

```bash
# API: e2e 프로필은 가짜 OAuth 제공자를 쓴다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e OGU_BFF_KEY=local-bff-key ./gradlew bootRun

# 웹
API_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key APP_ORIGIN=http://localhost:3000 \
  pnpm --filter web dev
```

## 자동 검증

```bash
cd apps/api && ./gradlew test                      # 모듈, 통합, 계약 테스트
pnpm --filter web test                             # 쿠키, 가드, 프록시 단위 테스트
pnpm --filter web test:e2e                         # BFF만으로 되는 화면 흐름
pnpm --filter web test:e2e:full                    # API까지 띄운 전체 흐름 (CI의 e2e-full job)
grep -rn "US2-AC3" apps/                           # 인수 조건에서 테스트 찾기
```

## 수동 검증 시나리오 (로컬)

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | `/signup`에서 새 이메일, `abcd1234`로 가입 | `/onboarding`으로 이동 | US1-AC1 |
| 2 | 온보딩에서 닉네임 `오구 1` 입력 | 허용 문자 안내, 저장 안 됨 | US1-AC6 |
| 3 | 닉네임 `오구1`, 직군, 경력 입력 후 완료 | `/home` 도착 | US1-AC4 |
| 4 | 개발자 도구 콘솔에서 `document.cookie` | `ogu_` 쿠키가 보이지 않음 | US4-AC6 |
| 5 | 로그아웃 후 `/my` 주소로 바로 이동 | `/login?next=/my`, 로그인하면 `/my` 도착 | US4-AC4, US4-AC5 |
| 6 | 틀린 비밀번호로 5번 로그인 | 6번째는 올바른 비밀번호여도 15분 대기 안내 | US2-AC3 |
| 7 | e2e 프로필에서 `/api/auth/oauth/kakao` | 가짜 제공자를 거쳐 `/onboarding` | US3-AC1 |

## 운영 환경 준비 (저장소 소유자)

1. 카카오 개발자 콘솔에서 앱을 만들고 Redirect URI `https://<웹 도메인>/api/auth/oauth/kakao/callback`을 등록한다. 동의 항목에서 이메일은 선택 동의로 둔다.
2. 구글 Cloud Console에서 OAuth 클라이언트(웹)를 만들고 같은 형식의 Redirect URI를 등록한다.
3. 카카오의 PKCE 지원 여부를 확인하고 결과를 research.md R4에 적는다.
4. Sentry 무료 프로젝트(Next.js)를 만들고 DSN을 발급한다.
5. 값을 넣는다.
   - VM `/opt/ogu/.env`: `OGU_BFF_KEY`, `JWT_SECRET`(`openssl rand -base64 48`), `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `OAUTH_ALLOWED_REDIRECT_URIS`
   - Vercel: `BFF_API_KEY`(VM의 `OGU_BFF_KEY`와 같은 값), `APP_ORIGIN`, `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_SENTRY_DSN`
6. **US5-AC1 수동 검증**: Vercel에 `ENABLE_ERROR_PROBE=1`을 잠시 넣고 재배포한 뒤, 운영 URL의 `/debug/error-probe`에서 확인용 오류를 낸다. 5분 안에 Sentry에 나타나는지 보고, 끝나면 변수를 지우고 재배포한다(변수가 없으면 이 경로는 404다).
7. **US5-AC2 수동 검증**: 그 오류 기록의 요청 정보에 쿠키, `Authorization`, `password`가 없는지 본다.
