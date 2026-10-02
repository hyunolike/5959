# Quickstart: 인증과 회원 검증 (002-auth)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/openapi.yaml](contracts/openapi.yaml)과 [contracts/bff-routes.md](contracts/bff-routes.md), 데이터는 [data-model.md](data-model.md)를 본다.

## 준비

```bash
# API: e2e 프로필은 가짜 OAuth 제공자를 쓴다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e OGU_BFF_KEY=local-bff-key ./gradlew bootRun

# 웹: APP_ENV=e2e가 있어야 OAuth 시작 라우트가 실제 제공자 대신 가짜 제공자로 보낸다(시나리오 7)
API_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key APP_ORIGIN=http://localhost:3000 APP_ENV=e2e \
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

### T070 실행 결과 (2026-09-29)

로컬에서 `SPRING_PROFILES_ACTIVE=local,e2e OGU_BFF_KEY=local-bff-key`로 API를,
`API_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key APP_ORIGIN=http://localhost:3000 APP_ENV=e2e`로
웹을 띄우고, 브라우저 대신 웹 서버(`localhost:3000`)에 쿠키 저장 curl로 1~7번을 그대로 실행했다(실제
카카오·구글 제공자는 쓰지 않았다 — 7번은 `APP_ENV=e2e`의 가짜 제공자다). 4번(`document.cookie`)만 실제
브라우저 콘솔 대신 `Set-Cookie` 응답 헤더의 `HttpOnly` 플래그로 대신 확인했다(스크립트가 애초에 읽을 수
없는 쿠키라는 사실은 동일하게 보인다).

| # | 결과 |
|---|---|
| 1 | `POST /api/auth/signup` → `201`, `__Host-ogu_at`/`__Host-ogu_rt` 설정, 본문에 토큰 없음 |
| 2 | `PUT /api/auth/onboarding`에 `"오구 1"` → `400 INVALID_REQUEST`("닉네임은 한글, 영문, 숫자로 1~10자") |
| 3 | 같은 라우트에 `"오구1"` → `200`, `__Host-ogu_ob=1` 설정 |
| 4 | 세 쿠키 모두 `Set-Cookie`에 `HttpOnly` 플래그 있음 |
| 5 | `POST /api/auth/logout` → `204`, 세 쿠키 삭제 → `GET /my` → `307 /login?next=%2Fmy` → 로그인 → `GET /login?next=%2Fmy` → `307 /my` |
| 6 | 틀린 비밀번호 5회(`401`) → 6번째 올바른 비밀번호 → `429 LOGIN_THROTTLED`, `Retry-After: 900` |
| 7 | `GET /api/auth/oauth/kakao` → `302`(가짜 제공자 콜백 URL) → 콜백 → `302 /onboarding`, 세션 쿠키 설정 |

문서나 코드를 고칠 필요가 있었던 것: 없음(시나리오 자체는 문서 그대로 통과). 다만 준비 명령의 웹 실행에
`APP_ENV=e2e`가 빠져 있어 7번이 실제 카카오로 가 버렸다 — 위 "준비" 절의 명령을 고쳤다.

### 성능 측정 (T070, plan.md 성능 목표와 비교)

**측정 환경 유의사항**: 이 Mac은 측정 중 QEMU 가상머신이 백그라운드에서 돌고 있어 CPU가 여유롭지 않았다.
아래 수치는 이 기기의 상대적인 참고값이고, 절대치를 과신하면 안 된다(특히 p99와 max는 QEMU가 CPU를 뺏어간
순간의 지연을 그대로 반영한다). `apps/api`에 직접 curl로 측정했다(Next.js BFF의 개발 모드 컴파일 지연을
섞지 않기 위해 — plan.md의 목표도 API 쪽 수치다).

| 대상 | 횟수 | min | p50 | p95 | p99 | max | 목표(plan.md) | 결과 |
|---|---|---|---|---|---|---|---|---|
| `POST /api/v1/auth/login` | 100 | 128ms | 188ms | **419ms** | 645ms | 2061ms | p95 ≤ 500ms(bcrypt 약 100ms 포함) | 충족(여유 81ms) |
| `GET /api/v1/members/me`(세션 확인 포함, 인증) | 100 | 10ms | 28ms | **80ms** | 189ms | 365ms | — | — |
| `GET /actuator/health`(인증·세션 확인 없음) | 100 | 3ms | 9ms | **31ms** | 38ms | 40ms | — | — |
| 위 두 값의 p95 차이 | | | | **49ms** | | | 세션 확인 추가 비용 p95 ≤ 5ms | 정밀 측정 불가(아래 설명) |

