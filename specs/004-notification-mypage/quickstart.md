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
| 13 | "모두 읽음"을 누르는 순간 C가 A의 다른 글(8번의 글)에 처음 공감(이미 공감한 회원은 다시 알리지 않으므로 B나 D는 쓰지 않는다. 개발자 도구로 요청을 느리게 하면 쉽다) | 누르기 전까지의 알림은 읽음, 그 뒤 온 공감은 안 읽은 채 배지 1 | US2-AC4 |
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

## 성능 측정

2026-10-08에 로컬에서 쟀다(T068). 003과 같이 Docker 이미지 빌드가 이 기기에서 멈춰서(기본 buildx 빌더가 중지된 다른 빌더로 잡혀 있다) API는 호스트에서 jar(`java -jar build/libs/api.jar`, JDK 21, `e2e` 프로필)로 띄우고, DB와 Redis는 따로 띄운 `pgvector/pgvector:pg17`, `redis:7.4-alpine` 컨테이너를 썼다. 웹은 `pnpm build && pnpm start`(APP_ENV=e2e)다. 기기는 8코어 Mac(M1 Pro)이고 측정 중 1분 부하(load average)는 3~4였다. 반복 테스트(SC-002, SC-003)를 돌리는 동안에는 5~7이었다. DB에는 그동안의 e2e가 남긴 회원 640여 명과 글 470여 개가 이미 있었다.

### 알림 목록, 마이페이지, 감정 통계 (SC-004)

회원 둘을 API로 가입과 온보딩까지 시킨 뒤 `apps/api/src/test/resources/seed/m3-perf.sql`로 한 회원에 글 1천 개(몬스터 포함, 작성 시각을 지난 8주에 고르게), 받은 알림 1만 개(안 읽음 8천 개), 댓글 1천 개, 공감 1천 개, 함께 물리친 몬스터 100개를 넣었다. API(`/api/v1/*`)를 그 회원의 토큰으로 차례로 100번씩 불렀고 데우기 5번은 뺐다. 다음 쪽은 첫 쪽이 준 커서로 불렀다. 단위는 ms다.

```bash
docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 \
  -v member_id=<회원 ID> -v actor_id=<다른 회원 ID> < apps/api/src/test/resources/seed/m3-perf.sql
```

| 요청 | 횟수 | p50 | p95 | p99 | 최대 | 목표 | 측정 중 부하 |
|---|---|---|---|---|---|---|---|
| 알림 목록 첫 쪽 | 100 | 15.9 | 21.8 | 27.8 | 29.0 | p95 1000 이하 | 2.9 → 2.9 |
| 알림 목록 다음 쪽 | 100 | 14.2 | 19.9 | 24.3 | 33.3 | p95 1000 이하 | 2.9 → 2.9 |
| 안 읽은 알림 수 | 100 | 12.7 | 18.5 | 19.5 | 19.7 | p95 1000 이하 | 2.9 → 2.7 |
| 내가 쓴 글 첫 쪽 | 100 | 17.9 | 22.1 | 23.2 | 27.0 | p95 1000 이하 | 2.7 → 2.7 |
| 내가 쓴 글 다음 쪽 | 100 | 16.3 | 20.0 | 23.6 | 34.1 | p95 1000 이하 | 2.7 → 2.7 |
| 내 댓글 첫 쪽 | 100 | 7.3 | 9.3 | 11.0 | 12.0 | p95 1000 이하 | 2.7 → 3.1 |
| 내 댓글 다음 쪽 | 100 | 7.2 | 12.7 | 20.1 | 20.6 | p95 1000 이하 | 3.1 → 3.1 |
| 공감한 글 첫 쪽 | 100 | 14.8 | 21.5 | 25.7 | 31.4 | p95 1000 이하 | 3.1 → 3.1 |
| 공감한 글 다음 쪽 | 100 | 15.2 | 23.6 | 27.3 | 33.3 | p95 1000 이하 | 3.1 → 3.1 |
| 감정 통계(글 1천 개) | 100 | 21.5 | 34.2 | 64.3 | 71.1 | p95 1000 이하 | 3.1 → 3.5 |

