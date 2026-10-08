# Research: 알림과 마이페이지 (004-notification-mypage)

스펙의 요구사항을 구현 결정으로 옮기면서 검토한 내용이다. 형식은 003과 같이 결정, 근거, 검토한 대안 순서다.

## R1. 모듈 배치와 의존 방향

**결정**: `notification` 모듈을 새로 만들고, 마이페이지 조회는 기존 `feed` 모듈에 더한다. 프로필 수정과 연결 표는 `member`가 맡는다.

| 모듈 | 이 마일스톤의 책임 | 의존 |
|---|---|---|
| `member` | 프로필 수정, 연결 표(SSE 티켓) 발급과 소비 | 없음 |
| `post` | 내 글, 내 댓글, 공감한 글 키셋 조회, 알림용 글 미리보기와 댓글 조회 파사드 | `member` |
| `monster` | `MonsterSpawned` 발행, 처치에 함께한 회원 조회, 통계용 몬스터 행 조회 | `post`, `emotion` |
| `notification` | 알림 생성, 묶음, 보관, 목록, 읽음, SSE 전달 | `post`, `monster`, `member` |
| `feed` | 마이페이지 세 목록과 감정 통계 조합 | `post`, `monster`, `emotion`, `member` |

**근거**:
- overview 5.1의 그래프는 `notification → post, monster`다. 알림 목록에 행동한 회원의 닉네임을 보여 주려면 `MemberApi.getMembers`가 필요하므로 `notification → member`를 더한다. `member`는 어떤 모듈에도 의존하지 않으니 순환이 생기지 않는다.
- `notification`은 `emotion`에 의존하지 않는다. 몬스터 생성 알림은 `EmotionAnalyzed`가 아니라 `monster`가 내는 `MonsterSpawned`를 받는다(R8).
- 감정 통계는 overview 표에서 `emotion`의 책임으로 적혀 있지만, 숫자의 원천은 `monsters`와 `monster_hp_log`다. `emotion`이 `monster`를 알면 `monster → emotion`과 순환이 생기므로 통계는 `feed`가 파사드를 모아 계산한다(R12). overview 표의 문구는 구현 때 고친다.
- 연결 표는 인증의 일부다. 세션이 아직 살아 있는지 확인해야 하므로 세션 테이블을 가진 `member`가 발급하고 소비한다. `notification`은 `MemberApi.consumeStreamTicket`만 부른다.

## R2. SSE 전송 방식 (Spring Boot 4, MVC)

**결정**: Spring MVC의 `SseEmitter`를 쓴다. WebFlux는 들이지 않는다.
- 연결 하나는 서블릿 비동기 요청 하나다. 기다리는 동안 Tomcat 요청 스레드를 쥐지 않는다. 쓰기는 `notification` 모듈의 전용 실행기(스레드 4개)가 한다.
- Tomcat NIO의 기본 연결 상한(`server.tomcat.max-connections` 8192)을 그대로 쓴다. 회원 한 명의 동시 연결은 5개까지 받고, 6번째가 오면 가장 오래된 연결을 닫는다(US1-AC7은 탭 여러 개를 요구하지만 무한히 열 이유는 없다).
- 하트비트는 25초마다 주석 줄(`: hb`)과 id 없는 `ping` 이벤트를 보낸다(브라우저 EventSource는 주석을 스크립트에 알리지 않아, 웹이 조용한 연결을 알아채려면 이벤트가 필요하다). 쓰기에 실패하면(`IOException`) 그 연결을 허브에서 지운다. 끊긴 연결을 늦어도 25초 안에 알아챈다.
- 연결 하나의 수명은 15분이다. 서버가 `complete()`로 닫으면 웹이 새 티켓으로 다시 붙는다. access 토큰 수명(15분)과 같게 두어, 로그아웃이나 세션 무효화 뒤에도 스트림이 그보다 오래 남지 않는다.
- 응답 헤더: `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`.
- Caddy는 `reverse_proxy`가 `text/event-stream` 응답을 받으면 즉시 흘려보낸다. 다만 지금 Caddyfile의 `encode zstd gzip`이 SSE 응답을 압축 버퍼에 담지 않도록 스트림 경로를 `encode`에서 뺀다. 읽기와 쓰기 타임아웃은 Caddy 기본값(없음)이라 따로 늘리지 않는다(quickstart 운영 준비).

**근거**:
- 알림은 회원당 분에 몇 건 수준이고, 지금 API가 MVC라 WebFlux를 함께 두면 보안 필터 체인과 예외 처리를 두 벌 갖게 된다.
- 서블릿 비동기라 연결 수가 스레드 수에 묶이지 않는다. VM(4 OCPU, 24GB)에서 수천 연결은 메모리로도 문제없다.
- 하트비트가 없으면 중간 장비가 조용한 연결을 끊어도 서버가 모른 채 허브에 쌓인다.

**검토한 대안**:
- WebFlux `Flux<ServerSentEvent>`: 연결당 비용은 더 작지만, 이 규모에서 얻는 것이 없고 스택이 둘이 된다.
- 가상 스레드로 요청마다 블로킹 루프: 동작은 하지만 `SseEmitter`보다 나은 점이 없고 하트비트와 정리 로직을 직접 짜야 한다.

## R3. 일회용 연결 표(티켓)