`/actuator/health`는 인증, JWT 검증, `SessionCheckFilter`, DB 조회, JSON 직렬화를 전부 건너뛰는
가장 가벼운 공개 엔드포인트라서, `members/me`와의 p95 차이(49ms)는 "세션 확인 필터 하나의 비용"이 아니라
인증 경로 전체(Bearer 파싱, JWT 서명 검증, `SessionCheckFilter`의 세션 조회, 회원 조회, 응답 직렬화)를
합친 값이다. plan.md가 말하는 "세션 확인 추가 비용 p95 5ms 이하"를 정확히 재려면 세션 확인 필터만 뺀
동일 엔드포인트가 있어야 하는데 그런 비교 대상이 없어, 이 측정으로는 **그 목표의 충족 여부를 판정할 수
없다**. 다만 `SessionCheckFilter` 자체는 세션 PK로 인덱스 조회 한 번(`repository.findById`)만 하므로,
49ms 대부분은 JWT 서명 검증과 회원 조회·JSON 직렬화 쪽일 가능성이 높다는 정황만 남긴다. 세션 확인만
격리해서 재려면 인증은 통과시키되 DB 조회가 없는 더미 엔드포인트가 따로 있어야 하고, 이는 이번 배치
범위 밖이라 다음 성능 작업으로 남긴다.

## 운영 환경 준비 (저장소 소유자)

1. 카카오 개발자 콘솔에서 앱을 만들고 Redirect URI `https://<웹 도메인>/api/auth/oauth/kakao/callback`을 등록한다. 동의 항목에서 이메일은 선택 동의로 둔다.
2. 구글 Cloud Console에서 OAuth 클라이언트(웹)를 만들고 같은 형식의 Redirect URI를 등록한다.
3. 카카오의 PKCE 지원 여부를 확인하고 결과를 research.md R4에 적는다.
4. Sentry 무료 프로젝트(Next.js)를 만들고 DSN을 발급한다.
5. 값을 넣는다. 목록은 `ProdAuthSettingsCheck`(prod 프로필 기동 검증), `apps/web/src/shared/config/env.ts`, `infra/compose.prod.yaml`, `infra/.env.example`과 맞춘 것이다.
   - VM `/opt/ogu/.env`(`infra/.env.example` 참고): `OGU_BFF_KEY`(로컬 개발용 고정 값 `local-bff-key`와 같으면 `prod` 프로필이 기동을 거부한다), `JWT_SECRET`(`openssl rand -base64 48`, 로컬 개발용 고정 값이나 `infra/compose.e2e.yaml`의 e2e 고정 값과 같으면 `prod` 프로필이 기동을 거부한다), `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `OAUTH_ALLOWED_REDIRECT_URIS`(전부 `https://`— 하나라도 아니면 기동을 거부한다). `prod`와 `e2e` 프로필을 동시에 켤 수 없다.
   - Vercel(Production과 Preview 모두): `API_ORIGIN`(VM의 공개 API 주소, 예: `https://api.<도메인>`. `VERCEL_ENV`가 있는 모든 배포에서 명시적으로 넣지 않으면 빌드가 실패한다 — 빠졌다고 기본값인 `http://localhost:8080`으로 조용히 넘어가지 않는다), `BFF_API_KEY`(VM의 `OGU_BFF_KEY`와 같은 값. 마찬가지로 `VERCEL_ENV`가 있으면 명시적으로 넣어야 하고, 로컬 개발용 고정 값 `local-bff-key`면 빌드가 실패한다), `APP_ORIGIN`(역시 `VERCEL_ENV`가 있으면 필수), `KAKAO_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `OAUTH_STATE_SECRET`(`openssl rand -base64 48`. `VERCEL_ENV`가 있는 모든 배포 — Production과 Preview — 에서 없으면 Vercel 빌드가 실패한다), `NEXT_PUBLIC_SENTRY_DSN`. `APP_ENV`는 Vercel에 두지 않는다(기본값 `development`로 두면 실제 제공자로 로그인한다; `production`에서 `e2e`로 두면 빌드가 실패한다).
6. **US5-AC1 수동 검증**: Vercel에 `ENABLE_ERROR_PROBE=1`을 잠시 넣고 재배포한 뒤, 운영 URL의 `/debug/error-probe`에서 확인용 오류를 낸다. 5분 안에 Sentry에 나타나는지 보고, 끝나면 변수를 지우고 재배포한다(변수가 없으면 이 경로는 404다).
7. **US5-AC2 수동 검증**: 그 오류 기록의 요청 정보에 쿠키, `Authorization`, `password`가 없는지 본다.