감정 통계는 전체 1,000, 처치 200, 함께 물리친 몬스터 100, 분포 20%씩으로 시드와 맞았다. 주별 추이는 125씩 일곱 주와 이번 주 63으로 합이 938이다. 시드가 지난 56일에 고르게 뿌리는데 측정일이 목요일이라 가장 오래된 62개는 8주 창 밖의 아홉 번째 주에 들어가 빠진 것이다.

### 행동에서 토스트까지 (SC-001)

Playwright로 글쓴이와 행동하는 회원을 서로 다른 브라우저 컨텍스트로 띄우고, 댓글 요청의 응답을 받은 시각과 글쓴이 화면에 토스트 요소가 붙은 시각(`MutationObserver`)의 차이를 20번 쟀다. 두 시각 모두 같은 기기의 시계다.

| 횟수 | 최소 | p50 | p95 | 최대 | 목표 | 부하 |
|---|---|---|---|---|---|---|
| 20 | 15ms | 22ms | 39ms | 46ms | p95 3000ms 이하 | 3.7 → 4.0 |

브라우저, 웹, API, DB, Redis가 모두 한 기기에 있어 네트워크 지연이 없다. 운영에서는 여기에 브라우저와 VM 사이의 왕복 시간이 더해진다.

### 재전송, 두 인스턴스, Redis 장애 반복 (SC-002, SC-003)

`NotificationStreamReplayTest`(5개), `TwoInstanceStreamTest`(2개), `RedisOutageStreamTest`(5개)를 한 번에 묶어 `--rerun-tasks`로 20번 돌렸다.

```bash
cd apps/api && ./gradlew test --rerun-tasks --tests "*NotificationStreamReplayTest*" \
  --tests "*TwoInstanceStreamTest*" --tests "*RedisOutageStreamTest*"
```

| 테스트 | 한 번에 도는 테스트 | 실행 | 실패 | 빠짐이나 중복 |
|---|---|---|---|---|
| `NotificationStreamReplayTest` | 5 | 20번 | 0 | 0건 |
| `TwoInstanceStreamTest` | 2 | 20번 | 0 | 0건 |
| `RedisOutageStreamTest` | 5 | 20번 | 0 | 0건 |

20번 모두 통과했다. 실행 중 1분 부하는 3.3~7.3이었다. 이 테스트들은 받은 이벤트의 알림 ID와 번호가 DB의 그 구간과 같고 같은 번호가 두 번 오지 않는지를 단언하므로, 통과가 곧 빠짐과 중복 0건이다.

### 공감 100번 묶음 (SC-005)

회원 100명이 같은 글에 32초 동안 5명씩 겹쳐 공감했다(100번 모두 200). 글쓴이의 알림 목록에는 공감 알림이 하나였고, 안 읽음이며 `actorCount`가 100이었다.

### 비용 (SC-006)

`infra/compose.prod.yaml`의 서비스는 `caddy`, `api`, `postgres`, `redis` 넷이다. 이번에 더해진 것은 같은 VM 안의 `redis:7.4-alpine` 컨테이너 하나이고 새 외부 서비스나 유료 기능은 없다. 월 비용은 그대로 0원이다.

## 수동 시나리오 실행 기록

2026-10-08에 위 환경에서 28개를 모두 끝까지 돌렸다(T069). Playwright 스크립트 하나가 실제 웹 화면에서 회원 A(탭 두 개), B, C, D, E를 서로 다른 브라우저 컨텍스트로 띄워 표의 순서대로 진행했고, 화면으로 볼 수 없는 것(9번의 스트림 401, 15번의 남의 알림 읽음)은 같은 스크립트에서 API를 불렀다. 22번은 `m3-perf.sql` 시드가 피드에 글 2천 개를 더해 뒤 단계의 피드 확인을 가리므로 마지막에 돌렸다.

