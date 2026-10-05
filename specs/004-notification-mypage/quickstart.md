# Quickstart: 알림과 마이페이지 검증 (004-notification-mypage)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/notification-mypage.openapi.yaml](contracts/notification-mypage.openapi.yaml)(구현 후에는 저장소 루트 `contracts/openapi.yaml`), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다.

## 준비

```bash
# API: e2e 프로필은 가짜 감정 분석기를 쓴다(003 research R3). bootRun이 compose.yaml로 Postgres와 Redis를 함께 띄운다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e OGU_SSE_ALLOWED_ORIGINS=http://localhost:3000 ./gradlew bootRun

# 웹. SSE_PUBLIC_ORIGIN은 브라우저가 스트림에 바로 붙을 API 주소다(없으면 API_ORIGIN)
API_ORIGIN=http://localhost:8080 SSE_PUBLIC_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key \
  APP_ORIGIN=http://localhost:3000 pnpm --filter web dev
```

로컬에서 스트림만 따로 보고 싶으면 BFF 없이 API를 직접 부른다. `$AT`는 온보딩을 마친 회원의 access 토큰이다.

```bash
TICKET=$(curl -s -X POST localhost:8080/api/v1/notifications/stream-tickets \
  -H "Authorization: Bearer $AT" | jq -r .data.ticket)
curl -N "localhost:8080/api/v1/notifications/stream?ticket=$TICKET"            # 처음 연결
curl -N "localhost:8080/api/v1/notifications/stream?ticket=$TICKET2&lastEventId=3"  # 3번 이후 다시 받기
```

같은 티켓으로 두 번째 `curl`을 하면 `401 STREAM_TICKET_INVALID`다. 25초마다 `: hb` 줄이 보이면 하트비트가 도는 것이다.

## 자동 검증

```bash
cd apps/api && ./gradlew test            # 모듈, 통합(SSE 포함), 계약, 동시성, 두 인스턴스 테스트
pnpm --filter web test                   # 재연결 간격, 캐시 반영, 배지, 알림 문구, 통계
pnpm --filter web test:e2e:full          # 두 브라우저 컨텍스트로 실시간 알림과 재연결 (CI의 e2e-full)
grep -rn "US1-AC6" apps/                 # 인수 조건에서 테스트 찾기
```

## 수동 검증 시나리오 (로컬)