**결정**:
1. 웹의 전용 BFF 라우트 `POST /api/notifications/stream-ticket`가 쿠키의 access 토큰으로 API `POST /api/v1/notifications/stream-tickets`를 부른다. 세션 오류 401이면 기존 `callWithSessionRefresh`로 한 번 갱신하고 다시 부른다.
2. API(`member`)는 32바이트 난수를 base64url로 만든 티켓을 돌려주고, 저장은 SHA-256 해시만 한다(`sse_ticket`, 만료 30초, `session_id` 포함).
3. BFF는 `{ ticket, streamUrl, expiresAt }`을 돌려준다. `streamUrl`은 서버 환경 변수 `SSE_PUBLIC_ORIGIN`(없으면 `API_ORIGIN`)으로 만든 API 도메인의 `/api/v1/notifications/stream` 주소다.
4. 브라우저는 `EventSource(streamUrl + "?ticket=...&lastEventId=...")`로 API 도메인에 바로 붙는다.
5. API는 한 문장으로 소비한다: `UPDATE sse_ticket SET used_at = now() WHERE token_hash = :h AND used_at IS NULL AND expires_at > now() RETURNING member_id, session_id`. 행이 없으면 `401 STREAM_TICKET_INVALID`다. 행이 있으면 세션이 아직 유효한지 확인한다.
6. 스트림 경로는 API 보안 설정의 공개 경로에 넣는다(Bearer를 받지 않는다). CORS는 이 경로에만 `OGU_SSE_ALLOWED_ORIGINS`(웹 출처)를 허용하고 자격 증명은 쓰지 않는다.
7. 일반 프록시 `/api/[...path]`는 `notifications/stream-tickets`를 넘기지 않는다. 티켓은 전용 라우트에서만 나간다.
8. 하루가 지난 티켓 행은 정리 작업(R10)이 지운다.

**근거**:
- access 토큰을 주소에 싣지 않는다(FR-006). 주소에 실리는 티켓은 30초 안에 한 번만 쓸 수 있고 해시로만 저장되므로, 로그나 기록에 남아도 다시 쓸 수 없다.
- 소비가 조건부 `UPDATE` 한 문장이라 같은 티켓으로 동시에 두 번 붙어도 한 번만 성공한다. 인스턴스가 둘이어도 DB가 판정하므로 저장소를 따로 둘 필요가 없다.
- 브라우저 `EventSource`의 자동 재연결은 같은 주소(이미 쓴 티켓)로 다시 붙으므로 쓰지 않는다. 웹이 끊김을 받으면 직접 닫고 새 티켓을 받아 연다(R14). 새 `EventSource`는 `Last-Event-ID` 헤더를 붙일 수 없으므로 마지막 ID는 쿼리 `lastEventId`로 넘긴다. 서버는 헤더와 쿼리를 모두 읽고 헤더를 우선한다.

**검토한 대안**:
- 티켓을 Redis에 TTL 30초로 두고 `GETDEL`: Redis는 이번에 들어오지만(R5) 티켓은 DB에 둔다. 소비와 세션 확인이 DB 한 곳에서 끝나고, Redis가 내려가도 연결 자체는 막히지 않는다.
- 서명한 짧은 JWT를 티켓으로: 서버에 상태가 없어 "한 번만" 보장이 안 된다.
- 쿠키로 인증: 웹(Vercel)과 API 도메인이 달라 `__Host-` 쿠키를 API가 받을 수 없다(ADR-0002).

## R4. 회원별 전달 순서와 Last-Event-ID 재전송

**결정**:
- 알림 행마다 회원별 전달 순서 번호 `seq`를 둔다. SSE 이벤트의 `id`가 이 값이다.
- 번호는 회원별 카운터 행 `notification_sequence(member_id, last_seq)`에서 받는다. 알림을 만들거나 고치는 트랜잭션 안에서 `INSERT ... ON CONFLICT (member_id) DO UPDATE SET last_seq = last_seq + 1 RETURNING last_seq`를 먼저 실행한다.
- 묶인 공감 알림(R7)에 새 공감이 들어오면 같은 행을 고치면서 `seq`를 새 번호로 올린다. 행은 하나 그대로이고 번호만 커진다.
- 재전송은 `WHERE receiver_id = :me AND seq > :lastEventId AND created_at >= now() - 90일 ORDER BY seq`다. 묶음 알림은 마지막 상태로 한 번만 다시 온다.
- 웹은 알림을 `id`(알림 ID)로 덮어쓴다. 같은 묶음이 번호를 바꿔 다시 오면 목록의 기존 항목을 지우고 맨 위에 둔다.
- 웹은 첫 연결에도 unread-count의 `latestSeq`를 `lastEventId`로 넘긴다. `lastEventId`가 아예 없으면 지금의 `last_seq`부터 시작한다. 지난 알림은 목록 API가 보여 준다.

**근거**:
- 카운터 행 잠금은 커밋까지 유지된다. 그래서 같은 회원의 번호 N+1은 N을 받은 트랜잭션이 끝난 뒤에야 나온다. 커밋 순서와 번호 순서가 같으므로, "N+1을 보낸 뒤 N이 늦게 커밋되어 영영 빠지는" 일이 생기지 않는다. 전역 시퀀스(`nextval`)는 커밋 순서를 보장하지 않아 이 문제가 생긴다.
- 번호에는 빈칸이 생길 수 있다(멱등 키에 걸려 삽입하지 않은 경우, R6). 재전송은 `>` 비교라 빈칸은 상관없고, 지켜야 하는 것은 순서다.
- 묶음의 번호를 올리는 방식이면 "새 공감이 오면 맨 위로"(FR-002)와 "끊긴 동안의 변화를 빠짐없이"(FR-005)가 같은 규칙 하나로 풀린다. 중간 상태(예: 3명, 4명)는 다시 보내지 않고 마지막 상태(5명)만 보내므로, 웹이 덮어쓰는 한 중복으로 보이지 않는다(SC-002).
- 회원마다 잠금이 따로라 다른 회원의 알림끼리는 서로 기다리지 않는다. 한 트랜잭션이 여러 회원의 번호를 받을 때(처치 알림, R9)는 회원 ID 오름차순으로 잠가 교착을 막는다.

