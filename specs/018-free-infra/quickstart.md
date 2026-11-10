# Quickstart: 무료 인프라로 이전 (018-free-infra)

자동 테스트로 볼 수 없는 것(공급자의 실제 동작, 한 달 사용량, 이전 절차)을 손으로 확인하는 절차다. 결정 근거는 [research.md](research.md), 상태와 설정은 [data-model.md](data-model.md).

## 0. 준비 (저장소 소유자)

| 서비스 | 할 일 | 저장할 곳 |
|---|---|---|
| Neon | 프로젝트 `ogu`를 **AWS Singapore(ap-southeast-1)** 에 만든다. Postgres 17. DB `ogu`, 역할 `ogu`. 직접 연결 주소(`-pooler`가 없는 것)를 쓴다 | Render env `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. GitHub Secrets `NEON_DIRECT_URL`, `NEON_API_KEY`, `NEON_PROJECT_ID` |
| Render | 작업 공간 하나, 다른 무료 서비스 없이. `render.yaml` Blueprint로 서비스 `ogu-api`(Singapore, Free, 이미지) | GitHub Secrets `RENDER_DEPLOY_HOOK_URL`. GHCR 이미지가 비공개면 Render에 `read:packages` 토큰 |
| Cloudflare R2 | 버킷 `ogu-backup`, 수명 주기 규칙 "`postgres/` 14일 뒤 삭제" | GitHub Secrets `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` |
| cron-job.org | 작업 하나: `GET https://<render 주소>/actuator/health/liveness`, 10분마다, 실패와 복구 메일 켬 | - |
| Resend (메일을 켤 때만) | 보내는 도메인 인증 | Render env `MAIL_*` (Q6) |

Neon에서 먼저 `CREATE EXTENSION IF NOT EXISTS vector;`를 한 번 돌린다(복원 전에 확장이 있어야 한다).

## Q1. 512MB, CPU 0.1개에서 뜨는가 (R1, FR-010)

```bash
cd apps/api && ./gradlew bootJar
docker build -t ogu-api:q1 apps/api
docker compose -f infra/compose.e2e.yaml up -d postgres   # 로컬 DB
docker run --rm --cpus=0.1 --memory=512m --network <e2e 네트워크> \
  -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=... (CI 스모크 테스트와 같은 가짜 값) ogu-api:q1
```

- 기대: 3분 안에 `/actuator/health/liveness`가 UP. `docker stats`의 메모리가 기동 뒤 450MB 아래.
- 이어서 k6 50명 30분(`infra/k6/run-raid-load.sh`, VUS=50): 메모리 480MB 아래, OOM 없음, 공격 p95 500ms 안(SC-010). 값을 `research.md` R1 끝에 적는다.

## Q2. DB가 잠드는가 (US2-AC3, US4-AC3, SC-003)

리허설 환경(Render + Neon, 회원에게 안 보임)에서 한다.

1. 웹 없이 cron-job.org만 돌게 두고 1시간 기다린다.
2. Neon 콘솔의 컴퓨트 상태가 `Idle`(잠듦)인지, Monitoring의 활성 시간이 늘지 않았는지 본다.
3. 피드를 한 번 열고 10분 뒤 다시 본다. 다시 `Idle`이어야 한다.
4. 하루 둔 뒤 `GET /projects/{id}`의 `compute_time_seconds` ÷ 3600을 적는다. 회원 요청이 없는 하루의 값이 0.2 CU-시간(약 50분) 아래여야 한다.

## Q3. 한국에서 응답 시간 (US1-AC4, AC5, SC-004)

이전 전에 지금 운영의 값을 먼저 잰다. 같은 회원으로, 같은 곳에서.

```bash
for i in $(seq 1 40); do curl -s -o /dev/null -w '%{time_total}\n' -b cookies.txt https://new.5959.site/api/feed; sleep 2; done | sort -n
```

- 지금 운영, 리허설(웹 아이오와 + API 싱가포르), 리허설(웹 싱가포르 + API 싱가포르)을 각각 잰다. 95번째 값을 표로 남기고 웹 리전을 정한다(R9).
- DB가 잠든 뒤 첫 요청을 따로 잰다. 3초 안이어야 한다.

## Q4. 50분짜리 알림 연결이 끊기지 않는가 (R4)

리허설에서 브라우저 하나로 로그인해 둔 채 다른 회원으로 댓글을 단다. 연결이 열린 지 40분과 49분에 한 번씩. 둘 다 5초 안에 알림이 와야 한다. 중간에 끊기면 Render의 요청 시간 상한이 짧은 것이므로 `connection-lifetime`을 그 아래로 낮춘다.

## Q5. 배포가 겹칠 때 (R6, US3-AC5, AC6)