회원 A, B, C 세 명을 가입과 온보딩까지 마친 상태에서 시작한다. A는 창 두 개(일반 창과 시크릿 창이 아니라 같은 프로필의 탭 두 개)를 열어 둔다. B와 C는 각각 다른 브라우저 프로필을 쓴다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | A가 `[불안:낮음] 내일 발표가 걱정돼요` 작성 후 탭 두 개에서 홈을 열어 둠 | 몇 초 안에 두 탭 모두 "몬스터가 나타났어요" 토스트, 배지 1 | US1-AC3, US1-AC7 |
| 2 | B가 A의 글에 댓글 | 새로고침 없이 A의 두 탭에 "B 님이 내 글에 댓글을 남겼어요", 배지 2 | US1-AC1, US1-AC7 |
| 3 | A가 자기 글에 댓글, B의 댓글에 답글 | A에게는 알림 없음. B에게 "A 님이 내 댓글에 답글을 남겼어요" 하나 | US1-AC5 |
| 4 | C가 B의 댓글에 답글 | A에게 "내 글에 답글", B에게 "내 댓글에 답글"이 각각 하나 | US1-AC1 |
| 5 | B가 공감, 이어서 C가 공감 | A의 목록에 공감 알림 하나만 맨 위. "C 님 외 1명이 공감했어요" | US1-AC2 |
| 6 | B가 공감 취소 후 다시 공감 | A에게 새 알림도 숫자 변화도 없음 | US1-AC2 |
| 7 | 다른 회원 D가 공감, 댓글로 HP를 0으로 만듦(003 quickstart 8번처럼 HP 10 글이면 공감과 댓글 몇 명 필요) | A에게 "내 몬스터가 처치됐어요", HP를 줄인 B, C, D에게 "함께 공격한 몬스터가 처치됐어요"가 한 번씩 | US1-AC4 |
| 8 | A의 탭 하나에서 개발자 도구로 오프라인 전환, B가 A의 다른 글에 댓글 2개와 공감, 다시 온라인 | 다시 붙은 뒤 끊긴 동안의 알림이 순서대로, 한 번씩 들어옴. 공감 묶음은 마지막 상태 하나 | US1-AC6 |
| 9 | 로그아웃 상태에서 `/notifications` 접근, 그리고 `curl`로 티켓 없이 또는 지난 티켓으로 스트림 요청 | 로그인 화면으로 이동. 스트림은 401 | US1-AC8 |
| 10 | A가 알림을 25개 이상 만든 뒤 `/notifications` 열기 | 최신 20개, 각 항목에 종류, 닉네임, 글 앞부분, 시각, 읽음 표시 | US2-AC1 |
| 11 | 목록 끝까지 스크롤 | 나머지가 이어 붙고 같은 알림이 두 번 보이지 않음 | US2-AC2 |
| 12 | 안 읽은 알림 하나를 누름 | 글 상세로 이동, 배지가 하나 줆, 돌아오면 읽음 표시 | US2-AC3 |
| 13 | "모두 읽음"을 누르는 순간 B가 공감(개발자 도구로 요청을 느리게 하면 쉽다) | 누르기 전까지의 알림은 읽음, 그 뒤 온 공감은 안 읽은 채 배지 1 | US2-AC4 |
| 14 | A가 알림이 걸린 글을 지운 뒤 그 알림을 누름 | 목록에 "삭제된 글"로 남고, 누르면 "삭제된 글이에요" 안내 | US2-AC5 |
| 15 | B의 토큰으로 A의 알림 ID에 `PUT /api/v1/notifications/{id}/read` | 404 `NOTIFICATION_NOT_FOUND`, A의 알림은 그대로 안 읽음 | US2-AC6 |
| 16 | A가 `/my?tab=posts` | 지운 글을 뺀 내 글이 최신순, 감정, 몬스터 HP, 공감 수, 댓글 수, 시각 | US3-AC1 |
| 17 | A가 `/my?tab=comments`, 한 항목을 누름 | 내 댓글과 답글이 최신순, 지운 댓글과 지운 글의 댓글은 없음. 누르면 그 글로 이동 | US3-AC2 |
| 18 | B가 `/my?tab=likes` | 공감한 순서의 최신순, 6번에서 취소 후 다시 공감한 글은 다시 공감한 시각 자리. 지운 글은 없음 | US3-AC3 |
| 19 | 새로 가입한 회원 E가 세 탭을 엶 | 각 탭에 빈 안내와 "글쓰기", "피드 보기" 버튼 | US3-AC4 |
| 20 | A가 `/my`의 감정 통계 확인 | 전체와 처치된 수, 감정 5종의 수와 비율(합 100%) | US4-AC1 |
| 21 | 불안 2개, 짜증 2개(짜증이 더 최근)인 회원으로 통계 확인 | "가장 많이 나타난 몬스터"가 짜증 | US4-AC2 |
| 22 | 시드 스크립트로 글 작성 시각을 지난 8주에 흩뿌린 회원의 추이 확인 | 8개 막대, 한국 시간 월요일 시작, 글 없는 주는 0 | US4-AC3 |
| 23 | E가 통계 확인 | 숫자 0, "아직 몬스터가 없어요"와 글쓰기 안내 | US4-AC4 |
| 24 | 7번 뒤 B의 통계 확인 | "함께 물리친 몬스터" 1 | US4-AC5 |
| 25 | A가 `/my/edit`에서 닉네임, 직군, 경력을 바꿔 저장 | 저장되고 마이페이지에 바로 보임 | US5-AC1 |
| 26 | A가 B의 닉네임을 대소문자만 바꿔 입력, 이어서 `A!@` 입력 | 각각 M1 온보딩과 같은 "이미 사용 중인 닉네임" 안내와 형식 안내, 저장 안 됨 | US5-AC2 |
| 27 | 25번 뒤 A의 예전 글과 새 글을 피드에서 비교 | 예전 글은 예전 직군과 경력, 새 글은 바뀐 값 | US5-AC3 |
| 28 | 25번 뒤 예전 글, 댓글, B가 받은 A 관련 알림 확인 | 모두 바뀐 닉네임 | US5-AC4 |