**검토한 대안**:
- 알림 ID(identity)를 이벤트 ID로: 커밋 순서와 ID 순서가 어긋나 재전송에서 빠질 수 있고, 묶음 갱신을 표현하지 못한다.
- 별도의 전달 로그 테이블(알림이 바뀔 때마다 한 행): 묶음에 공감 100번이면 로그가 100행이 되고 재전송 때 같은 알림이 여러 번 간다.

## R5. 인스턴스 간 전달: Redis pub/sub

**결정**: overview 4절과 5.1대로 M3에서 Redis를 들여 SSE 팬아웃에 쓴다. Redis는 기존 VM의 compose 안에 컨테이너로 띄운다. Redis로는 "이 회원에게 새 것이 있다"는 신호만 보내고, 내용은 언제나 DB에서 번호로 다시 읽는다.
1. 알림을 쓰는 트랜잭션은 커밋 뒤 훅(`TransactionSynchronization.afterCommit`)에 발행을 예약한다. 커밋되면 채널 `ogu:notification`에 `{memberId}:n:{seq}`를 `PUBLISH`한다. 읽음 처리는 `{memberId}:r`이다. 알림 내용은 싣지 않는다.
2. 발행은 Spring Data Redis(Lettuce)로 하고, 명령 타임아웃은 1초다. 실패하면 WARN만 남기고 넘어간다. 알림 행은 이미 커밋됐으므로 요청이나 리스너를 실패시키지 않는다.
3. 인스턴스마다 `RedisMessageListenerContainer`가 채널을 구독한다. 신호를 받으면 그 회원의 이 인스턴스 연결마다 "따라잡기(drain)"를 한다. 연결별로 잠금을 잡고 `seq > 연결의 마지막 전송 번호`인 행을 DB에서 읽어 보낸 뒤 번호를 올린다. 신호의 `seq`는 힌트일 뿐이라, 이미 그 번호까지 보낸 연결은 쿼리 없이 건너뛴다.
4. 안전망: 60초마다 연결별로 `max(seq)`를 확인해 뒤처진 연결을 따라잡는다. pub/sub는 많아야 한 번 전달하므로, 커밋과 발행 사이에 프로세스가 죽거나 구독 연결이 잠깐 끊겨 놓친 신호를 이것이 메운다.
5. 구독이 끊겼다가 다시 붙으면(컨테이너의 복구 주기 5초) 이 인스턴스의 모든 연결을 한 번씩 따라잡는다.

**Redis가 내려가 있을 때**:
- 알림 생성은 Redis와 무관하게 DB 트랜잭션으로 끝난다. 알림은 만들어지고 저장되며, 목록과 안 읽은 수 API도 그대로 동작한다.
- 실시간 전달만 느려진다. 구독이 끊긴 것을 알아채면(컨테이너 오류 콜백이나 5초 PING 실패 2회) 안전망 주기를 60초에서 5초로 줄이고, 구독이 돌아오면 60초로 되돌린다. 그동안 SC-001(3초 안 95%)은 지키지 못할 수 있지만 빠지는 알림은 없다.
- 다시 연결한 화면은 언제나 DB에서 `lastEventId` 이후를 받으므로 Redis 장애가 알림 손실로 이어지지 않는다.
- Actuator의 Redis 헬스 지표는 `/actuator/health` 판단에서 뺀다. 배포 스크립트가 헬스 체크 실패로 API를 롤백하지 않게 하기 위해서다. Redis 상태는 별도 그룹(`/actuator/health/realtime`)과 로그로 본다.
- API는 Redis 없이도 기동한다(compose는 `service_started`로만 기다린다).

**근거**:
- 사용자 요구사항이 overview의 Redis 도입 시점(M3 SSE 팬아웃)을 따르라고 명시했다. M5 레이드가 보스 HP를 Redis Lua로 원자적으로 줄이고(overview 5.4), 요청 제한도 Bucket4j와 Redis로 옮길 예정이라(overview 5.8) 같은 컨테이너를 그대로 다시 쓴다.
- Redis는 compose 안의 컨테이너라 월 비용이 0원 그대로다(SC-006, constitution VI). 메모리 상한 64MB로 VM(24GB)에 부담이 없다.
- 신호만 보내고 내용은 DB에서 읽으므로 같은 신호가 두 번 와도 중복 전송이 없고(연결별 잠금과 번호), 신호를 잃어도 손실이 아니다. SC-002와 SC-003의 "빠짐도 중복도 0건"은 전달 계층이 아니라 R4의 번호 규칙과 DB 재전송이 보장한다.
- 커밋 뒤에만 발행하므로, 구독자가 아직 커밋되지 않은 알림을 찾다가 못 보는 일이 없다.
- 재시작하면 연결이 끊기고, 웹은 마지막 번호로 다른 인스턴스(또는 다시 뜬 인스턴스)에 붙어 DB에서 다시 받는다(SC-003).

