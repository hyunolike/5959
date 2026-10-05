# Implementation Plan: 알림과 마이페이지

**Branch**: `004-notification-mypage` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-notification-mypage/spec.md`

## Summary

내 글에 댓글이나 답글, 공감, 몬스터 생성, 처치가 생기면 알림을 만들고, 서비스를 열어 둔 회원에게 SSE로 바로 보낸다. 끊겼다가 다시 붙으면 놓친 알림을 빠짐없이, 중복 없이 다시 보낸다. 마이페이지에는 내 글, 내 댓글, 공감한 글 목록과 감정 통계, 프로필 수정을 더한다.

API에는 `notification` 모듈을 추가한다. 알림은 도메인 이벤트를 커밋 뒤 비동기로 받아 만들고, 유일 키로 재발행에도 하나만 남긴다. 회원별 카운터 행에서 받는 전달 번호가 SSE 이벤트 ID이고, 공감 묶음은 같은 행의 번호를 올려 맨 위로 보낸다. 인스턴스 사이의 전달은 overview대로 이번에 들이는 Redis pub/sub로 커밋 뒤 신호만 보내고, 내용은 언제나 DB에서 번호로 읽는다. Redis가 내려가도 알림은 저장되고, 전달은 안전망 조회와 재연결 재전송으로 이어진다. 연결은 BFF가 발급한 30초 일회용 티켓으로 브라우저가 API 도메인에 바로 붙는다.

웹은 알림 종과 배지, `/notifications` 목록, 토스트, `/my`의 세 탭과 감정 통계, `/my/edit`를 만든다. 연결 상태는 Zustand, 목록과 수는 TanStack Query가 맡는다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1(Spring MVC `SseEmitter`), Spring Modulith 2.1, `spring-boot-starter-data-redis`(Lettuce, 새 의존성), Testcontainers Redis
- 웹: Next.js 16, TanStack Query 5, Zustand 5, React Hook Form, Zod. 브라우저 내장 `EventSource`. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. Flyway `V4__notification_mypage.sql`로 `notification`, `notification_sequence`, `like_notification_participant`, `sse_ticket` 테이블과 마이페이지 조회 인덱스 4개를 만든다. Redis 7.4는 pub/sub 신호에만 쓰고 데이터를 저장하지 않는다(research R5)

**Testing**:
- API: JUnit 5, `@ApplicationModuleTest`, MockMvc, Testcontainers(Postgres, Redis), Redis를 멈춘 상태의 전달 테스트, JDK `HttpClient`로 실제 SSE 스트림 읽기, 같은 DB에 컨텍스트 두 개를 띄운 인스턴스 간 테스트, 동시성 테스트(공감 100 스레드), 시계 주입
- 웹: Vitest, Testing Library(가짜 `EventSource`), Playwright(`e2e-full`, 브라우저 컨텍스트 둘)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API, Caddy 뒤), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 알림이 생긴 뒤 열린 화면까지 3초 안에 95%(SC-001)
- 알림 목록, 마이페이지 목록, 감정 통계 p95 1초 이하(회원 하나에 글 1천 개, 알림 1만 개, SC-004)
- 공감이 1분에 100번 몰려도 안 읽은 공감 알림은 글마다 하나(SC-005)

**Constraints**:
- 재연결 뒤 놓친 알림과 중복 모두 0건, 재시작과 인스턴스 두 대에서도 같다(SC-002, SC-003)
- 알림 실패가 댓글, 공감, 분석, 처치를 막지 않는다(FR-003)
- 장기 로그인 정보가 연결 주소에 실리지 않는다(FR-006)
- 모듈 순환 의존이 없어야 한다(constitution I)
- 추가 월 인프라 비용 0원(SC-006, constitution VI)

**Scale/Scope**: 회원당 동시 연결 5개, 인스턴스 1~2대, 알림 7종, 새 API 연산 11개, 화면 3개(`/notifications`, `/my` 탭과 통계, `/my/edit`)와 전역 알림 종

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 확인 | 결과 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `notification`은 `post`, `monster`, `member`에만 의존한다. overview 5.1의 `notification → post, monster`에 닉네임과 연결 표 때문에 `notification → member`가 더해지지만, `member`는 아무것도 의존하지 않아 순환이 없다. 몬스터 생성 알림은 `emotion`이 아니라 `monster`의 새 이벤트 `MonsterSpawned`로 받아 `notification → emotion`을 만들지 않는다(R1, R8). 감정 통계는 `emotion`이 `monster`를 알면 순환이 되므로 `feed`가 조합한다. 웹은 `entities/notification`, `entities/emotion-stats`, `features/notification-stream`, `features/read-notification`, `features/edit-profile`, `widgets/notification-bell`, `widgets/notification-list`, `widgets/my-activity`, `widgets/emotion-stats-panel`을 FSD 규칙대로 두고 steiger가 검사한다 | 통과 |
| II. 계약이 코드보다 먼저다 | `contracts/notification-mypage.openapi.yaml`(경로 11개, 연산 11개)을 먼저 확정했고 Redocly 검증을 통과했다(경고 2개는 003과 같은 `info-license`, `localhost` 서버). SSE 스트림도 `text/event-stream`과 이벤트 data 스키마로 계약에 적었다. 구현 첫 작업이 루트 누적 계약에 합치고 타입을 생성하는 것이다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 27개(US1 8, US2 6, US3 4, US4 5, US5 4)에 테스트 ID를 붙인다. 실시간 전달(US1-AC1, AC6, AC7)은 API SSE 통합 테스트와 e2e-full 둘 다에서 검증한다. 수동 절차는 quickstart 28단계에 모든 ID를 담았다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위기 감지는 M4다. M3는 참여를 늘리는 기능(알림)이 들어가지만 레이드처럼 경쟁이나 몰림을 만드는 기능이 아니고, 위기 신호 알림은 M4에서 같은 알림 체계에 붙인다(스펙 Assumptions). M4 전까지 위험 글이 알림으로 퍼지지 않도록 별도 장치가 없다는 점은 M2와 같은 수준의 공백으로 남긴다 | 통과(주의) |
| V. AI 장애가 핵심 흐름을 막지 않는다 | 이 마일스톤은 AI를 새로 부르지 않는다. 몬스터 생성 알림은 분석이 끝난 뒤의 이벤트라 분석 장애와 무관하고, 기본 몬스터(24시간)도 같은 알림을 낸다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | Redis는 관리형 서비스가 아니라 기존 VM의 compose 안 컨테이너다(메모리 상한 64MB, 저장 없음). overview 4절의 계획(M3 SSE 팬아웃)과 같고, 새 외부 서비스나 유료 기능이 없어 추가 비용은 0원이다(SC-006, R5). VM의 연결 수천 개는 서블릿 비동기라 스레드를 쥐지 않는다(R2) | 통과 |

**Phase 1 설계 후 재확인**:
- 새 테이블은 각각 한 모듈이 소유한다. `notification`, `notification_sequence`, `like_notification_participant`는 `notification`, `sse_ticket`은 `member`다. 다른 모듈은 파사드(`PostApi.findComment`, `PostApi.previews`, `MonsterApi.damagerIds`, `MemberApi.consumeStreamTicket` 등)만 쓴다.
- 이벤트 구독 방향이 의존 그래프와 같다. `notification`은 `post`의 `PostLiked`, `CommentCreated`와 `monster`의 `MonsterSpawned`, `MonsterDefeated`를 받는다.
- 알림 생성은 모두 커밋 뒤 비동기 리스너라 원래 행동과 분리되고(FR-003), 유일 키로 재발행에도 결과가 하나다.
- Redis는 신호만 나르고 데이터의 원천이 아니다. Redis가 없어도 알림은 저장되고 재전송된다(R5).
- 계약의 모든 JSON 응답이 `ApiResponse` 봉투를 따른다. 스트림만 `text/event-stream`이고, 스트림을 열기 전의 오류(티켓)는 봉투를 쓴다.
- 프로필 수정이 `/api/v1/members/me`(온보딩 전 허용 경로)에 `PATCH`로 붙으므로, 허용 목록을 `GET`에만 맞춰 온보딩을 건너뛰는 길을 막는다(R13).
- 위반 사항이 없어 Complexity Tracking은 비운다.

## Project Structure

### Documentation (this feature)

```text
specs/004-notification-mypage/
├── spec.md
├── plan.md                              # 이 문서
├── research.md                          # 결정 R1~R15
├── data-model.md                        # 테이블 4개, 인덱스, 상태 규칙, 이벤트, 파사드, 의존 그래프
├── quickstart.md                        # 수동 시나리오 28단계, 동시성과 재전송, 운영 준비
├── contracts/
│   └── notification-mypage.openapi.yaml # 이번 추가분(경로 11개, 연산 11개). 구현 때 루트 contracts/openapi.yaml에 합친다
├── checklists/requirements.md
└── tasks.md                             # /speckit-tasks
```

### Source Code (repository root)

```text
contracts/openapi.yaml                           # 누적 계약(004 추가분 합침)