## 동시성과 재전송 확인

```bash
cd apps/api && ./gradlew test --tests "*NotificationConcurrencyTest*" --rerun-tasks
cd apps/api && ./gradlew test --tests "*NotificationStreamReplayTest*" --rerun-tasks
cd apps/api && ./gradlew test --tests "*TwoInstanceStreamTest*" --rerun-tasks
```

- 서로 다른 회원 100명이 같은 글에 동시에 공감하면 안 읽은 공감 묶음이 하나이고 `actor_count`가 100이다(SC-005). 회원의 `seq`는 겹치지 않고 커지는 순서로 커밋된다.
- 연결을 끊은 동안 알림 50건(공감 묶음 갱신 포함)을 만들고 마지막 번호로 다시 붙으면, 받은 이벤트의 알림 ID 집합이 DB의 그 구간과 같고 같은 번호가 두 번 오지 않는다(SC-002).
- 컨텍스트 두 개(A, B)를 같은 DB와 Redis에 띄우고 A에 붙은 채 B에서 알림을 만들면 A로 온다. A를 닫고 B에 마지막 번호로 붙으면 그 사이 알림이 빠짐없이 온다(SC-003).

Redis 장애는 `./gradlew test --tests "*RedisOutageStreamTest*"`로 본다. 수동으로는 웹에서 A로 접속한 채 `docker compose stop redis`를 하고 B로 공감한다. 공감은 성공하고, A의 화면에는 늦어도 5초(줄어든 안전망 주기) 안에 알림이 뜬다. `docker compose start redis` 뒤에는 다시 3초 안에 온다.

수동으로 재시작을 확인하려면 API를 띄운 채 웹에서 A로 접속하고, API를 멈춘 사이 DB에 직접 알림이 생기도록 B로 공감한 뒤(API가 내려가 있으면 공감도 안 되므로, 두 번째 API를 `--server.port=8081`로 띄워 B가 거기로 공감) 첫 API를 다시 띄운다. A의 화면은 재시도 뒤 붙으면서 그 알림을 한 번 받는다.

## 성능 측정 (SC-001, SC-004)

- SC-001: e2e-full의 알림 시나리오에서 행동 요청의 응답 시각과 토스트가 뜬 시각의 차이를 20번 재서 p95를 남긴다. 목표는 3초 이하다.
- SC-004: 회원 하나에 글 1천 개, 알림 1만 개를 시드로 넣고 알림 목록 첫 쪽과 다음 쪽, 마이페이지 세 목록, 감정 통계를 각각 100번 불러 p95를 남긴다. 목표는 1초 이하다. 측정 방법과 기록 형식은 003 quickstart의 "성능 측정"을 따른다.

## 운영 준비 (저장소 소유자)

