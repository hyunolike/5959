---

description: "Task list for 004-notification-mypage (알림과 마이페이지)"
---

# Tasks: 알림과 마이페이지

**Input**: Design documents from `/specs/004-notification-mypage/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/notification-mypage.openapi.yaml, quickstart.md

**Tests**: 포함한다. constitution III에 따라 스토리마다 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다(예: `` `US1-AC6 ...` ``). ID가 없는 보조 테스트는 한국어 설명만 쓴다. 인수 조건은 27개다(US1 8, US2 6, US3 4, US4 5, US5 4).

**Organization**: 사용자 스토리별로 묶었다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 미완료 작업에 의존하지 않아 병렬로 할 수 있다
- **[Story]**: 해당 사용자 스토리(US1~US5)

## Path Conventions

- API: `apps/api/src/main/kotlin/com/ogu/`, 테스트 `apps/api/src/test/kotlin/com/ogu/`, 리소스 `apps/api/src/main/resources/`
- 웹: `apps/web/src/`, 전체 흐름 E2E `apps/web/e2e-full/`
- 계약: 저장소 루트 `contracts/openapi.yaml`
- 인프라: `infra/`, 로컬 개발 compose `apps/api/compose.yaml`

---

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 `specs/004-notification-mypage/contracts/notification-mypage.openapi.yaml`의 tags, paths, components(parameters, schemas, responses)를 루트 contracts/openapi.yaml에 합친다. 이름이 같은 스키마와 응답(`ErrorResponse`, `ErrorEnvelope`, `JobRole`, `CareerYear`, `AuthMethod`, `MemberProfile`, `EmotionType`, `AnalysisStatus`, `MonsterView`, `Author`, `FeedItem`, `FeedPage`, 파라미터 `Cursor`, `Size`, 응답 `BadRequest`, `Unauthorized`, `Forbidden`)는 루트 정의 하나만 남기고, 둘이 다르면 루트를 기준으로 맞춘 뒤 차이를 커밋 메시지에 적는다. `/api/v1/members/me`는 루트에 이미 있으므로 기존 경로 항목에 `patch`(`updateMyProfile`)만 더한다. `info.version`을 `0.4.0`으로 올린다. `npx @redocly/cli lint contracts/openapi.yaml`이 오류 없이 통과해야 한다(경고는 003과 같은 `info-license`, `localhost` 서버만 허용)
- [x] T002 `pnpm --filter web gen:api`로 apps/web/src/shared/api/generated.ts를 다시 만들어 커밋한다. apps/api/src/test/kotlin/com/ogu/ContractTests.kt의 `pendingPaths`에 이번에 추가된 연산 11개(`getNotifications`, `markNotificationRead`, `markAllNotificationsRead`, `getUnreadNotificationCount`, `issueStreamTicket`, `streamNotifications`, `updateMyProfile`, `getMyPosts`, `getMyComments`, `getMyLikedPosts`, `getMyEmotionStats`)를 넣어 지금은 통과하게 하고 KDoc에 004 문장을 더한다. 스트림 경로는 응답 형식(`text/event-stream`)만 비교한다는 점도 KDoc에 적는다(research R15)
- [x] T003 [P] apps/api/build.gradle.kts에 `spring-boot-starter-data-redis`(Lettuce)와 Testcontainers Redis 의존성을 더한다. apps/api/src/test/kotlin/com/ogu/TestcontainersConfiguration.kt에 `redis:7.4-alpine` 컨테이너를 `@ServiceConnection`으로 붙인다. `./gradlew build`가 통과해야 한다
- [x] T004 [P] apps/api/src/main/resources/application.yml에 `spring.data.redis.url`(`${REDIS_URL:redis://localhost:6379}`), 명령 타임아웃 `1s`를 넣는다. `management.health`에서 Redis 지표를 기본 그룹에서 빼고 `redis` 그룹으로 따로 둔다(배포 롤백 기준에서 제외, research R5). `ogu.notification` 블록: `heartbeat: 25s`, `connection-lifetime: 15m`, `max-connections-per-member: 5`, `retention: 90d`, `safety-drain-interval: 60s`, `outage-drain-interval: 5s`, `purge-cron: "0 0 4 * * *"`(Asia/Seoul), `purge-batch-size: 1000`, `writer-threads: 4`. `ogu.stream-ticket.ttl: 30s`, `ogu.sse.allowed-origins: ${OGU_SSE_ALLOWED_ORIGINS:}`. apps/api/src/main/kotlin/com/ogu/member/infrastructure/config/ProdAuthSettingsCheck.kt가 prod에서 `REDIS_URL` 환경 변수가 없거나 호스트가 localhost이면, 또는 `ogu.sse.allowed-origins`가 비면 기동을 실패시키게 하고 ProdAuthSettingsCheckTest에 두 사례를 더한다
- [x] T005 [P] 인프라: infra/compose.prod.yaml에 quickstart 운영 준비 1번의 `redis` 서비스(`redis:7.4-alpine`, `--requirepass ${REDIS_PASSWORD}`, `--maxmemory 64mb --maxmemory-policy noeviction`, `--save "" --appendonly no`, `mem_limit: 128m`, 헬스 체크, 포트 공개 없음)를 그대로 넣고, `api`에 `depends_on: redis: condition: service_started`(Redis 장애가 API 기동을 막지 않게), `REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379`, `OGU_SSE_ALLOWED_ORIGINS`를 더한다. infra/.env.example에 `REDIS_PASSWORD`, `OGU_SSE_ALLOWED_ORIGINS`를 적는다. infra/compose.e2e.yaml과 apps/api/compose.yaml에 비밀번호 없는 같은 서비스를 넣는다. infra/Caddyfile의 API 사이트에서 `@compressible not path /api/v1/notifications/stream`과 `encode @compressible zstd gzip`으로 스트림을 압축에서 뺀다. infra/tests/deploy_test.sh에 검사를 더한다: prod compose에 `redis` 서비스가 있고 `ports`가 없으며 `--save ""`와 `--appendonly no`를 쓰는지, `api`가 `redis`에 `service_started`로 의존하는지(`service_healthy`가 아닌지), Caddyfile이 스트림 경로를 `encode`에서 빼는지. `bash infra/tests/deploy_test.sh`가 통과해야 한다
- [x] T006 [P] CI 환경: .github/workflows/ci.yml의 Docker 이미지 스모크 테스트가 `ogu-ci` 네트워크에 `redis:7.4-alpine` 컨테이너를 같이 띄우고 API에 `REDIS_URL`과 가짜 `OGU_SSE_ALLOWED_ORIGINS`를 넘기며, 정리 단계에서 그 컨테이너도 지운다. e2e-full 잡의 웹 환경에 `SSE_PUBLIC_ORIGIN`을 넘긴다(브라우저가 붙을 compose.e2e API 주소). apps/web/.env.example에 `SSE_PUBLIC_ORIGIN`(비우면 `API_ORIGIN`)을 적는다

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: 이 단계가 끝나야 스토리 작업을 시작한다

- [x] T007 apps/api/src/main/resources/db/migration/V4__notification_mypage.sql을 data-model.md 그대로 작성한다(아래는 확인할 점이고, 컬럼은 data-model 표의 모든 컬럼을 만든다). 제약에는 모두 이름을 붙이고 모듈 경계를 넘는 참조에는 FK를 두지 않는다. `WHERE`가 붙은 유일 조건은 제약이 아니라 `CREATE UNIQUE INDEX`로 만들고, 그 인덱스를 쓰는 `ON CONFLICT`는 같은 조건식을 되풀이한다.
  - `notification`: `actor_count int NOT NULL DEFAULT 1, CHECK ≥ 1`, `dedup_key varchar(40) NULL`, `seq bigint NOT NULL`, `read_at timestamptz NULL`, `created_at`, `updated_at`. 제약 `notification_receiver_seq_key UNIQUE (receiver_id, seq)`, `notification_receiver_dedup_key UNIQUE (receiver_id, dedup_key)`, `notification_unread_like_group_key UNIQUE (receiver_id, post_id) WHERE type = 'POST_LIKE' AND read_at IS NULL`, `notification_unread_idx (receiver_id) WHERE read_at IS NULL`, `notification_created_at_idx (created_at)`, 체크 `type`은 7종, `type = 'POST_LIKE' OR actor_count = 1`, `type <> 'POST_LIKE' OR dedup_key IS NULL`
  - `notification_sequence`: `member_id bigint PK`, `last_seq bigint NOT NULL, CHECK ≥ 0`
  - `like_notification_participant`: PK `(post_id, liker_id)`, `notification_id bigint NOT NULL, FK → notification.id ON DELETE CASCADE`, 인덱스 `(notification_id)`
  - `sse_ticket`: `token_hash char(64) PK`, `member_id bigint NOT NULL, FK → member.id`, `session_id uuid NOT NULL`, `expires_at`, `used_at NULL`, 체크 `expires_at > created_at`, 인덱스 `(expires_at)`
  - 인덱스 4개: `posts_author_live_idx ON posts (author_id, id DESC) WHERE deleted_at IS NULL`, `comments_author_live_idx ON comments (author_id, id DESC) WHERE deleted_at IS NULL`, `post_likes_member_created_idx ON post_likes (member_id, created_at DESC, post_id DESC)`, `monster_hp_log_member_monster_idx ON monster_hp_log (member_id, monster_id)`
- [x] T008 apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt에 V4 테스트를 추가한다: 4개 테이블과 인덱스 4개 존재, 같은 받는 사람과 글에 안 읽은 `POST_LIKE` 두 번째 삽입을 `notification_unread_like_group_key`가 거부하고 첫 행을 읽은 뒤에는 허용, 같은 `(receiver_id, dedup_key)` 두 번째 삽입 거부, `POST_COMMENT`인데 `actor_count = 2` 거부, `POST_LIKE`인데 `dedup_key`가 있으면 거부, 7종 밖의 `type` 거부, 알림을 지우면 참여자 행이 CASCADE로 지워짐, 참여자 행의 `notification_id`가 NULL이면 거부, `sse_ticket`의 `expires_at <= created_at` 거부
- [x] T009 모듈 뼈대와 공개 타입을 만든다. `notification` 모듈(apps/api/src/main/java/com/ogu/notification/package-info.java에 `allowedDependencies = {"shared", "post", "monster", "member"}`, 패키지 `domain`, `application`, `stream`, `presentation`), `NotificationType`(7종). `monster` 루트에 `MonsterSpawned(postId, monsterId, defaulted)`, `MonsterStatRow(postId, emotion, status, createdAt)`. `post` 루트에 `CommentSummary(postId, authorId, parentId, parentAuthorId)`, `PostPreview(postId, contentPreview, deleted)`, `PostRef(postId, createdAt)`, `MyCommentPage`. `member` 루트에 `StreamTicket(ticket, expiresAt)`. 파사드 메서드는 처음 쓰는 스토리에서 추가한다. `ModularityTests`가 통과하고 의존 방향이 data-model.md "모듈 의존 그래프"와 같아야 한다. `notification`이 `emotion`을 모른다는 단언을 ModularityTests에 더한다
- [x] T010 [P] apps/api/src/main/kotlin/com/ogu/shared/error/ErrorCode.kt에 `NOTIFICATION_NOT_FOUND`(404), `STREAM_TICKET_INVALID`(401)를 추가한다
- [x] T011 Redis 배선(research R5): apps/api/src/main/kotlin/com/ogu/notification/stream/NotificationSignal.kt(채널 `ogu:notification`, 형식 `{memberId}:n:{seq}`와 `{memberId}:r`의 직렬화와 해석), RedisSignalPublisher(`TransactionSynchronization.afterCommit`에 발행을 예약, 트랜잭션 밖이면 바로 발행, 실패하면 WARN만 남기고 예외를 던지지 않음), `RedisMessageListenerContainer` 설정(복구 주기 5초). Lettuce `disconnectedBehavior=REJECT_COMMANDS`로 Redis 장애 중 발행이 타임아웃까지 기다리지 않고 바로 실패하게 한다. 테스트: 신호 형식 왕복, 커밋 전에는 발행하지 않고 롤백되면 발행하지 않음, Redis가 멈춘 상태에서 발행해도 호출한 쪽이 성공함
- [x] T012 notification 도메인: apps/api/src/main/kotlin/com/ogu/notification/domain/(Notification, NotificationSequence, LikeParticipant와 리포지토리). 보관 기간 조건 `created_at >= now() − 90일`을 주입한 `Clock`과 `ogu.notification.retention`으로 만드는 헬퍼 하나를 두고 목록, 안 읽은 수, 재전송, 읽음 처리가 모두 이것을 쓰게 한다. 헬퍼 경계 테스트(89일 23:59:59는 포함, 90일은 제외)
- [x] T013 [P] plan의 우려 수정(research R13): apps/api/src/main/kotlin/com/ogu/member/infrastructure/security/SecurityPaths.kt의 `ONBOARDING_ALLOWED`에서 `/api/v1/members/me`를 `GET`에만 맞춘다(메서드를 함께 보는 매처). 테스트: 온보딩 전 회원의 `GET /api/v1/members/me`는 200, `PATCH /api/v1/members/me`는 403 `ONBOARDING_REQUIRED`, 닉네임 확인과 온보딩, 로그아웃은 그대로 통과
- [x] T016 [P] apps/web/src/shared/ui/toast.tsx와 테스트: 화면 오른쪽 아래, 4초 뒤 사라짐, 여러 개가 쌓이면 최신이 위, 누르면 콜백 실행. 새 의존성 없이 만들고 shared/ui/index.ts로 내보낸다

**Checkpoint**: 스키마, 모듈 경계, Redis 배선, 공통 타입 준비 완료(웹 엔티티 슬라이스는 처음 쓰는 스토리에서 만든다)

---

## Phase 3: User Story 1 - 내 글에 생긴 일을 바로 알게 된다 (Priority: P1) 🎯 MVP

**Goal**: 댓글, 답글, 공감, 몬스터 생성과 처치가 알림으로 만들어지고, 열린 화면에 SSE로 바로 온다. 끊겼다 다시 붙어도, 서버가 두 대여도, Redis가 내려가도 빠짐과 중복이 없다

**Independent Test**: quickstart 시나리오 1~9, "동시성과 재전송 확인"

### Tests for User Story 1 ⚠️

- [ ] T017 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/CommentNotificationTests.kt(`@ApplicationModuleTest`, `Scenario` DSL): `US1-AC1 다른 회원이 댓글을 달면 글쓴이에게 POST_COMMENT 하나`, `US1-AC1 다른 회원의 답글은 글쓴이에게 POST_REPLY, 원 댓글 주인에게 COMMENT_REPLY를 하나씩 만든다`, `US1-AC5 글쓴이가 자기 글에 댓글이나 답글을 달면 글쓴이에게 알림이 없다`, 원 댓글 주인이 글쓴이면 한 답글로 그 회원에게 알림이 하나뿐, 답글 작성자가 원 댓글 주인이면 댓글 주인 알림 없음, 한 사람이 댓글을 여러 개 달면 댓글마다 알림, 지운 글이나 지운 댓글이면 만들지 않음, 같은 `CommentCreated`를 두 번 보내도 알림 하나이고 신호도 한 번
- [ ] T018 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/LikeNotificationTests.kt: `US1-AC2 B와 C가 차례로 공감하면 안 읽은 공감 알림 하나에 actor_count 2, latest_actor는 C이고 seq가 새 번호로 오른다`, `US1-AC2 공감 취소 후 다시 공감해도 알림과 숫자가 그대로다`, `US1-AC2 묶음을 읽은 뒤 온 공감은 새 묶음을 만들고 이미 센 회원은 다시 세지 않는다`, 댓글 공감은 알림을 만들지 않음, 지운 글 공감은 만들지 않음
- [ ] T019 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/MonsterNotificationTests.kt: `US1-AC3 몬스터가 생기면 글쓴이에게 MONSTER_SPAWNED`(`defaulted=true`인 기본 몬스터 포함), `US1-AC4 처치되면 글쓴이는 MONSTER_DEFEATED, HP를 줄인 회원은 MONSTER_DEFEATED_TOGETHER를 한 번씩 받는다`(한 회원이 공감과 댓글로 기록 행이 여럿이어도 하나, 처치 뒤 남긴 응원 `hp_before = hp_after = 0`만 있는 회원은 제외), `US1-AC4 마지막 공격으로 HP를 0으로 만든 회원도 MONSTER_DEFEATED_TOGETHER를 받는다`, 소급 반영으로 처치된 채 생기면 `MonsterDefeated`가 먼저 처리돼도 글쓴이의 seq는 "나타났어요" 다음 "처치됐어요" 순서(research R8), 뒤늦게 온 `MonsterSpawned`는 아무것도 바꾸지 않음
- [ ] T020 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/NotificationFailureIsolationTests.kt(FR-003): 알림 리스너가 예외를 던져도 댓글, 공감 API는 성공하고 몬스터 HP도 반영된다, 미완료 이벤트 발행이 남고 `EventPublicationResubmitter`로 다시 보내면 알림이 하나 생긴다
- [ ] T021 [P] [US1] apps/api/src/test/kotlin/com/ogu/member/StreamTicketApiTests.kt: 티켓 발급은 `201`과 `StreamTicket`, `US1-AC8 로그인하지 않으면 티켓 발급은 401`, `US1-AC8 티켓 없이, 이미 쓴 티켓, 30초가 지난 티켓(시계 주입 경계), 세션이 끝난 티켓으로는 스트림이 401 STREAM_TICKET_INVALID`, 온보딩 전 회원은 발급 403, DB에는 SHA-256 해시만 저장, 같은 티켓으로 동시에 두 번 붙으면 한 번만 성공, `SseTicketCleanupJob`이 하루 지난 행만 지움(시계 주입)
- [ ] T022 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/NotificationStreamTests.kt(`RANDOM_PORT`, JDK `HttpClient` `BodyHandlers.ofLines()`): `US1-AC1 열린 스트림에 notification 이벤트가 id=seq와 unreadCount를 싣고 온다`, `US1-AC7 같은 회원의 연결 두 개 모두 받는다`, `US1-AC8 다른 회원의 알림은 오지 않는다`, 응답 헤더 `Content-Type: text/event-stream`, `Cache-Control: no-store`, `X-Accel-Buffering: no`, 하트비트 `: hb`(테스트에서 주기를 줄임), 연결 수명이 지나면 서버가 닫음, 6번째 연결이 오면 가장 오래된 연결이 닫힘, CORS는 `OGU_SSE_ALLOWED_ORIGINS` 출처만 허용하고 자격 증명 헤더 없음
- [ ] T023 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/NotificationStreamReplayTest.kt(SC-002): `US1-AC6 끊긴 동안 알림 50건(공감 묶음 갱신 포함)을 만들고 마지막 번호로 다시 붙으면 받은 알림 ID 집합이 DB 구간과 같고 같은 seq가 두 번 오지 않으며 seq 오름차순이다`, 묶음은 마지막 상태로 한 번만 옴, `Last-Event-ID` 헤더가 쿼리 `lastEventId`보다 우선, `lastEventId` 없이 붙으면 지금의 `last_seq` 이후만 받음, 90일이 지난 알림은 재전송하지 않음
- [ ] T024 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/NotificationConcurrencyTest.kt(SC-005): `US1-AC2 서로 다른 회원 100명이 같은 글에 동시에 공감하면 안 읽은 공감 묶음이 하나이고 actor_count가 100이다`, 회원의 seq가 겹치지 않고 커밋 순서와 같다, 처치 알림(여러 받는 사람)과 공감 알림이 같은 회원에게 동시에 만들어져도 교착이 없다(회원 ID 오름차순 잠금). M1 회고대로 `pg_stat_activity`를 읽을 때 `pg_stat_clear_snapshot()`을 먼저 부른다
- [ ] T025 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/TwoInstanceStreamTest.kt(SC-003): 같은 Testcontainers Postgres와 Redis에 컨텍스트 두 개(A, B)를 다른 포트로 띄운다. `US1-AC7 A에 붙은 회원에게 B에서 만든 알림이 온다`, `US1-AC6 A를 닫고 B에 마지막 번호로 붙으면 그 사이 알림이 빠짐없이 중복 없이 온다`
- [ ] T026 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/RedisOutageStreamTest.kt: Redis 컨테이너를 멈춘 상태에서 공감과 댓글 API가 성공하고 알림이 DB와 목록에 있다, 열린 스트림이 줄어든 안전망 주기(테스트 1초) 안에 그 알림을 받는다, Redis를 다시 띄우면 구독이 돌아오고 주기가 원래대로 바뀐다, `US1-AC6 Redis 없이 다시 붙은 연결도 lastEventId 이후를 빠짐없이 받는다`, `/actuator/health`는 Redis가 내려가도 UP, Redis가 내려간 상태에서도 애플리케이션이 기동하고 구독은 복구 주기로 다시 시도한다
- [ ] T027 [P] [US1] 웹 단위 테스트: apps/web/src/features/notification-stream/model/backoff.test.ts(1초, 2초, 4초... 최대 30초, ±20% 흔들기, 열리면 0으로), model/store.test.ts(`idle → connecting → open → retrying`, 티켓 401이면 `stopped`이고 다시 시도하지 않음, `visibilitychange`와 `online`이면 기다리지 않고 바로 연결, 로그아웃이면 닫음), api/connect.test.ts(가짜 `EventSource`: `error`면 바로 닫고 새 티켓으로 다시 열기, 주소에는 `ticket`과 `lastEventId`만 있고 access 토큰이 없음, 첫 연결 주소의 `lastEventId`가 unread-count의 `latestSeq`와 같음), model/cache-sync.test.ts(`notification` 이벤트는 목록 첫 쪽에서 같은 ID를 지우고 맨 앞에 넣고 안 읽은 수를 `unreadCount`로 바꿈, `unread-count` 이벤트는 배지만 바꿈), apps/web/src/app/api/notifications/stream-ticket/route.test.ts(쿠키 access 토큰으로 API 호출, 401이면 `callWithSessionRefresh`로 한 번 갱신, `streamUrl`은 `SSE_PUBLIC_ORIGIN`이 없으면 `API_ORIGIN`, 로그인하지 않으면 401), apps/web/src/app/api/[...path]/route.test.ts에 `notifications/stream-tickets` 직접 전달 거부 사례
- [ ] T028 [P] [US1] apps/web/e2e-full/notification.spec.ts(브라우저 컨텍스트 A, B): `US1-AC1`(B의 댓글이 A 화면에 새로고침 없이 토스트와 배지로), `US1-AC2`(B, C 공감이 하나로 묶임), `US1-AC3`(A의 새 글에 "몬스터가 나타났어요"), `US1-AC6`(A를 `context.setOffline(true)`로 끊은 동안 B의 댓글 2개와 공감, 다시 붙은 뒤 한 번씩), `US1-AC7`(A의 탭 두 개 모두), `US1-AC8`(로그아웃 상태에서는 알림 종이 없고 스트림 요청이 나가지 않음)

### Implementation for User Story 1

- [ ] T029 [US1] member 모듈 연결 표(research R3): domain/SseTicket, SseTicketRepository(소비는 `UPDATE sse_ticket SET used_at = now() WHERE token_hash = :h AND used_at IS NULL AND expires_at > now() RETURNING member_id, session_id` 한 문장), application/StreamTicketService(32바이트 난수 base64url, SHA-256 해시 저장, 소비 뒤 세션 유효 확인), SseTicketCleanupJob(하루 지난 행 삭제), `MemberApi.issueStreamTicket(memberId, sessionId)`, `MemberApi.consumeStreamTicket(ticket): Long?`. infrastructure/security/SecurityPaths.kt에 `/api/v1/notifications/stream`을 공개 경로로 더한다(Bearer를 받지 않음)
- [ ] T030 [US1] 파사드와 이벤트: `PostApi.findComment(commentId): CommentSummary?`(지웠으면 null), monster 모듈 application/MonsterFactory가 몬스터 저장 직후 같은 트랜잭션에서 `MonsterSpawned`를 발행(소급 반영으로 처치되면 `MonsterSpawned` 다음 `MonsterDefeated`), `MonsterApi.damagerIds(monsterId)`(`SELECT DISTINCT member_id FROM monster_hp_log WHERE monster_id = :id AND hp_after < hp_before`). 기존 monster 테스트가 그대로 통과해야 한다
- [ ] T031 [US1] notification 모듈 application/NotificationWriter(research R4, R6, R7): 번호는 `INSERT INTO notification_sequence (member_id, last_seq) VALUES (:m, 1) ON CONFLICT (member_id) DO UPDATE SET last_seq = notification_sequence.last_seq + 1 RETURNING last_seq`로 받고, 여러 회원이면 회원 ID 오름차순. 단건 알림은 `INSERT ... ON CONFLICT DO NOTHING`, 들어가지 않으면 신호를 보내지 않음. 공감 묶음은 번호를 받은 뒤 안 읽은 묶음이 있으면 `actor_count + 1`, `latest_actor_id`, `seq`, `updated_at`을 바꾸고 없으면 새로 만든다. 이어서 그 묶음 ID로 참여자를 `ON CONFLICT (post_id, liker_id) DO NOTHING` 삽입하고, 0행이면 트랜잭션 전체를 되돌린다(번호와 묶음 갱신 모두 취소). 쓴 회원마다 커밋 뒤 `{memberId}:n:{seq}` 발행을 예약한다
- [ ] T032 [US1] notification 모듈 application/NotificationEventListener: `PostLiked`, `CommentCreated`, `MonsterSpawned`, `MonsterDefeated`를 `@ApplicationModuleListener`로 받는다. data-model.md "알림 생성 규칙" 1~5를 따른다(글이 지워졌으면 만들지 않음, 행동한 회원과 받는 사람이 같으면 만들지 않음). 글 노출 판단은 `PostApi.find` 하나만 쓴다(M4 숨김이 같은 경로로 알림을 막도록). 댓글 받는 사람은 `PostApi.findComment`로 정하고 `COMMENT:{commentId}` 키를 함께 쓴다. 처치는 글쓴이와 `damagerIds`의 합집합에서 글쓴이를 뺀 회원, 글쓴이의 `SPAWNED:{monsterId}`가 없으면 먼저 만든다
- [ ] T033 [US1] notification 모듈 application/NotificationQueryService.unreadCount(`{ count, latestSeq }`, 부분 인덱스와 보관 기간 조건 사용)와 presentation/NotificationController `GET /api/v1/notifications/unread-count`. ContractTests `pendingPaths`에서 `getUnreadNotificationCount`를 뺀다
- [ ] T034 [US1] notification 모듈 stream: SseHub(회원별 연결 목록, 회원당 5개 상한과 가장 오래된 연결 닫기, 연결별 잠금과 마지막 전송 번호, `seq > 마지막 번호`를 DB에서 읽어 보내는 따라잡기, 전용 실행기 4스레드), StreamHeartbeat(25초 `: hb`, 쓰기 실패면 연결 제거), 연결 수명 15분 뒤 `complete()`, StreamCorsConfig(스트림 경로에만 `ogu.sse.allowed-origins`, 자격 증명 없음). presentation/NotificationStreamController: `POST /api/v1/notifications/stream-tickets`(`MemberApi.issueStreamTicket`), `GET /api/v1/notifications/stream`(티켓 소비, `Last-Event-ID` 헤더 우선 쿼리 `lastEventId`, 재전송 뒤 실시간). ContractTests `pendingPaths`에서 `issueStreamTicket`, `streamNotifications`를 뺀다
- [ ] T035 [US1] notification 모듈 stream: RedisSignalSubscriber(신호를 받으면 그 회원의 이 인스턴스 연결을 따라잡기, 신호 seq까지 이미 보낸 연결은 건너뜀, 구독이 끊기면 안전망 주기를 5초로 줄이고 돌아오면 모든 연결을 한 번 따라잡은 뒤 60초로 되돌림), SafetyDrain(주기마다 연결별 `max(seq)` 확인). 구독 장애 감지는 `RedisMessageListenerContainer`의 `setErrorHandler`/복구 콜백과 5초 주기 `PING` 중 하나로 판정하고, 연속 2회 실패면 outage 모드, 구독 성공 콜백(`SubscriptionListener.onChannelSubscribed`)이면 복구로 본다
- [ ] T014 [US1] 웹 엔티티(T036과 같은 배치에서 만든다. 슬라이스는 처음 쓰는 작업과 함께 들어와 steiger를 우회하지 않는다) apps/web/src/entities/notification/: model/types.ts(generated.ts의 `Notification`, `NotificationType`, `NotificationPage`, `UnreadCount`, `StreamNotificationEvent`, `StreamUnreadCountEvent`), model/message.ts와 테스트(data-model.md 종류 표의 문구 그대로: "닉네임 님 외 N명이 공감했어요"에서 N = actor_count − 1, 0이면 "외 N명" 생략, `MONSTER_DEFEATED`는 "내 몬스터가 처치됐어요", `MONSTER_DEFEATED_TOGETHER`는 "함께 공격한 몬스터가 처치됐어요"), model/badge.ts와 테스트(0이면 숨김, 1~99는 숫자, 100 이상은 "99+"), index.ts
- [ ] T036 [P] [US1] 웹 연결: apps/web/src/features/notification-stream/(model/store.ts Zustand, model/backoff.ts, api/connect.ts, model/cache-sync.ts, index.ts), apps/web/src/app/api/notifications/stream-ticket/route.ts(전용 BFF 라우트, `{ ticket, streamUrl, expiresAt }`), apps/web/src/app/api/[...path]/route.ts에서 `notifications/stream-tickets` 전달 거부. BFF 라우트 계약(`POST /api/notifications/stream-ticket` → `200 { ticket, streamUrl, expiresAt }`, 401)을 specs/002-auth/contracts/bff-routes.md에 더한다. 안 읽은 수 쿼리(`useUnreadCountQuery`)는 apps/web/src/entities/notification/api/queries.ts에 두고 다시 연결할 때마다 새로 받는다. 처음 연결할 때는 안 읽은 수 응답의 `latestSeq`를 `lastEventId`로 넘긴다(목록과 스트림 사이 틈을 막는다)
- [ ] T037 [US1] 웹 알림 종: apps/web/src/widgets/notification-bell/(종, `badge.ts`로 배지, 마운트 때 연결 시작, 새 알림이면 토스트(알림 페이지를 보고 있으면 띄우지 않음. 토스트 문구에는 글 본문이나 댓글 본문을 넣지 않고 종류 문구만 쓴다), 누르면 `/notifications`로 이동), apps/web/src/app/layout.tsx가 `ogu_ob` 쿠키가 있을 때만 위젯을 렌더링한다. 로그아웃 성공 때 스토어가 연결을 닫는다.

**Checkpoint**: 알림이 만들어지고 열린 화면에 바로 온다. 재연결, 두 인스턴스, Redis 장애 테스트가 통과한다 (MVP)

---

## Phase 4: User Story 2 - 알림을 모아 보고 읽음으로 정리한다 (Priority: P1)

**Goal**: 최신순 목록과 이어 불러오기, 하나 읽음, 모두 읽음, 90일 보관

**Independent Test**: quickstart 시나리오 10~15

### Tests for User Story 2 ⚠️

- [ ] T038 [P] [US2] apps/api/src/test/kotlin/com/ogu/notification/NotificationListApiTests.kt: `US2-AC1 최신 20개와 항목 필드(종류, 행동한 회원 닉네임, 묶인 인원 수, 글 앞부분, 시각, 읽음)`, 묶인 공감은 마지막 공감 시각 기준 위치, `US2-AC2 커서로 다음 20개, 중복과 누락 없음`(쪽 사이에 새 알림과 묶음 갱신이 끼어도 같은 알림이 두 번 나오지 않음), `US2-AC5 관련 글이 지워지면 post가 null이고 목록에는 남는다`, 90일이 지난 알림은 목록과 안 읽은 수에서 빠짐(시계 주입), 쿼리 수가 쪽 크기와 상관없이 고정, 온보딩 전 회원은 403 `ONBOARDING_REQUIRED`
- [ ] T039 [P] [US2] apps/api/src/test/kotlin/com/ogu/notification/NotificationReadApiTests.kt: `US2-AC3 하나 읽으면 204이고 안 읽은 수가 하나 준다`(이미 읽었으면 그대로 204), `US2-AC4 모두 읽음은 upToSeq 이하만 읽음으로 바꾸고 그 뒤 번호는 안 읽은 채 남긴다`, `US2-AC6 다른 회원의 알림을 읽음 처리하면 404 NOTIFICATION_NOT_FOUND이고 그 알림은 그대로 안 읽음`, 90일이 지난 알림 읽음은 404, 읽음이 바뀌면 그 회원의 열린 연결 모두에 `unread-count` 이벤트(ID 없음)가 간다, 읽음과 같은 묶음의 새 공감이 동시에 오면 읽음이 먼저면 새 묶음이 생기고 공감이 먼저면 `upToSeq`보다 커서 안 읽음으로 남는다(research R7)
- [ ] T040 [P] [US2] apps/api/src/test/kotlin/com/ogu/notification/NotificationPurgeJobTest.kt: 만든 지 90일이 지난 알림을 1,000행씩 지운다(최근에 갱신된 묶음도 만든 시각 기준), 참여자 행이 CASCADE로 지워진다, 7일이 지난 완료된 이벤트 발행을 지운다, 두 번 실행해도 결과가 같다
- [ ] T041 [P] [US2] 웹 단위 테스트: apps/web/src/features/read-notification/model/*.test.ts(하나 읽음은 목록과 배지를 낙관적으로 바꾸고 실패하면 되돌림, 모두 읽음의 `upToSeq`는 목록 첫 항목, 마지막 SSE `id`, `latestSeq` 가운데 큰 값), apps/web/src/widgets/notification-list/ui/*.test.tsx(`post`가 null이면 "삭제된 글"로 보이고 누르면 이동하지 않고 안내), apps/web/src/shared/server/route-guard.test.ts에 `/notifications` 보호 사례
- [ ] T042 [US2] apps/web/e2e-full/notification.spec.ts에 목록 시나리오를 더한다(T028과 같은 파일): `US2-AC1`, `US2-AC2`(스크롤로 다음 쪽), `US2-AC3`(누르면 상세로 이동하고 배지가 하나 줆), `US2-AC4`(모두 읽음 뒤 배지 사라짐), `US2-AC5`(지운 글의 알림은 "삭제된 글"과 안내)

### Implementation for User Story 2

- [ ] T043 [US2] `PostApi.previews(postIds): Map<Long, PostPreview>`(지운 글도 포함, 앞 50글자, `deleted`). notification 모듈 NotificationQueryService.page(`seq DESC` 키셋, 불투명 커서, 보관 기간 조건, `MemberApi.getMembers`와 `PostApi.previews` 일괄 조회)와 `GET /api/v1/notifications`. ContractTests `pendingPaths`에서 `getNotifications`를 뺀다
- [ ] T044 [US2] notification 모듈 application/NotificationReadService: 하나 읽음 `UPDATE ... SET read_at = now() WHERE id = :id AND receiver_id = :me AND read_at IS NULL`(보관 기간 조건 포함, 없으면 404), 모두 읽음 `WHERE receiver_id = :me AND read_at IS NULL AND seq <= :upToSeq`, 커밋 뒤 `{memberId}:r` 발행, SseHub가 받으면 그 회원의 모든 연결에 `unread-count` 이벤트. `PUT /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all`. ContractTests `pendingPaths`에서 `markNotificationRead`, `markAllNotificationsRead`를 뺀다
- [ ] T045 [US2] notification 모듈 application/NotificationPurgeJob(매일 04:00 한국 시간, 1,000행씩, `CompletedEventPublications.deletePublicationsOlderThan(7일)`). ShedLock 없이 멱등으로 둔다
- [ ] T046 [P] [US2] 웹 목록: apps/web/src/entities/notification/api/queries.ts에 `useNotificationsQuery`(`useInfiniteQuery`), ui/notification-item.tsx(문구, 시각, 읽음 표시, 삭제된 글), apps/web/src/features/read-notification/(mark-one, mark-all), apps/web/src/widgets/notification-list/(무한 스크롤, 모두 읽음 버튼, 빈 목록 안내), apps/web/src/app/notifications/page.tsx, apps/web/src/shared/server/route-guard.ts의 보호 경로에 `/notifications` 추가(FR-014). 알림을 누르면 읽음 처리 뒤 `/post/{id}`로 이동하고, 상세가 `404 POST_NOT_FOUND`를 받아도 같은 안내를 쓴다

**Checkpoint**: 알림 실시간 전달과 목록, 읽음 정리가 함께 동작한다

---

## Phase 5: User Story 3 - 마이페이지에서 내 활동을 다시 본다 (Priority: P2)

**Goal**: 내가 쓴 글, 내 댓글, 공감한 글 세 탭

**Independent Test**: quickstart 시나리오 16~19

### Tests for User Story 3 ⚠️

- [ ] T047 [P] [US3] apps/api/src/test/kotlin/com/ogu/feed/presentation/MyPageApiTests.kt: `US3-AC1 내 글이 최신순 20개씩이고 피드 항목과 같은 필드이며 지운 글은 없다`, `US3-AC2 내 댓글과 답글이 최신순이고 글 앞부분이 붙으며 지운 댓글과 지운 글의 댓글은 없다`, `US3-AC3 공감한 글은 공감 시각 최신순이고 취소한 공감과 지운 글은 없다`(취소 후 다시 공감하면 다시 공감한 시각 자리), `US3-AC4 항목이 없으면 빈 목록과 다음 커서 null`, 세 목록 모두 커서로 다음 쪽에 중복과 누락 없음, 쿼리 수가 쪽 크기와 상관없이 4~5개, 다른 회원의 활동은 섞이지 않음, 온보딩 전 회원은 세 목록 모두 403 `ONBOARDING_REQUIRED`
- [ ] T048 [P] [US3] apps/web/src/widgets/my-activity/ui/*.test.tsx: `US3-AC4 탭이 비면 안내와 "글쓰기", "피드 보기" 버튼`, `?tab=posts|comments|likes`와 탭 전환이 맞물림, 잘못된 값이면 posts
- [ ] T049 [P] [US3] apps/web/e2e-full/mypage.spec.ts: `US3-AC1`, `US3-AC2`(누르면 그 글로 이동), `US3-AC3`, `US3-AC4`(새 회원)

### Implementation for User Story 3

- [ ] T050 [US3] post 모듈 application/MyPostsReader, MyCommentsReader, LikedPostsReader(키셋)와 `PostApi.pageByAuthor(authorId, cursor, size)`(`id DESC`, 인덱스 `posts_author_live_idx`), `PostApi.pageCommentsByAuthor(authorId, cursor, size): MyCommentPage`(살아 있는 글의 살아 있는 댓글, `id DESC`, `isReply`, 글 본문 앞부분), `PostApi.pageLikedBy(memberId, cursor, size)`(`(created_at DESC, post_id DESC)`, 지운 글 제외)
- [ ] T051 [US3] feed 모듈: 피드 조합을 application/FeedAssembler로 꺼내 피드와 마이페이지가 함께 쓴다(기존 FeedApiTests가 그대로 통과), application/MyPageQuery, presentation/MyPageController `GET /api/v1/members/me/posts`, `/comments`, `/liked-posts`. ContractTests `pendingPaths`에서 `getMyPosts`, `getMyComments`, `getMyLikedPosts`를 뺀다
- [ ] T052 [P] [US3] 웹: apps/web/src/entities/post/api/(use-my-posts-query.ts, use-liked-posts-query.ts), apps/web/src/entities/comment/api/use-my-comments-query.ts와 ui/my-comment-item.tsx, apps/web/src/widgets/my-activity/(세 탭, 피드 카드 재사용, 무한 스크롤, 빈 상태), apps/web/src/app/my/page.tsx를 프로필, 감정 통계 자리, 탭 구성으로 바꾼다(로그아웃 버튼은 유지)

**Checkpoint**: 마이페이지 세 목록이 단독으로 동작한다

---

## Phase 6: User Story 4 - 내 감정의 흐름을 숫자로 본다 (Priority: P2)

**Goal**: 몬스터 수, 처치 수, 감정 분포, 가장 많은 감정, 8주 추이, 함께 물리친 몬스터

**Independent Test**: quickstart 시나리오 20~24

### Tests for User Story 4 ⚠️

- [ ] T053 [P] [US4] apps/api/src/test/kotlin/com/ogu/feed/application/EmotionStatsQueryTest.kt(시계 주입 단위 테스트): `US4-AC1 비율은 정수 %이고 합이 100이며 반올림 오차는 가장 큰 항목에서 맞춘다`(1:1:1 → 34/33/33, 가장 큰 항목이 여럿이면 가장 많은 감정 규칙으로 고름), `US4-AC2 가장 많은 감정이 같으면 그 가운데 가장 최근 몬스터의 감정`, `US4-AC3 8주는 한국 시간 월요일 0시 시작이고 오래된 주부터이며 빈 주는 0`(일요일 23:59:59 KST와 월요일 00:00:00 KST 경계, UTC로는 일요일 15:00), `US4-AC4 몬스터가 없으면 모두 0이고 가장 많은 감정은 null`
- [ ] T054 [P] [US4] apps/api/src/test/kotlin/com/ogu/feed/presentation/EmotionStatsApiTests.kt: `US4-AC1 전체와 처치된 몬스터 수, 감정 5종 수와 비율`(지운 글과 분석 중인 글은 세지 않음), `US4-AC3 주별 추이는 글 작성 시각 기준`(분석이 늦어진 몬스터도 쓴 주에 듦), `US4-AC4 몬스터가 없는 회원은 숫자가 0`, `US4-AC5 내가 HP를 줄인 다른 사람의 처치된 몬스터 수가 함께 물리친 몬스터다`(처치 뒤 응원만 한 몬스터와 지운 글은 빼고, 한 몬스터에 기록이 여럿이어도 하나), 온보딩 전 회원은 403 `ONBOARDING_REQUIRED`
- [ ] T055 [P] [US4] 웹 단위 테스트: apps/web/src/entities/emotion-stats/ui/distribution-bar.test.tsx(5종 고정 순서, 비율 표시), ui/weekly-chart.test.tsx(8개 막대, 빈 주는 높이 0), apps/web/src/widgets/emotion-stats-panel/ui/*.test.tsx(`US4-AC4 몬스터가 없으면 "아직 몬스터가 없어요"와 글쓰기 안내`, `US4-AC5 함께 물리친 몬스터 수 표시`)
- [ ] T056 [US4] apps/web/e2e-full/mypage.spec.ts에 통계 시나리오를 더한다(T049와 같은 파일): `US4-AC1`, `US4-AC4`, `US4-AC5`(다른 회원 글을 공격해 처치한 뒤)

### Implementation for User Story 4

- [ ] T057 [US4] 파사드: `PostApi.liveRefsByAuthor(authorId): List<PostRef>`, `PostApi.liveIds(postIds): Set<Long>`, `MonsterApi.statRows(postIds): List<MonsterStatRow>`, `MonsterApi.defeatedPostIdsDamagedBy(memberId): Set<Long>`(`hp_after < hp_before`이고 `DEFEATED`, 인덱스 `monster_hp_log_member_monster_idx`)
- [ ] T058 [US4] feed 모듈 application/EmotionStatsQuery(research R12 순서 1~7, 주입한 `Clock`, `Asia/Seoul`)와 MyPageController `GET /api/v1/members/me/emotion-stats`. ContractTests `pendingPaths`에서 `getMyEmotionStats`를 뺀다
- [ ] T015 [US4] 웹 엔티티 apps/web/src/entities/emotion-stats/: model/types.ts(`EmotionStats`, `EmotionShare`, `WeeklyEmotionCount`), index.ts. T059와 같은 배치에서 만들어 처음부터 위젯이 쓰게 한다(steiger override를 두지 않는다)
- [ ] T059 [P] [US4] 웹: apps/web/src/entities/emotion-stats/api/queries.ts, ui/distribution-bar.tsx, ui/weekly-chart.tsx, apps/web/src/widgets/emotion-stats-panel/(숫자, 분포, 가장 많은 감정의 정지 이미지, 추이, 함께 물리친 몬스터, 빈 상태), apps/web/src/app/my/page.tsx의 통계 자리를 채운다.

**Checkpoint**: 마이페이지 통계가 데이터와 맞는다

---

## Phase 7: User Story 5 - 프로필을 고친다 (Priority: P3)

**Goal**: 닉네임, 직군, 경력 수정. 글의 직군과 경력은 스냅숏 유지, 닉네임은 어디서나 현재 값

**Independent Test**: quickstart 시나리오 25~28

### Tests for User Story 5 ⚠️

- [ ] T060 [P] [US5] apps/api/src/test/kotlin/com/ogu/member/presentation/ProfileUpdateApiTests.kt: `US5-AC1 규칙에 맞는 닉네임, 직군, 경력으로 고치면 200 MemberProfile이고 GET /me에 바로 보인다`, `US5-AC2 다른 회원의 닉네임을 대소문자만 바꿔 넣으면 409 NICKNAME_TAKEN, 허용되지 않는 문자는 M1과 같은 메시지의 400 INVALID_REQUEST`, 내 닉네임의 대소문자만 바꾸는 것은 허용, 필드가 하나도 없으면 400, `US5-AC3 직군과 경력을 바꿔도 예전 글은 스냅숏 그대로이고 새 글부터 바뀐 값`, `US5-AC4 닉네임을 바꾸면 예전 글, 댓글, 알림 목록에 바뀐 닉네임이 보인다`, 두 회원이 같은 닉네임을 동시에 저장하면 한 명만 성공, 온보딩 전 회원의 수정은 서비스에서도 403 `ONBOARDING_REQUIRED`
- [ ] T061 [P] [US5] apps/web/src/features/edit-profile/model/schema.test.ts(M1 온보딩 닉네임 스키마를 재사용해 같은 사례가 같은 결과, 바뀐 필드만 보냄, 하나도 안 바꾸면 저장 버튼 비활성)
- [ ] T062 [US5] apps/web/e2e-full/mypage.spec.ts에 프로필 시나리오를 더한다(T049와 같은 파일): `US5-AC1`, `US5-AC2`(중복과 형식 안내), `US5-AC3`(피드에서 예전 글과 새 글 비교)

### Implementation for User Story 5

- [ ] T063 [US5] member 모듈 application/ProfileService(회원 행 `FOR UPDATE`, `Nickname.of` 검증, 소문자 키가 내 키와 같으면 중복 확인 생략, 다르면 `existsByNicknameKey`, 저장 때 `member.nickname_key` 유일 제약 위반은 `409 NICKNAME_TAKEN`, 온보딩 전이면 403), presentation/MemberController `PATCH /api/v1/members/me`와 dto/ProfileUpdateRequest. `MemberProfileChanged` 이벤트는 만들지 않는다(research R13). ContractTests `pendingPaths`에서 `updateMyProfile`을 뺀다
- [ ] T064 [P] [US5] 웹: apps/web/src/features/edit-profile/(model/schema.ts, api/use-update-profile-mutation.ts, ui/profile-form.tsx: `nickname-availability` 확인 재사용), apps/web/src/app/my/edit/page.tsx, `/my`에 수정 버튼. 성공하면 내 정보, 피드, 상세, 알림 목록 쿼리를 무효화하고 `/my`로 이동한다

**Checkpoint**: 모든 스토리가 단독으로 동작한다

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T065 ContractTests `pendingPaths`가 비어 있고 루트 계약의 모든 연산(M1 8개, M2 13개, M3 11개)이 구현과 일치하는지 확인한다. `pnpm --filter web gen:api` 뒤 generated.ts 차이가 없어야 한다
- [ ] T066 [P] `grep -rn "US[1-5]-AC[0-9]*" apps/`로 스펙의 인수 조건 27개가 모두 테스트 이름에 있는지 확인하고 빠진 것을 추가한다
- [ ] T067 [P] 문서: apps/api/AGENTS.md에 `notification` 모듈 절(공개 이벤트와 파사드, 알림 생성 규칙, 번호와 재전송, Redis는 신호만 나른다는 점, Redis 장애 때 안전망, 연결 표)과 프로필 수정을 더한다. apps/web/docs/ARCHITECTURE.md에 알림 절(새 슬라이스, 연결 상태는 Zustand이고 목록과 수는 TanStack Query, 티켓 BFF 라우트, 재연결과 백오프, 캐시 반영)과 마이페이지 절을 더한다. docs/architecture/overview.md 4절(Redis는 pub/sub 신호만, 저장 없음), 5.1 표(`member` 공개 파사드에 연결 표 발급과 소비, `monster` 발행 이벤트에 `MonsterSpawned`, `notification` 구독 이벤트에 `PostLiked`, `MonsterSpawned` 추가, `emotion` 책임에서 감정 통계를 빼고 `feed`에 마이페이지 감정 통계), 5.1 그래프(`notification --> member`, 기존 `feed --> member` 추가), 알림과 SSE 흐름(티켓, 번호, 재전송, 팬아웃), 6.4(연결 상태 스토어), 8절(Redis 컨테이너, Caddy 스트림 설정)을 구현과 맞춘다. README에 알림과 마이페이지 소개 한 단락. ADR-0005(docs/adr/0005-notifications-before-safety.md)가 plan의 Complexity Tracking과 맞는지 확인한다
- [ ] T068 성능 측정: 회원 하나에 글 1천 개와 알림 1만 개를 넣는 시드 SQL을 apps/api/src/test/resources/seed/m3-perf.sql로 두고(글 작성 시각을 지난 8주에 흩뿌려 quickstart 22번에도 쓴다), 알림 목록 첫 쪽과 다음 쪽, 마이페이지 세 목록, 감정 통계를 각각 100번 불러 p95를 잰다(SC-004). e2e-full 알림 시나리오에서 행동 응답과 토스트 사이를 20번 재 p95를 낸다(SC-001). `NotificationStreamReplayTest`, `TwoInstanceStreamTest`, `RedisOutageStreamTest`를 각각 20번 `--rerun-tasks`로 돌려 빠짐과 중복 0건을 확인한다(SC-002, SC-003). 공감 100번을 1분 안에 나눠 보내 안 읽은 공감 알림이 하나인지 본다(SC-005). 결과를 specs/004-notification-mypage/quickstart.md에 003과 같은 표로 남기고 측정 중 부하(load average)와 환경을 함께 적는다. compose에 새 외부 서비스가 없음을 확인해 SC-006도 적는다
- [ ] T069 specs/004-notification-mypage/quickstart.md의 수동 시나리오 28개와 Redis 장애, 재시작 확인을 로컬에서 끝까지 실행하고, 다르면 문서나 코드를 고친다
- [ ] T070 `/speckit-analyze`로 일관성을 확인하고 PR을 연다(스펙 링크, 인수 조건 27개 체크리스트, 운영 준비 항목: `REDIS_PASSWORD`, `OGU_SSE_ALLOWED_ORIGINS`, Vercel `SSE_PUBLIC_ORIGIN`, Caddyfile)

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: T001 → T002 순서. T003~T006은 병렬
- **Foundational (Phase 2)**: Setup 뒤. 모든 스토리를 막는다. T007 → T008, T009 → T011, T012. T003 → T011
- **US1 (Phase 3)**: Foundational 뒤 (MVP). T029 → T034, T030 → T032, T031 → T032, T034 → T035, T014 → T036 → T037. 웹 연결(T036)은 T034의 계약만 있으면 가짜 `EventSource`로 먼저 할 수 있다
- **US2 (Phase 4)**: US1 뒤(알림이 있어야 목록이 있다). T044는 T034의 SseHub가 필요하다. T046은 T037의 종이 링크할 화면이다
- **US3 (Phase 5)**: Foundational 뒤. US1, US2와 독립이라 함께 진행할 수 있다
- **US4 (Phase 6)**: US3 뒤(`/my` 화면과 MyPageController를 같이 쓴다). T058은 T057 뒤
- **US5 (Phase 7)**: API(T060, T063)는 Foundational 뒤(T013). 웹과 e2e(T062, T064)는 T049, T052, T059 뒤(같은 `mypage.spec.ts`, `app/my/page.tsx`). US5-AC4의 알림 부분은 US2 뒤
- **Polish**: 모든 스토리 뒤. T068 → T069(시드 SQL을 22번에 쓴다)

### Parallel Opportunities

- Setup: T003, T004, T005, T006
- Foundational: T010, T013, T016
- US1 테스트 T017~T028은 모두 다른 파일이라 한꺼번에 쓴다. 구현은 API(T029~T035)와 웹(T036)을 나눠 진행한다
- US2 테스트 T038~T041
- US1을 진행하는 동안 US3(T047~T052)과 US5 API(T060, T063)를 다른 사람이 진행할 수 있다

### Parallel Example: User Story 1

```bash
# 테스트를 먼저 한꺼번에 쓴다
Task: "T017 CommentNotificationTests"
Task: "T018 LikeNotificationTests"
Task: "T019 MonsterNotificationTests"
Task: "T023 NotificationStreamReplayTest"
Task: "T025 TwoInstanceStreamTest"
Task: "T026 RedisOutageStreamTest"
Task: "T027 웹 연결 단위 테스트"

# 구현: API와 웹을 나눠서
Task: "T029 member 연결 표"
Task: "T030 PostApi.findComment, MonsterSpawned, damagerIds"
Task: "T036 features/notification-stream과 BFF 티켓 라우트"
```

## Implementation Strategy

### MVP First

1. Setup → Foundational → US1
2. **멈추고 검증**: 댓글, 공감 묶음, 몬스터 생성과 처치 알림이 열린 화면에 바로 오고, 재연결, 두 인스턴스, Redis 장애에서 빠짐과 중복이 없다(quickstart 1~9, "동시성과 재전송 확인")
3. US2(목록과 읽음) → 배포: 알림 기능 완성. 운영 준비(Redis 컨테이너, 환경 변수, Caddyfile)를 이 배포에서 끝낸다
4. US3(활동 목록) → US4(감정 통계) → US5(프로필 수정) → 배포

### Suggested Batches

| 배치 | 작업 | 내용 |
|---|---|---|
| 1 | T001~T006 | 계약, 의존성, 설정, 인프라, CI |
| 2 | T007~T013, T016 | V4, 모듈 뼈대, Redis 배선, 공통 타입 |
| 3 | T017~T020, T030~T033 | US1 API 알림 생성 |
| 4 | T021~T026, T029, T034, T035 | US1 API 스트림, 재전송, 다중 인스턴스, Redis 장애 |
| 5 | T014, T027, T028, T036, T037 | US1 웹 |
| 6 | T038~T040, T043~T045 | US2 API |
| 7 | T041, T042, T046 | US2 웹 |
| 8 | T047~T052 | US3 |
| 9 | T015, T053~T059 | US4 |
| 10 | T060~T064 | US5 |
| 11 | T065~T070 | 마무리와 PR |

### Notes

- 커밋 메시지와 PR 제목은 Conventional Commits
- `webbb-be/`, `webbb-fe/`는 수정하지 않는다
- M1 회고: 동시성 테스트는 `pg_stat_activity`를 읽을 때 `pg_stat_clear_snapshot()`을 먼저 부르고, 시각 비교는 DB 정밀도(마이크로초)에 맞춰 자른다
- Redis는 데이터의 원천이 아니다. 빠짐과 중복 0건은 번호 규칙과 DB 재전송이 보장하므로, Redis 관련 테스트가 흔들리면 신호 경로가 아니라 번호와 따라잡기부터 본다