**검토한 대안**:
- **Postgres LISTEN/NOTIFY**: 새 컨테이너가 없고 `NOTIFY`가 커밋 때만 전달돼 발행 시점이 저절로 맞는다는 장점이 있다. 그래도 고르지 않은 이유는 두 가지다. 사용자 요구사항과 overview(4절, 5.1)가 M3에서 Redis를 들이기로 정해 두었고, Redis는 M5 레이드 HP에서 어차피 필요해 지금 들이면 운영 경험을 미리 쌓는다. 덧붙여 인스턴스마다 풀 밖의 전용 DB 연결을 하나씩 쥐어야 한다.
- **Redis Streams(소비자 그룹)**: 신호를 저장해 놓친 것도 다시 받을 수 있지만, 내용과 순서는 이미 DB의 번호가 보장하므로 저장하는 신호가 중복일 뿐이다.
- **주기 폴링(2초마다 연결별 조회)**: 가장 단순하지만 연결 수 x 0.5회/초의 쿼리가 늘 돈다. Redis가 내려갔을 때의 대체 경로(5초)와 평소 안전망(60초)으로만 쓴다.

## R6. 도메인 이벤트에서 알림 만들기

**결정**:
- `notification`은 `PostLiked`, `CommentCreated`, `MonsterSpawned`, `MonsterDefeated`를 `@ApplicationModuleListener`로 받는다. 원래 트랜잭션이 커밋된 뒤 비동기로, 새 트랜잭션에서 실행된다. 실패하면 Event Publication Registry에 미완료로 남고 `EventPublicationResubmitter`가 다시 보낸다(FR-003).
- 리스너는 먼저 글이 살아 있는지 `PostApi.find(postId)`로 본다. 지운 글이면 아무것도 만들지 않는다(경계 상황).
- 멱등은 유일 키로 보장한다.
  - 댓글과 답글, 몬스터 알림: `UNIQUE (receiver_id, dedup_key)`. 키는 `COMMENT:{commentId}`, `SPAWNED:{monsterId}`, `DEFEATED:{monsterId}`다. 받는 사람의 카운터 행을 먼저 잠가 번호를 받고 `INSERT ... ON CONFLICT DO NOTHING`으로 넣는다. 들어가지 않았으면 SSE 신호도 보내지 않는다. 그 번호는 빈 채로 남지만 재전송은 `>` 비교라 영향이 없다.
  - 공감: 참여자 표의 기본 키 `(post_id, liker_id)`(R7).
- 댓글 알림의 받는 사람은 `PostApi.findComment(commentId)`로 정한다. 댓글이 이미 지워졌으면 만들지 않는다.
  - 원 댓글: 글쓴이에게 `POST_COMMENT`. 단 행동한 사람이 글쓴이면 만들지 않는다(US1-AC5).
  - 답글: 글쓴이에게 `POST_REPLY`(행동한 사람이 글쓴이가 아니면), 원 댓글 주인에게 `COMMENT_REPLY`(행동한 사람도 글쓴이도 아니면).
  - 두 사람 모두 같은 키 `COMMENT:{commentId}`를 쓰므로, 한 답글로 같은 회원에게 알림이 두 개 생길 수 없다(FR-001).
- 한 트랜잭션에서 알림 행, 번호, 참여자를 함께 쓰고, Redis 발행은 커밋 뒤 훅에 예약한다(R5). 커밋되면 모두 보이고 신호가 나가며, 실패하면 아무것도 없고 신호도 나가지 않는다.

**근거**:
- 알림은 원래 행동과 분리돼야 한다(FR-003). `monster`처럼 같은 트랜잭션에서 받으면 알림 실패가 댓글 저장까지 되돌린다.
- 재발행은 "적어도 한 번"이므로 같은 이벤트가 두 번 와도 결과가 같아야 한다. 유일 키가 그 역할을 하고, 번호 카운터는 삽입이 성공한 경우에만 올라가 재발행이 SSE 이벤트를 늘리지 않는다.
- `CommentCreated`에는 부모 댓글 정보가 없다. 이벤트를 넓히는 대신 파사드로 읽는다. 이벤트에는 ID만 싣는다는 기존 규칙(AGENTS.md "이벤트 재전송")을 지킨다.

**검토한 대안**:
- 이벤트에 받는 사람 목록을 담아 `post`가 계산: `post`가 알림 규칙을 알게 되고, 처치 알림처럼 `monster`의 정보가 필요한 경우를 다룰 수 없다.

## R7. 공감 알림 묶음 (FR-002, US1-AC2, SC-005)

**결정**:
- 묶음은 `(받는 사람, 글)`마다 안 읽은 `POST_LIKE` 알림 하나다. 부분 유일 인덱스 `UNIQUE (receiver_id, post_id) WHERE type = 'POST_LIKE' AND read_at IS NULL`로 강제한다.
- 처리 순서(한 트랜잭션):
  1. 받는 사람(글쓴이)의 카운터 행을 잠그고 번호를 받는다. 이 잠금이 같은 회원의 묶음 갱신을 줄 세운다.
  2. 안 읽은 묶음이 있으면 `actor_count + 1`, `latest_actor_id`, `seq`, `updated_at`을 바꾼다. 없으면 새 묶음을 만든다(`actor_count = 1`).
  3. 그 묶음 ID로 참여자 표 `like_notification_participant(post_id, liker_id)`에 `ON CONFLICT DO NOTHING`으로 넣는다. 0행이면(이미 알린 공감) 트랜잭션 전체를 되돌려 번호와 묶음 갱신을 모두 취소한다.