apps/api/src/main/java/com/ogu/notification/package-info.java   # allowedDependencies = shared, post, monster, member
apps/api/src/main/kotlin/com/ogu/
├── member/
│   ├── MemberApi.kt                             # issueStreamTicket, consumeStreamTicket 추가
│   ├── StreamTicket.kt
│   ├── domain/        SseTicket, SseTicketRepository(조건부 UPDATE 소비)
│   ├── application/   ProfileService(닉네임 재검증, 잠금, 유일 제약), StreamTicketService, SseTicketCleanupJob
│   ├── presentation/  MemberController(PATCH /me), dto/ProfileUpdateRequest
│   └── infrastructure/security/SecurityPaths.kt # 스트림 공개 경로, 온보딩 허용 목록을 GET /me로 좁힘
├── post/
│   ├── PostApi.kt                               # findComment, previews, pageByAuthor, pageLikedBy, pageCommentsByAuthor, liveRefsByAuthor, liveIds
│   ├── CommentSummary.kt, PostPreview.kt, PostRef.kt, MyCommentPage.kt
│   └── application/   MyPostsReader, MyCommentsReader, LikedPostsReader(키셋)
├── monster/
│   ├── MonsterApi.kt                            # damagerIds, statRows, defeatedPostIdsDamagedBy
│   ├── MonsterSpawned.kt, MonsterStatRow.kt     # 새 이벤트와 통계용 행
│   └── application/   MonsterFactory(MonsterSpawned 발행)
├── notification/
│   ├── domain/        Notification, NotificationType, NotificationSequence, LikeParticipant, 리포지토리
│   ├── application/   NotificationEventListener(4종), NotificationWriter(번호, 멱등, 묶음, 커밋 뒤 발행 예약),
│   │                  NotificationQueryService(목록, 안 읽은 수), NotificationReadService, NotificationPurgeJob
│   ├── stream/        SseHub(회원별 연결, 따라잡기), RedisSignalPublisher, RedisSignalSubscriber(구독, 복구 뒤 따라잡기),
│   │                  SafetyDrain(60초, Redis 장애 중 5초),
│   │                  StreamHeartbeat, StreamCorsConfig
│   └── presentation/  NotificationController, NotificationStreamController, dto
├── feed/
│   ├── application/   FeedAssembler(피드와 마이페이지 공용), MyPageQuery, EmotionStatsQuery(주 계산, 반올림 보정)
│   └── presentation/  MyPageController(/api/v1/members/me/posts, comments, liked-posts, emotion-stats)
└── shared/error/ErrorCode.kt                    # NOTIFICATION_NOT_FOUND, STREAM_TICKET_INVALID
apps/api/src/main/resources/
├── db/migration/V4__notification_mypage.sql
└── application.yml                              # spring.data.redis.url, management.health.redis(헬스 판단 제외), ogu.notification.*(하트비트, 연결 수명, 회원당 연결 수, 보관 기간, 안전망 주기), ogu.sse.allowed-origins