| # | 결과 | 확인한 내용 |
|---|---|---|
| 1 | 통과 | A의 두 탭 모두 "몬스터가 나타났어요" 토스트와 배지 1 |
| 2 | 통과 | 두 탭 모두 "B 님이 내 글에 댓글을 남겼어요", 배지 2 |
| 3 | 통과 | A의 배지와 서버의 안 읽은 수는 2 그대로. B에게 "A 님이 내 댓글에 답글을 남겼어요", 배지 1 |
| 4 | 통과 | A에게 "C 님이 내 글에 답글을 남겼어요"(배지 3), B에게 "C 님이 내 댓글에 답글을 남겼어요"(배지 2) |
| 5 | 통과 | B의 공감으로 배지 4, C의 공감 뒤에도 4. 목록 맨 위가 공감 알림 하나이고 `actorCount` 2, 토스트는 "C 님 외 1명이 공감했어요" |
| 6 | 통과 | 취소 후 다시 공감해도 배지 5(5번에서 다른 글 공감 하나를 더했다) 그대로이고 알림 ID와 번호가 바뀌지 않음 |
| 7 | 통과 | HP 2에서 D의 공감과 댓글로 0. A에게 "내 몬스터가 처치됐어요", B, C, D의 목록에 "함께 공격한 몬스터가 처치됐어요"가 하나씩 |
| 8 | 통과 | 오프라인 동안 배지 7 그대로. 다시 붙은 뒤 두 탭 모두 10. 그 사이 알림은 댓글 2개와 공감 묶음 1개로 번호가 서로 다름 |
| 9 | 통과 | 로그아웃 상태의 `/notifications`는 `/login?next=%2Fnotifications`로 이동. 티켓 없는 요청과 엉터리 티켓은 401, 한 번 쓴 티켓은 401 `STREAM_TICKET_INVALID` |
| 10 | 통과 | 안 읽은 알림 30개에서 목록은 20개. 첫 항목에 문구, 닉네임, 글 앞부분, 시각, "안 읽음" |
| 11 | 통과 | 끝까지 내리면 30개이고 "더 보기"가 사라짐 |
| 12 | 통과 | 누르면 글 상세로 이동하고 배지 29. 돌아오면 그 항목이 읽음 |
| 13 | 통과 | 모두 읽음 요청을 1.5초 늦춘 사이 C가 처음 공감. 응답 뒤 배지와 서버의 안 읽은 수가 1이고 안 읽은 항목은 그 공감 하나 |
| 14 | 통과 | 글을 지우면 그 글의 알림이 "삭제된 글"로 남고 링크가 아님. 누르면 "삭제된 글이에요."이고 주소는 그대로 |
| 15 | 통과 | B가 A의 알림을 읽음 처리하면 404 `NOTIFICATION_NOT_FOUND`, A의 알림은 안 읽음 그대로 |
| 16 | 통과 | 지운 글을 뺀 내 글 2개가 최신순. 첫 글에 HP 0/10, 공감 3, 댓글 5, 작성 시각 |
| 17 | 통과 | 지운 댓글을 뺀 내 댓글 2개가 최신순이고 답글에는 "답글" 표시. 누르면 그 글로 이동 |
| 18 | 통과 | B의 공감한 글은 취소 후 다시 공감한 글이 맨 위. 지운 글은 없음 |
| 19 | 통과 | 새 회원 E의 세 탭 모두 빈 안내와 "글쓰기", "피드 보기" |
| 20 | 통과 | 내 몬스터 1, 물리친 몬스터 1, 불안 1마리 100%이고 나머지 0% |
| 21 | 통과 | 불안 2, 짜증 2(짜증이 더 최근)인 회원의 "가장 많이 나타난 몬스터"가 짜증 |
| 22 | 통과 | 시드를 넣은 회원의 추이가 막대 8개. 주 시작일은 2026-08-17부터 2026-10-05까지 모두 월요일이고 값은 125가 일곱 주, 이번 주 63 |
| 23 | 통과 | E의 숫자가 0이고 "아직 몬스터가 없어요"와 "고민 쓰기" |
| 24 | 통과 | B의 "함께 물리친 몬스터" 1 |
| 25 | 통과 | 닉네임, 직군, 경력을 바꿔 저장하면 `/my`로 돌아가 바뀐 값이 보임 |
| 26 | 통과 | B의 닉네임을 대문자로 넣으면 "이미 사용 중인 닉네임입니다.", `A!@`는 "한글, 영문, 숫자로 1~10자를 입력하세요."이고 닉네임은 그대로 |
| 27 | 통과 | 피드에서 예전 글은 "디자인 · 3년차", 새 글은 "기획 · 5년차" |
| 28 | 통과 | B가 본 A의 예전 글과 댓글, B가 받은 답글 알림이 모두 바뀐 닉네임이고 예전 닉네임은 보이지 않음 |