- 읽음과 겹치는 경우: 읽음 처리의 `UPDATE`와 2단계의 `UPDATE ... WHERE read_at IS NULL`은 같은 행 잠금을 두고 줄을 선다. 읽음이 먼저 커밋되면 2단계는 0행이 되어 새 묶음을 만들고, 공감이 먼저면 번호가 올라가 "모두 읽음"의 기준 번호(R11)보다 커지므로 안 읽은 채로 남는다.

**근거**:
- 참여자 기본 키가 `(post_id, liker_id)`라서 공감, 취소, 재공감을 반복해도 알림은 처음 한 번뿐이다(경계 상황). 묶음이 바뀌어도(읽은 뒤 새 묶음) 같은 회원은 다시 세지 않는다.
- 공감 100번이 1분에 몰려도 카운터 행 잠금으로 차례로 처리되고, 부분 유일 인덱스가 안 읽은 묶음을 하나로 묶어 둔다(SC-005). 공감 하나의 처리는 짧은 쿼리 네 개라 100건이 줄을 서도 1초 안팎이다.
- 묶음에 들어온 공감을 SSE로 보낼 때 같은 알림 ID로 가므로, 화면은 새 항목을 늘리지 않고 숫자만 바꿔 맨 위로 올린다.

**검토한 대안**:
- 읽을 때 묶기(행은 공감마다 쌓고 조회에서 GROUP BY): 쓰기는 단순하지만 목록 키셋, 안 읽은 수, 재전송이 모두 묶음 단위로 다시 계산돼야 해 SC-004(알림 1만 개에서 1초)를 맞추기 어렵다.

## R8. 몬스터 생성 알림: `MonsterSpawned` 새 이벤트

**결정**: `monster`의 `MonsterFactory`가 몬스터를 저장한 직후 같은 트랜잭션에서 `MonsterSpawned(postId, monsterId, defaulted)`를 발행한다. `notification`은 이것을 받아 글쓴이에게 `MONSTER_SPAWNED`를 만든다. 기본 몬스터(분석 24시간 실패)도 똑같이 알린다(US1-AC3).

분석 전 공격만으로 생성과 동시에 처치되는 경우(경계 상황), 같은 트랜잭션에서 `MonsterSpawned`와 `MonsterDefeated(retroactive = true)`가 차례로 나간다. 두 비동기 리스너가 실행되는 순서는 보장되지 않으므로, `retroactive = true`인 `MonsterDefeated` 처리만 글쓴이의 `SPAWNED:{monsterId}` 알림이 없으면 먼저 만들고 나서 처치 알림을 만든다. 둘 다 같은 카운터 잠금 아래에서 만들어지므로 번호가 "나타났어요" 다음 "처치됐어요" 순서가 된다. 뒤늦게 온 `MonsterSpawned`는 유일 키에 걸려 아무것도 하지 않는다(그 사이 받은 번호는 빈 채로 남지만 재전송은 `>` 비교라 해가 없다, R4).

생성 뒤 따로 처치된 몬스터(`retroactive = false`)는 생성 알림을 다시 만들지 않는다. 생성이 먼저 커밋됐으므로 생성 알림은 이미 있거나 곧 생기고, 90일이 지나 정리 작업이 지운 생성 알림을 처치가 다시 만들면 글쓴이에게 "나타났어요"가 거짓으로 새로 뜨기 때문이다. 이 경우 생성 리스너가 실패해 재전송을 기다리는 동안 처치되면 두 알림의 번호 순서가 뒤바뀔 수 있으나, 둘 다 빠짐없이 전달된다.

**근거**:
- `EmotionAnalyzed`를 받으면 몬스터가 실제로 생겼는지(지운 글이면 만들지 않는다) 알 수 없고, `notification → emotion` 의존이 새로 생긴다. `monster`의 이벤트를 받으면 overview 그래프의 `notification → monster` 안에서 끝난다.
- 생성과 처치의 순서를 리스너 실행 순서에 맡기지 않고, 처치 쪽이 생성을 보장하게 해서 순서를 데이터로 고정한다.

## R9. 처치 알림 받는 사람 (US1-AC4, 명확화 1)

**결정**: `MonsterDefeated`를 받으면 받는 사람은 글쓴이와 `MonsterApi.damagerIds(monsterId)`의 합집합에서 글쓴이 중복을 뺀 회원들이다.
- `damagerIds`는 `SELECT DISTINCT member_id FROM monster_hp_log WHERE monster_id = :id AND hp_after < hp_before`다. 처치 뒤에 남긴 응원(`hp_before = hp_after = 0`)은 HP를 줄이지 않았으므로 빠진다.
- 글쓴이의 행동은 기록에 남지 않지만(003 R5 규칙 1), 방어로 글쓴이 ID를 한 번 더 뺀다.
- 글쓴이는 `MONSTER_DEFEATED`("내 몬스터가"), 나머지는 `MONSTER_DEFEATED_TOGETHER`("함께 공격한 몬스터가")를 받는다. 키는 모두 `DEFEATED:{monsterId}`라 회원마다 한 번이다.
- 받는 사람을 회원 ID 오름차순으로 처리해 카운터 행을 잠그는 순서를 고정한다(R4).