apps/web/src/
├── app/
│   ├── layout.tsx                               # 온보딩 쿠키가 있으면 알림 종 렌더링
│   ├── notifications/page.tsx
│   ├── my/page.tsx                              # 프로필, 감정 통계, 탭(?tab=posts|comments|likes)
│   ├── my/edit/page.tsx
│   └── api/notifications/stream-ticket/route.ts # 전용 BFF 라우트(티켓 + streamUrl)
├── entities/
│   ├── notification/   types, queries(useNotificationsQuery, useUnreadCountQuery), model/message.ts(문구), model/badge.ts(99+), ui/notification-item
│   └── emotion-stats/  types, queries, ui/distribution-bar, ui/weekly-chart
├── features/
│   ├── notification-stream/ model/store.ts(Zustand), model/backoff.ts, api/connect.ts(티켓, EventSource), model/cache-sync.ts
│   ├── read-notification/   mark-one, mark-all(upToSeq)
│   └── edit-profile/        schema(M1 닉네임 규칙 재사용), form, mutation
├── widgets/
│   ├── notification-bell/   종, 배지, 연결 시작, 토스트
│   ├── notification-list/   무한 스크롤, 모두 읽음, 삭제된 글 안내
│   ├── my-activity/         세 탭, 빈 상태
│   └── emotion-stats-panel/
├── shared/ui/toast.tsx
└── shared/server/route-guard.ts                 # 보호 경로에 /notifications 추가
apps/web/src/app/api/[...path]/route.ts          # notifications/stream-tickets 직접 전달 거부
apps/web/e2e-full/notification.spec.ts, mypage.spec.ts

infra/Caddyfile                                  # 스트림 경로를 encode에서 제외
infra/compose.prod.yaml, infra/compose.e2e.yaml  # redis 서비스(redis:7.4-alpine, 저장 없음, 64MB), REDIS_URL, OGU_SSE_ALLOWED_ORIGINS
apps/api/compose.yaml                            # 로컬 개발용 redis 서비스
docs/architecture/overview.md                    # 5.1 notification 의존과 통계 위치
```

**Structure Decision**: M2의 모노레포 구조를 그대로 쓴다.
- API는 Modulith 모듈 `notification` 하나를 추가하고, 마이페이지 조회는 `feed`에, 프로필 수정과 연결 표는 `member`에 둔다.
- 웹은 FSD의 `entities`, `features`, `widgets`에 슬라이스를 추가한다. BFF는 티켓 발급 전용 라우트 하나를 더하고, 나머지 새 경로는 범용 프록시를 쓴다.
- 인프라는 compose에 Redis 컨테이너 하나를 더하고, Caddyfile과 환경 변수를 바꾼다.

## Complexity Tracking

위반 사항 없음.