1. k6 20명이 레이드를 공격하는 중에 새 이미지로 배포 훅을 부른다.
2. 배포가 끝나고 2분 뒤 k6를 멈추고 `infra/k6/run-raid-load.sh`의 불변식 출력(받아들인 공격 = 줄어든 HP = 기여의 합)을 본다. 맞아야 한다.
3. 같은 때 알림 연결을 연 브라우저가 다시 붙었는지, 배포 중에 생긴 알림을 받았는지 본다.

## Q6. 메일 (R7, US5)

메일을 켤 때만 한다. Render env: `MAIL_ENABLED=true`, `MAIL_FROM`, `MAIL_SMTP_HOST=smtp.resend.com`, `MAIL_SMTP_PORT=2587`, `MAIL_SMTP_USERNAME=resend`, `MAIL_SMTP_PASSWORD=<API 키>`, `OGU_WEB_ORIGIN=https://new.5959.site`. 이메일 가입 회원으로 재설정을 요청해 1분 안에 받는지 본다. 받지 못하고 로그에 연결 시간 초과가 있으면 포트가 막힌 것이다. research R7의 대안(HTTP API 발송)을 이 기능의 남은 일로 연다. 나머지는 015 quickstart와 같다.

## Q7. 매일 점검과 백업 (US6, R10)

1. `ops-daily.yml`을 손으로 돌린다. R2에 오늘 덤프가 생기고, 로그에 CU-시간과 저장 용량이 찍힌다.
2. 임계값을 일부러 1%로 낮춰 돌린다. 워크플로가 실패하고 저장소 소유자에게 GitHub 메일이 온다(US2-AC4, US6-AC3). 되돌린다.
3. 복구 연습(`ops-daily.yml`의 restore-drill 입력)을 돌린다. 행 수가 출력되고 30분 안에 끝난다(SC-009). `infra/RESTORE.md`를 새 절차로 고친다.

## 전환 (US1, FR-001~004, SC-008)

시작 전에 Q1~Q5를 마쳤고, 리허설 Render가 운영 설정(점검 꺼짐)으로 떠 있다. 예상 1시간.

| 때 | 할 일 | 확인 |
|---|---|---|
| -1일 | 회원 공지: 점검 시간, 그동안 읽기만 된다 | - |
| 0:00 | VM의 API에 `OGU_MAINTENANCE_READ_ONLY=true`를 넣고 다시 띄운다(`deploy-api.sh`) | 글쓰기가 점검 안내를 받는다 |
| 0:05 | 테이블마다 행 수를 적는다(`infra/gcp/scripts`의 행 수 스크립트). VM에서 `pg_dump -Fc` | 덤프 파일 크기 |
| 0:08 | Render 서비스를 일시 중지(Suspend)하고, Neon의 리허설 DB를 지우고 다시 만든다(`DROP DATABASE ogu; CREATE DATABASE ogu OWNER ogu;`, 그 DB에서 `CREATE EXTENSION vector;`) | 빈 DB |
| 0:10 | Neon에 `pg_restore --no-owner --no-acl -d $NEON_DIRECT_URL` | 오류는 소유권 경고뿐 |
| 0:20 | Neon에서 같은 행 수를 적어 견준다 | 모두 같다(US1-AC3) |
| 0:25 | Render 서비스를 다시 켜고(Resume) 배포 훅으로 운영 이미지를 띄운다(Flyway가 V16을 적용) | `/actuator/health` UP |
| 0:30 | 웹 Cloud Run의 `API_ORIGIN`, `SSE_PUBLIC_ORIGIN`을 Render 주소로(Terraform `web.tf`) | 새 리비전 |
| 0:35 | 로그인(이메일, 구글), 피드, 글쓰기, 댓글 알림, 레이드 공격, 리포트 화면 | US1-AC1, AC2 |
| 0:45 | 문제가 없으면 회원 공지 내림. 문제가 있으면 웹의 주소를 VM으로 되돌리고 VM의 점검을 끈다 | - |

전환 뒤 7일 동안 매일 Neon 사용량, Render 메모리, 백업 성공, 알림 도착을 본다. 되돌리기는 research R12.

## 정리 (US7)

7일 동안 문제가 없으면:
1. VM에서 마지막 덤프를 받아 R2의 `ogu-backup/final/`에 둔다(수명 주기 규칙 밖).
2. Terraform에서 `google_compute_instance.api`, `google_compute_disk.data`, `google_compute_address.api`와 그것만 쓰는 방화벽, 서비스 계정, 비밀을 지운다. `prevent_destroy`와 `deletion_protection`을 먼저 푼다. **지우기는 저장소 소유자가 승인한 뒤에 `terraform apply`한다.**
3. Artifact Registry 정리 정책을 확인한다(웹 이미지 최근 3개).
4. 다음 달 청구서가 0원인지 본다(SC-001).