**근거**: 명확화 1의 "HP를 줄인 회원"을 기록 행이 아니라 실제 감소로 해석한다. 기록 유일 키가 `(monster, member, action, target)`이라 한 회원이 행을 여러 개 가질 수 있으므로 `DISTINCT`가 필요하다. 함께 물리친 몬스터 통계(R12)도 같은 조건을 쓴다.

## R10. 90일 보관과 정리 작업 (FR-010)

**결정**:
- 목록, 안 읽은 수, 재전송, 읽음 처리는 모두 `created_at >= now() - 90일`인 알림만 본다. 정리 작업이 늦어도 화면에는 보관 기간이 정확히 지켜진다.
- `NotificationPurgeJob`이 매일 04:00(한국 시간)에 90일이 지난 알림을 1,000행씩 지운다. 참여자 행은 FK `ON DELETE CASCADE`로 함께 지워진다. 같은 작업이 7일이 지난 완료된 이벤트 발행(`CompletedEventPublications.deletePublicationsOlderThan`)도 지운다. 하루 지난 `sse_ticket` 행은 `member` 모듈의 `SseTicketCleanupJob`이 따로 지운다.
- 인스턴스가 둘이어도 지우기는 멱등이라 ShedLock 없이 둔다.

**근거**:
- 명확화 3이 "만든 뒤 90일"이라 묶음도 처음 만든 시각 기준이다. 묶음이 최근에 갱신됐어도 만든 지 90일이 지나면 빠진다.
- 공감과 댓글마다 `event_publication` 행이 하나씩 더 생기므로(알림 리스너), 003에서 미뤄 둔 완료 발행 정리를 여기서 한다.
- 참여자 행이 함께 지워지면 90일 뒤 같은 회원의 재공감이 다시 알릴 수 있다. 보관 기간이 지난 알림과 같은 취급이므로 받아들인다.

## R11. 읽음, 모두 읽음, 안 읽은 수 (FR-008, FR-009)

**결정**:
- 하나 읽음: `PUT /api/v1/notifications/{id}/read`. `UPDATE ... SET read_at = now() WHERE id = :id AND receiver_id = :me AND read_at IS NULL`. 다른 회원의 알림이거나 보관 기간이 지났으면 `404 NOTIFICATION_NOT_FOUND`다(US2-AC6, 존재 여부도 알리지 않는다). 이미 읽었으면 그대로 204다.
- 모두 읽음: `POST /api/v1/notifications/read-all { upToSeq }`. `WHERE receiver_id = :me AND read_at IS NULL AND seq <= :upToSeq`. `upToSeq`는 웹이 지금까지 받은 가장 큰 번호(목록 첫 항목이나 마지막 SSE `id`)다. 누르는 사이에 온 알림은 번호가 더 커서 안 읽은 채로 남는다(US2-AC4).
- 안 읽은 수: `GET /api/v1/notifications/unread-count`가 `{ count, latestSeq }`를 준다. 부분 인덱스 `(receiver_id) WHERE read_at IS NULL`을 쓴다. "99+" 표시는 웹이 한다.
- 읽음이 바뀌면 커밋 뒤 Redis로 `{memberId}:r`을 발행하고, 그 회원의 모든 연결에 `unread-count` 이벤트(ID 없음, 재전송 대상 아님)를 보낸다. 다른 탭의 배지도 바로 바뀐다. `notification` 이벤트에도 그때의 `unreadCount`를 함께 싣는다.

**근거**: 시각 대신 번호로 자르면 서버와 브라우저 시계 차이나 같은 마이크로초 문제 없이 "누른 시점까지"가 정확해진다. 읽음 변경을 재전송하지 않는 대신, 웹은 다시 연결할 때마다 안 읽은 수를 새로 받는다.

## R12. 마이페이지 목록과 감정 통계 (FR-011, FR-012)

**결정**:
- **내가 쓴 글** `GET /api/v1/members/me/posts`: `PostApi.pageByAuthor(authorId, cursor, size)`로 키셋(`id DESC`)을 읽고, 피드와 같은 조합기(`FeedAssembler`)로 몬스터, 감정, 작성자를 붙인다. 응답 항목은 피드의 `FeedItem`을 그대로 쓴다. 인덱스 `posts (author_id, id DESC) WHERE deleted_at IS NULL`.
- **내 댓글** `GET /api/v1/members/me/comments`: `PostApi.pageCommentsByAuthor`가 살아 있는 글의 살아 있는 댓글을 `id DESC` 키셋으로 읽고, 글 본문 앞 50글자를 함께 준다. 인덱스 `comments (author_id, id DESC) WHERE deleted_at IS NULL`.
- **공감한 글** `GET /api/v1/members/me/liked-posts`: `PostApi.pageLikedBy(memberId, cursor, size)`가 `post_likes`를 `(created_at DESC, post_id DESC)` 키셋으로 읽고 지운 글을 뺀다. 취소한 공감은 행이 없으니 자연히 빠진다. 조합은 내 글과 같다. 인덱스 `post_likes (member_id, created_at DESC, post_id DESC)`.
- 세 목록 모두 쿼리 수가 쪽 크기와 상관없이 4~5개다(003 R7과 같다).
- **감정 통계** `GET /api/v1/members/me/emotion-stats`:
  1. `PostApi.liveRefsByAuthor(authorId)`가 내 살아 있는 글의 `(postId, createdAt)`을 준다.
  2. `MonsterApi.statRows(postIds)`가 그 글들의 `(postId, emotion, status, createdAt)`을 준다. 몬스터가 없는(분석 중) 글은 빠진다.
  3. `feed`가 메모리에서 센다: 전체 수, 처치된 수, 감정 5종별 수.
  4. 비율은 정수 퍼센트로 반올림하고, 합이 100이 아니면 차이를 가장 큰 항목에 더하거나 뺀다. 가장 큰 항목이 여럿이면 아래 "가장 많은 감정"과 같은 규칙으로 하나를 고른다. 몬스터가 없으면 모두 0이다(US4-AC4).
  5. 가장 많은 감정은 수가 가장 큰 감정이고, 같으면 그 감정들 가운데 가장 최근에 생긴 몬스터(`createdAt`)의 감정이다(US4-AC2).
  6. 주별 추이는 글의 작성 시각을 한국 시간 월요일 0시 기준 주로 묶은 8개 구간(이번 주 포함, 오래된 주부터)이고, 글이 없는 주도 0으로 채운다(US4-AC3). 시계는 주입한 `Clock`을 쓴다.
  7. 함께 물리친 몬스터는 `MonsterApi.defeatedPostIdsDamagedBy(memberId)`로 내가 HP를 줄인 처치된 몬스터의 글 ID를 받고, `PostApi.liveIds(ids)`로 지운 글을 뺀 수다(US4-AC5). 인덱스 `monster_hp_log (member_id, monster_id)`.