표의 절차와 다르게 한 것이 둘 있다. 5번과 6번 사이에 B가 A의 다른 글에 공감을 하나 더 했다(18번에서 공감 순서를 보려고). 그래서 6번 뒤의 배지는 4가 아니라 5다. 21번과 22번은 A가 아닌 새 회원으로 했다.

### Redis 장애와 API 재시작

같은 날 Playwright로 A를 웹(API 18080)에 붙여 두고, 다른 회원의 공감은 같은 DB와 Redis를 쓰는 두 번째 API(18081)로 보냈다.

| 상황 | 결과 |
|---|---|
| 평소(다른 인스턴스에서 만든 알림) | 공감 응답 뒤 91ms에 A의 배지가 늘었다 |
| Redis 컨테이너를 멈추고 16초 뒤 공감 | 공감은 200이고, 2.3초 뒤 A의 배지가 늘었다(줄어든 안전망 주기 5초 안) |
| Redis를 다시 띄우고 16초 뒤 공감 | 97ms에 배지가 늘었다 |
| API(18080)를 죽인 사이 18081로 공감, 다시 띄움 | 화면이 연결 끊김으로 바뀌었다가 API를 띄운 지 17초 뒤(기동 시간 포함) 다시 붙었고, 그 알림을 한 번 받아 배지 4, 목록 4개(번호 1~4) |

Redis가 내려가 있는 동안 API의 `/actuator/health`는 UP, `/actuator/health/realtime`은 DOWN이었고, Redis를 다시 띄우자 API를 건드리지 않아도 `realtime`이 UP으로 돌아왔다.

## 운영 준비 (저장소 소유자)

1. **Redis 컨테이너를 compose에 더한다**(research R5). `infra/compose.prod.yaml`에 아래 서비스를 넣고, `api`에 `depends_on: redis: condition: service_started`(Redis 장애가 API 기동을 막지 않게)와 `REDIS_URL`을 더한다. `compose.e2e.yaml`과 로컬 `apps/api/compose.yaml`에도 같은 서비스를 비밀번호 없이 넣는다.

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

5. 헬스 체크: Redis가 내려가도 알림은 저장되므로 `/actuator/health`(배포 스크립트의 롤백 기준)에는 Redis를 넣지 않는다(기본 그룹이 Redis 지표를 빼도록 설정하고, Redis 지표는 따로 조회한다). Redis 상태는 compose 헬스 체크와 `/actuator/health/realtime`(그룹 이름은 지표 이름 `redis`와 겹칠 수 없다), `docker compose ps`로 본다.
6. 배포 뒤 확인: 브라우저에서 로그인하고 개발자 도구의 네트워크 탭에서 `stream` 요청이 `200 text/event-stream`으로 열려 있고, 25초마다 데이터가 조금씩 오는지 본다. 다른 계정으로 공감해 3초 안에 토스트가 뜨는지 본다.
7. 기존 데이터에는 이관이 없다. `V4__notification_mypage.sql`은 테이블과 인덱스만 만든다. 큰 테이블에 인덱스를 만들지만 지금 데이터 규모(글 수천 개)에서는 잠금 시간이 짧다.
