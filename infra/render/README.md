# Render와 Neon 운영 (018)

API는 Render 무료 웹 서비스 한 대(싱가포르, 메모리 512MB, CPU 0.1개)에서, DB는 Neon 무료(싱가포르)에서 돈다. 웹은 Vercel 프로젝트 `ogu-web`이다(2026-11-11부터. 그 전에는 Cloud Run). 웹이 부르는 API 주소는 Vercel 운영 환경 변수 `API_ORIGIN`이다. 설계와 측정값은 [018 research](../../specs/018-free-infra/research.md)에 있다.

## 1. 계정 준비

| 서비스 | 할 일 | 값을 넣을 곳 |
|---|---|---|
| Neon | 프로젝트 `ogu`를 AWS Singapore(ap-southeast-1)에 만든다. Postgres 17, DB `ogu`, 역할 `ogu`. 처음 한 번 `CREATE EXTENSION IF NOT EXISTS vector;`를 돌린다 | Render env `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| Render | 작업 공간 하나에 [`render.yaml`](../../render.yaml) Blueprint로 서비스 `ogu-api`를 만든다. 리전은 만든 뒤 바꿀 수 없다 | GitHub Secrets `RENDER_API_KEY`, `RENDER_SERVICE_ID` |
| cron-job.org | 작업 하나(아래 3절) | - |
| Resend(메일을 켤 때만) | 보내는 도메인을 인증한다 | Render env `MAIL_FROM`, `MAIL_SMTP_PASSWORD` |

### Neon 연결 주소

Neon 콘솔의 Connect에서 **Pooled connection을 끄고** 나오는 직접 연결 주소(호스트에 `-pooler`가 없는 것)를 쓴다. 연결 풀은 API의 Hikari가 맡는다(research R2).

```
DB_URL=jdbc:postgresql://<호스트>/ogu?sslmode=require
DB_USERNAME=ogu
DB_PASSWORD=<Neon이 준 비밀번호>
```

### Render 환경 변수

`render.yaml`에서 `sync: false`인 값은 Blueprint를 처음 만들 때 대시보드가 묻는다. 나중에 바꾸려면 서비스의 Environment에서 고치고 저장한다. 저장하면 다시 배포된다.

- `OGU_WEB_ORIGIN`, `OGU_SSE_ALLOWED_ORIGINS`: 웹 주소(`https://new.5959.site`).
- `OAUTH_ALLOWED_REDIRECT_URIS`: 웹의 카카오, 구글 콜백 주소. 웹 주소가 그대로라 바뀌지 않는다.
- `JWT_SECRET`, `OGU_BFF_KEY`, `GOOGLE_*`, `KAKAO_*`, `AI_*`: 옛 VM의 값을 그대로 옮긴다. `OGU_BFF_KEY`는 웹의 `BFF_API_KEY`와 같아야 한다. `JWT_SECRET`을 바꾸면 모든 회원이 다시 로그인한다.
- 리허설 동안은 `AI_COMPANION_ENABLED`, `AI_COMPANION_POSTING_ENABLED`, `MAIL_ENABLED`를 `false`로 둔다. 리허설 DB에 AI 친구의 글이 생기거나 AI 요금, 메일이 나가지 않게 하려는 것이다.

### GitHub Secrets

| 이름 | 값 |
|---|---|
| `RENDER_API_KEY` | Render의 Account Settings → API Keys에서 만든 키 |
| `RENDER_SERVICE_ID` | 서비스 주소의 `srv-...` |

## 2. 배포

Actions의 **Release** 워크플로를 손으로 실행한다([release.yml](../../.github/workflows/release.yml)).

1. release-please가 버전을 정한다.
2. [`scripts/build-image.sh`](scripts/build-image.sh)가 기동 캐시(AOT)를 담은 `linux/amd64` 이미지를 만든다. 캐시는 DB가 있어야 만들어져서 빈 Postgres를 잠깐 띄워 학습한다. 학습 값은 모두 가짜다.
3. 이미지를 GHCR(`ghcr.io/hyunolike/5959-api`, 공개)에 버전과 `latest`로 올린다.
4. Render API로 그 이미지의 digest를 지정해 배포를 걸고, 배포가 `live`가 될 때까지 최대 15분 기다린다.

손으로 이미지를 만들 때는 이렇게 한다. 맥(arm64)에서 `linux/amd64`를 만들면 에뮬레이션이라 10분 넘게 걸린다.

```bash
cd apps/api && ./gradlew bootJar && cd ../..
infra/render/scripts/build-image.sh ghcr.io/hyunolike/5959-api:<태그> linux/amd64
```

배포 동안 Render는 새 인스턴스가 뜰 때까지 옛 인스턴스로 요청을 받는다. 그래서 기동에 4분쯤 걸려도 회원에게 끊기지 않는다. 다만 두 인스턴스가 잠깐 함께 돌아, 그 사이 같은 회원이 양쪽에서 레이드를 공격하면 기여 일부가 빠질 수 있다(research R6).

## 3. 잠들지 않게 하기(cron-job.org)

Render 무료는 15분 동안 요청이 없으면 서비스를 재운다. 잠든 뒤 첫 요청은 깨어나기까지 약 5분을 기다린다(2026-11-12 측정 287초). 이것을 막으려고 밖에서 10분마다 부른다.

- 주소: `GET https://<render 주소>/actuator/health/liveness`
- 간격: 10분
- 알림: 실패와 복구 메일을 켠다(US2-AC5)

liveness는 DB를 보지 않는다(research R8). 그래서 이 요청은 서비스만 깨워 두고 Neon은 그대로 잠들 수 있다.

## 4. 무료 플랜에서 알아 둘 것

| 항목 | 값 | 근거 |
|---|---|---|
| 기동 | 약 4분(229~259초). 기동 캐시를 써도 줄지 않는다 | research R1 |
| 메모리 | 50명이 30분 동안 공격할 때 411~442MB(한도 512MB) | research R1 |
| 레이드 공격 응답 | 50명이 1초마다 쉬지 않고 공격하면 p95 2.6초. 수는 맞다 | research R1 |
| 종료 대기 | `maxShutdownDelaySeconds`를 받지 않는다. 기본 30초 안에 레이드 기록을 쓰고 끝낸다 | `render blueprints validate` |
| 나가는 메일 포트 | 25, 465, 587이 막혀 Resend의 2587을 쓴다 | research R7 |
| OTel 에이전트 | 켜지 않는다. CPU 0.1개에서는 기동이 크게 늘어난다 | research R1 |