**근거**:
- 회원 한 명의 글이 1천 개여도 행 1천 개를 메모리에서 세는 것은 수 밀리초다(SC-004). SQL 집계로 내리려면 `posts`와 `monsters`를 조인해야 하는데 두 테이블의 소유 모듈이 달라 경계를 넘는다.
- 주는 몬스터가 아니라 글의 작성 시각으로 묶는다. 스펙의 독립 테스트가 "글을 여러 주에 걸쳐 만들고"이고, 분석이 늦어진 몬스터도 쓴 주에 들어가야 자연스럽다.

**검토한 대안**:
- 통계 테이블을 이벤트로 미리 쌓기(조회 모델): 글 삭제, 처치, 기본 몬스터 전환을 모두 따라가야 하고 지금 규모에서는 이득이 없다. 주간 리포트(M7)에서 다시 본다.

## R13. 프로필 수정 (FR-013, US5)

**결정**: `PATCH /api/v1/members/me`에 `{ nickname?, jobRole?, careerYear? }`(하나 이상)를 받는다.
- `member`의 `ProfileService`가 회원 행을 `FOR UPDATE`로 잠그고, 닉네임은 M1의 `Nickname.of`로 검증한다. 소문자 키가 내 지금 키와 같으면(대소문자만 바꾼 경우) 중복 확인을 건너뛰고, 다르면 `existsByNicknameKey`로 확인한다. 저장 때 유일 제약(`member.nickname_key`)에 걸리면 `409 NICKNAME_TAKEN`으로 바꾼다. 동시에 같은 닉네임을 저장하면 한 명만 성공한다(경계 상황).
- 형식 오류는 M1과 같은 메시지의 `400 INVALID_REQUEST`다. 웹 폼은 M1의 닉네임 스키마와 `nickname-availability` 확인을 그대로 쓴다(US5-AC2).
- 응답은 `MemberProfile`이다. access 토큰에는 닉네임이 없으므로 새로 발급하지 않는다.
- 글의 직군과 경력은 작성 시점 스냅숏(`posts.author_job_role`, `author_career_year`)이라 바꾸지 않는다(US5-AC3). 003 R7에 적은 "`MemberProfileChanged`로 스냅숏 갱신"은 이 스펙에서 뒤집혔으므로 이벤트를 만들지 않는다.
- 닉네임은 어디서나 `MemberApi.getMembers`로 지금 값을 읽는다. 알림도 닉네임을 저장하지 않고 `actor_id`만 둔다(US5-AC4).
- `/api/v1/members/me`는 온보딩 전 허용 목록(`SecurityPaths.ONBOARDING_ALLOWED`)에 경로로 들어 있어 `PATCH`도 통과한다. 온보딩을 건너뛰는 길이 되지 않도록 허용 목록을 `GET`에만 맞추고, 서비스도 온보딩 전이면 `403 ONBOARDING_REQUIRED`로 막는다.

## R14. 웹: 실시간 연결과 화면