1. **Redis 컨테이너를 compose에 더한다**(research R5). `infra/compose.prod.yaml`에 아래 서비스를 넣고, `api`에 `depends_on: redis: condition: service_healthy`와 `REDIS_URL`을 더한다. `compose.e2e.yaml`과 로컬 `apps/api/compose.yaml`에도 같은 서비스를 비밀번호 없이 넣는다.

   ```yaml
   redis:
     image: redis:7.4-alpine
     restart: unless-stopped
     command: >
       redis-server --requirepass ${REDIS_PASSWORD}
       --maxmemory 64mb --maxmemory-policy noeviction
       --save "" --appendonly no
     mem_limit: 128m
     healthcheck:
       test: ["CMD-SHELL", "redis-cli -a $${REDIS_PASSWORD} --no-auth-warning ping | grep -q PONG"]
       interval: 5s
       retries: 10
   ```

   - **저장하지 않는다**(`--save ""`, `--appendonly no`). M3에서 Redis는 pub/sub 신호만 나르고 키를 하나도 저장하지 않는다. 신호는 원래 한 번 전달되면 끝이고, 내용과 순서는 Postgres에 있어 Redis를 비운 채 다시 띄워도 잃는 것이 없다. RDB나 AOF는 디스크 쓰기와 볼륨만 늘린다. M5에서 보스 HP를 담을 때 다시 정하는데, overview 5.4대로 HP는 Postgres 스냅숏과 로그로 복구하므로 그때도 저장 없이 갈 수 있는지 먼저 본다.
   - **메모리**: `maxmemory 64mb`와 `noeviction`, 컨테이너 상한 128MB. pub/sub는 키를 쓰지 않아 실제 사용량은 수 MB이고, 느린 구독자의 출력 버퍼는 기본 한도(`client-output-buffer-limit pubsub 32mb 8mb 60`)가 끊는다.
   - **포트는 열지 않는다.** compose 내부 네트워크에서 `api`만 붙는다. 그래도 같은 VM의 다른 컨테이너를 생각해 비밀번호를 건다.
   - 월 비용은 그대로 0원이다(SC-006).
2. VM `/opt/ogu/.env`와 `compose.prod.yaml`의 `api.environment`에 새 값을 넣는다.
   - `REDIS_PASSWORD`: 32자 이상 난수. `.env`에만 둔다.
   - `REDIS_URL`: `redis://:${REDIS_PASSWORD}@redis:6379`(compose가 조합한다). `spring.data.redis.url`로 들어간다. `prod`에서 비어 있으면 기동이 실패한다.
   - `OGU_SSE_ALLOWED_ORIGINS`: 웹 출처(예: `https://5959.vercel.app`). 비어 있으면 `prod` 기동이 실패한다(`ProdAuthSettingsCheck`에 검사 추가).
3. Vercel 프로젝트 환경 변수에 `SSE_PUBLIC_ORIGIN`(예: `https://api.도메인`)을 넣는다. `API_ORIGIN`과 같다면 생략해도 된다.
4. `infra/Caddyfile`에서 스트림 경로를 압축에서 뺀다. Caddy는 `text/event-stream` 응답을 바로 흘려보내고, 응답 타임아웃 기본값이 없어 따로 늘리지 않는다.

   ```caddy
   {$API_DOMAIN} {
   	@compressible not path /api/v1/notifications/stream
   	encode @compressible zstd gzip
   	reverse_proxy api:8080
   }
   ```

5. 헬스 체크: Redis가 내려가도 알림은 저장되므로 `/actuator/health`(배포 스크립트의 롤백 기준)에는 Redis를 넣지 않는다(기본 그룹이 Redis 지표를 빼도록 설정하고, Redis 지표는 따로 조회한다). Redis 상태는 compose 헬스 체크와 `/actuator/health/redis`, `docker compose ps`로 본다.
6. 배포 뒤 확인: 브라우저에서 로그인하고 개발자 도구의 네트워크 탭에서 `stream` 요청이 `200 text/event-stream`으로 열려 있고, 25초마다 데이터가 조금씩 오는지 본다. 다른 계정으로 공감해 3초 안에 토스트가 뜨는지 본다.
7. 기존 데이터에는 이관이 없다. `V4__notification_mypage.sql`은 테이블과 인덱스만 만든다. 큰 테이블에 인덱스를 만들지만 지금 데이터 규모(글 수천 개)에서는 잠금 시간이 짧다.