**결정**:
- **연결 상태**: `features/notification-stream`에 Zustand 스토어(`status: idle | connecting | open | retrying | stopped`, `lastEventId`, `attempt`)를 둔다(overview 6.4).
- **연결 절차**: BFF에서 티켓을 받고 `EventSource`를 연다. `error`가 오면 바로 닫고, 1초, 2초, 4초... 최대 30초에 ±20% 흔들기를 더해 기다린 뒤 새 티켓으로 다시 연다. 열리면 시도 횟수를 0으로 되돌린다. 티켓 발급이 401(세션 끝)이면 `stopped`로 두고 다시 시도하지 않는다. 탭이 다시 보이거나(`visibilitychange`) 네트워크가 돌아오면(`online`) 기다리지 않고 바로 붙는다. 서버가 15분마다 닫는 것도 같은 경로로 다시 붙는다. 구현하며 세 가지를 더했다(Batch 5 리뷰). 시도 횟수는 20초 동안 열려 있어야 0으로 되돌린다(탭이 6개 이상일 때 서로 밀어내는 것을 막는다). 서버가 25초마다 보내는 `ping` 이벤트를 비롯해 60초 동안 이벤트가 없거나, 탭이 30초 넘게 가려졌거나 오프라인이었다 돌아오면 열린 연결도 닫고 다시 붙는다. `stopped`에서는 탭이 보이거나 초점을 받을 때 한 번만 붙어 본다.
- **어디서 여는가**: `app/layout.tsx`(서버 컴포넌트)가 `ogu_ob` 쿠키가 있을 때만 `widgets/notification-bell`을 렌더링하고, 이 위젯이 연결을 연다. 로그아웃 성공 때 스토어가 연결을 닫는다.
- **캐시 반영**: `notification` 이벤트를 받으면 TanStack Query의 알림 목록 첫 쪽에서 같은 ID를 지우고 맨 앞에 넣고, 안 읽은 수 캐시를 `unreadCount`로 바꾼다. `unread-count` 이벤트는 배지만 바꾼다. 다시 연결하면 안 읽은 수를 새로 받는다.
- **토스트**: 화면 오른쪽 아래에 4초 동안 보인다. 알림 페이지를 보고 있으면 띄우지 않는다. 토스트를 누르면 목록에서 누른 것과 같이 그 알림을 읽음으로 바꾸고 글 상세로 간다(Batch 7). `shared/ui/toast`를 직접 만든다(의존성을 늘리지 않는다).
- **화면**: `/notifications`(목록, 모두 읽음, 무한 스크롤), `/my`(프로필과 감정 통계, 탭 `?tab=posts|comments|likes`), `/my/edit`(프로필 수정). `route-guard.ts`의 보호 경로에 `/notifications`를 더한다(FR-014).
- **슬라이스**: `entities/notification`(타입, 목록과 안 읽은 수 쿼리, 알림 문구 함수, 항목 UI), `entities/emotion-stats`(쿼리, 통계 UI), `features/notification-stream`, `features/read-notification`, `features/edit-profile`, `widgets/notification-bell`, `widgets/notification-list`, `widgets/my-activity`(세 탭), `widgets/emotion-stats-panel`.
- **삭제된 글**: 목록 항목의 `post`가 `null`이면 "삭제된 글"로 보이고, 누르면 이동하지 않고 안내를 띄운다. 이미 연 상세가 `404 POST_NOT_FOUND`를 받아도 같은 안내를 쓴다(US2-AC5).

**근거**: 서버 데이터(목록, 수)는 TanStack Query, 연결 상태만 Zustand라는 overview 6.4의 규칙을 그대로 따른다. 연결과 재시도를 직접 다루는 이유는 R3(일회용 티켓)에 있다.

## R15. 테스트 전략

**결정**:
- **API 모듈 테스트**: `@ApplicationModuleTest`로 `notification` 리스너를 검증한다. 답글 하나로 글쓴이와 댓글 주인이 각각 하나씩 받는지, 같은 이벤트를 두 번 보내도 알림이 하나인지, 처치에서 공격자 중복이 없는지를 본다.
- **SSE 통합 테스트**: `@SpringBootTest(webEnvironment = RANDOM_PORT)`와 JDK `HttpClient`(`BodyHandlers.ofLines()`)로 실제 스트림을 읽는다. 티켓 재사용과 만료(시계 주입), `lastEventId` 재전송, 같은 회원 연결 두 개에 모두 오는지, 하트비트를 확인한다.
- **두 인스턴스와 재시작(SC-003)**: 같은 Testcontainers Postgres와 Redis(`redis:7.4-alpine`)에 애플리케이션 컨텍스트 두 개를 다른 포트로 띄운다. A에 붙어 있는 동안 B에서 알림을 만들면 A로 오는지, A를 닫은 뒤 B에 마지막 번호로 붙으면 빠짐도 중복도 없는지 본다.
- **Redis 장애**: 연결된 상태에서 Redis 컨테이너를 멈추고 알림을 만든다. 행동 API와 알림 리스너가 성공하고 알림이 DB와 목록에 있는지, 열린 스트림이 줄어든 안전망 주기(테스트에서는 1초) 안에 그 알림을 받는지, Redis를 다시 띄우면 구독이 돌아와 주기가 원래대로 바뀌는지 본다. 끊었다 다시 붙은 연결은 Redis 없이도 `lastEventId` 이후를 빠짐없이 받는다.
- **동시성(SC-005)**: 서로 다른 회원 100명이 같은 글에 동시에 공감하면 안 읽은 묶음이 하나이고 `actor_count`가 100인지, 번호가 겹치지 않는지 본다. 처치 알림과 공감 알림이 같은 회원에게 동시에 만들어져도 교착이 없는지 본다.
- **통계**: 시계를 주입해 8주 경계(한국 시간 월요일 0시 직전과 직후), 반올림 보정, 동률 규칙을 단위 테스트한다.
- **계약**: 003과 같이 `ContractTests`와 `generated.ts` 드리프트 검사를 쓴다. 스트림 경로는 응답 형식만 비교하고 이벤트 형식은 SSE 통합 테스트가 맡는다.
- **웹**: 재시도 간격 계산, 캐시 반영(같은 ID를 맨 위로), 배지 "99+", 알림 문구, 통계 막대를 Vitest로 검증한다. `EventSource`는 가짜 구현으로 바꿔 끼운다.
- **E2E(`e2e-full`)**: 브라우저 컨텍스트 둘(A, B)로 B의 댓글과 공감이 A 화면에 새로고침 없이 뜨는지, A를 `context.setOffline(true)`로 끊은 동안의 알림이 다시 붙은 뒤 한 번씩 오는지, 탭 두 개에 모두 오는지 본다.
