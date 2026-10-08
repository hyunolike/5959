---

description: "Task list for 005-safety (위험 감지와 안전장치)"
---

# Tasks: 위험 감지와 안전장치

**Input**: Design documents from `/specs/005-safety/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/safety.openapi.yaml, quickstart.md

**Tests**: 포함한다. constitution III에 따라 스토리마다 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다(예: `` `US1-AC2 ...` ``). ID가 없는 보조 테스트는 한국어 설명만 쓴다. 인수 조건은 34개다(US1 8, US2 5, US3 6, US4 9, US5 6).

**Organization**: 사용자 스토리별로 묶었다. US2(AI가 실패해도 감지)의 키워드 규칙은 US1의 전제라 Foundational과 US1에 먼저 들어가고, US2 단계는 AI 분류와 재시도를 맡는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 미완료 작업에 의존하지 않아 병렬로 할 수 있다
- **[Story]**: 해당 사용자 스토리(US1~US5)

## Path Conventions

- API: `apps/api/src/main/kotlin/com/ogu/`, 테스트 `apps/api/src/test/kotlin/com/ogu/`, 리소스 `apps/api/src/main/resources/`
- 웹: `apps/web/src/`, 전체 흐름 E2E `apps/web/e2e-full/`
- 계약: 저장소 루트 `contracts/openapi.yaml`

---

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 `specs/005-safety/contracts/safety.openapi.yaml`의 tags, paths, components를 루트 contracts/openapi.yaml에 합친다. 이름이 같은 것(`ErrorResponse`, `ErrorEnvelope`, 파라미터 `PostId`, `CommentId`, `Cursor`, `Size`, 응답 `BadRequest`, `Unauthorized`, `Forbidden`, `NotFound`)은 루트의 정의를 쓴다. 기존 스키마를 바꾼다: `PostDetail`에 `safety`(`ContentSafety`, 작성자에게만), `Comment`에 `hidden`(필수)과 `safety`를 더하고 `content`와 `author`를 nullable로, `FeedItem`에 `hidden`(필수, 내 글 목록에서만 true), `NotificationType`에 `SUPPORT_NOTICE`, `CONTENT_RESTORED`, `REVIEW_KEPT`. `info.version`을 0.5.0으로 올린다
- [x] T002 `pnpm --filter web gen:api`로 apps/web/src/shared/api/generated.ts를 다시 만든다. 바뀐 스키마로 깨지는 웹 타입(`Comment.content`, `author`가 nullable)을 임시로 맞춘다(화면 처리는 T038). apps/api/src/test/kotlin/com/ogu/ContractTests.kt의 `pendingPaths`에 새 연산 14개를 넣어 통과시킨다
- [x] T003 [P] apps/api/src/main/resources/application.yml에 `ogu.safety` 블록을 넣는다: `term-refresh-interval: 30s`, `retry.initial-delay: 30s`, `retry.max-delay: 5m`, `retry.give-up-after: 24h`, `retry.poll-interval: 10s`, `report.max-per-hour: 20`, `retention: 365d`, `purge-cron`(매일 04:30 한국 시간), `backfill.enabled: true`, `backfill.batch-size: 1000`. `resilience4j.circuitbreaker.instances.riskClassifier`를 `emotionAnalyzer`와 같은 값으로 따로 둔다

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: 이 단계가 끝나야 스토리 작업을 시작한다. 이 단계에서는 아직 아무것도 숨기지 않으므로 기존 테스트 757개가 그대로 통과해야 한다

- [x] T004 apps/api/src/main/resources/db/migration/V5__safety.sql을 data-model.md 그대로 작성한다. `posts`, `comments`에 `hidden_at`, `hidden_reason`, `risk_level`(기본 `NONE`), `review_requested_at`과 체크 제약(`hidden_at`과 `hidden_reason`은 함께 NULL이거나 함께 값). `member.role`(기본 `MEMBER`). 피드 부분 인덱스 셋을 `deleted_at IS NULL AND hidden_at IS NULL`로 다시 만든다. `notification_type_check`에 종류 셋을 더하고 `dedup_key`를 60으로 늘린다. 새 테이블 7개와 인덱스, 유일 제약(`report_reporter_target_key`, `review_request_target_key`, `safety_term (kind, term)`). 시드: 도움 리소스 3개, 위기 낱말(원본 6개의 정규화 형태), 우려, 욕설, 허용 낱말의 초깃값
- [x] T005 apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt에 V5 테스트를 더한다: 새 열과 테이블, 인덱스 존재. `hidden_at`만 있고 `hidden_reason`이 없으면 거부. 같은 회원의 같은 대상 두 번째 신고 거부. 같은 대상의 두 번째 재검토 요청 거부. `risk_level`, `role`, `reason`의 목록 밖 값 거부. 새 알림 종류 셋 허용. 도움 리소스 3개와 위기 낱말 시드 존재
- [x] T006 `post` 조회 조건을 둘로 나눈다(research R5). apps/api/src/main/kotlin/com/ogu/post/application/Visibility.kt에 SQL 조각 `visible(alias)`(`deleted_at IS NULL AND hidden_at IS NULL`)과 `ownedOrVisible(alias, viewerParam)`(지우지 않았고, 숨기지 않았거나 작성자가 보는 사람)을 두고, `deleted_at is null`을 거는 조회를 모두 이것으로 바꾼다. **보이는 것만**: 피드(`PostPageReader`), 공감한 글(`LikedPostsReader`), `PostApi.find`, `findComment`, `likedPostIds`, `attacksSoFar`, 공감과 댓글 쓰기의 대상 확인(`LikeService`, `CommentService`), 댓글 공감. **작성자 포함**: 글 상세용 조회(새 `PostApi.findForViewer(postId, viewerId)`), `MyPostsReader`, `MyCommentsReader`(댓글은 작성자 포함, 조인한 글은 보이는 것만이되 내 글이면 포함), `liveRefsByAuthor`, 글과 댓글 수정과 삭제. JPA 파생 쿼리는 `findVisibleById`, `findOwnedOrVisibleById`로 바꾼다. 기존 테스트가 모두 통과해야 한다 **구현 메모**: `PostApi.find`는 지우지 않은 글(숨긴 글 포함) 그대로 두었다. 감정 분석과 몬스터가 숨긴 글도 다뤄야 작성자에게 몬스터가 보인다. 다른 회원에게 보이는 글은 새 `findVisible`, 글 상세는 `findForViewer`가 읽고, 알림은 `findVisible`로 바꿨다. 조건 조각은 `post/domain/Visibility.kt`에 있다.
- [x] T007 `post`의 새 공개 타입과 파사드: `PostModerationApi`(`contentOf`, `markRisk`, `markReviewRequested`, `hide`, `unhide`, `scan`)와 `ModerationTarget(targetType, targetId, postId, authorId, content, updatedAt, hidden)`, 구현 `PostModerationService`. `hide`와 `unhide`는 글 행을 `UPDATE ... WHERE` 한 문장으로 바꾸고 바뀐 행 수로 결과를 돌려준다. 이벤트 `PostWritten(postId, authorId, edited)`, `CommentWritten(postId, commentId, authorId, edited)`, `PostRemoved(postId)`, `CommentRemoved(commentId)`를 `PostService`, `CommentService`의 저장, 수정, 삭제와 같은 트랜잭션에서 낸다(원 댓글을 지우면 함께 지워지는 답글마다 `CommentRemoved`). `PostApi.previews(postIds, viewerId)`로 시그니처를 바꾸고 `notification`의 호출을 맞춘다(아직 숨김 처리는 없다) **구현 메모**: 대상 종류는 `post`의 `ContentType`(POST, COMMENT) 하나를 `safety`도 쓴다(`TargetType`을 따로 두지 않는다). 운영자 조회용 `contentsOf`를 더했다. 수정 때는 리스너가 JdbcClient로 본문을 읽으므로 이벤트 전에 flush한다.
- [x] T008 [P] apps/api/src/main/kotlin/com/ogu/shared/text/ContentMask.kt: 인터페이스 `mask(text: String): String`와 아무것도 가리지 않는 기본 구현(`@ConditionalOnMissingBean`). 단위 테스트
- [x] T009 [P] `safety` 모듈 뼈대: apps/api/src/main/java/com/ogu/safety/package-info.java(`allowedDependencies = {"shared", "post", "ai", "member"}`), `notification`의 `allowedDependencies`에 `safety`를 더한다. 루트 타입 `RiskLevel`(NONE < CONCERN < CRISIS, `max`), `TargetType`, 이벤트 `RiskDetected`, `ContentRestored`, `ReviewResolved`. `ErrorCode`에 `ALREADY_REPORTED`(409), `CANNOT_REPORT_OWN_CONTENT`(403), `REPORT_RATE_LIMITED`(429), `REVIEW_ALREADY_REQUESTED`(409). `ModularityTests` 통과 **구현 메모**: `TargetType`은 만들지 않았다(T007).
- [x] T010 [P] `member`: `Member.role`(`MemberRole` MEMBER, OPERATOR)과 `MemberApi.isOperator(memberId)`. 테스트: 기본은 false, DB에서 OPERATOR로 바꾸면 true
- [ ] T011 [P] `safety` 도메인: apps/api/src/main/kotlin/com/ogu/safety/domain/(RiskAssessment, Report, ReviewRequest, ModerationAction, SafetyTerm, SupportResource와 리포지토리) **구현 메모**: 테이블마다 그 테이블을 쓰는 스토리에서 JdbcClient 저장소로 만든다(JPA 엔티티를 두지 않는다).

**Checkpoint**: 스키마, 조회 조건, 파사드, 이벤트, 모듈 경계 준비 완료. 기존 테스트 통과

---

## Phase 3: User Story 1 - 위기 신호가 담긴 글을 쓰면 도움을 안내받는다 (Priority: P1) 🎯 MVP

**Goal**: 목록에 있는 위기 표현이 든 글과 댓글은 저장과 함께 숨겨지고, 작성자는 도움 리소스를 본다

**Independent Test**: 위기 표현이 든 글을 쓰고 작성자에게 안내가 보이는지, 다른 회원의 모든 조회에서 그 글이 없는지 본다

### Tests for User Story 1 ⚠️

- [x] T012 [P] [US1] apps/api/src/test/kotlin/com/ogu/safety/application/TextNormalizerTest.kt, KeywordRuleTest.kt(표 형식): 공백, 문장 부호, 기호 제거. 같은 글자 3번 이상은 2번으로. NFKC와 소문자. `US2-AC4 위기 표현 사이에 공백이나 기호를 끼워도 같은 표현으로 알아본다`. 위기 목록에 걸리면 CRISIS, 우려 목록만 걸리면 CONCERN, 둘 다 아니면 NONE. 부정문("죽고 싶지는 않다")도 CRISIS. 빈 문자열과 이모지뿐인 글은 NONE
- [x] T013 [P] [US1] apps/api/src/test/kotlin/com/ogu/safety/HiddenContentMatrixTests.kt: 숨긴 글과 숨긴 댓글을 준비하고 조회 경로마다 작성자와 다른 회원의 결과를 표로 단언한다. 경로: 피드 최신순과 인기순, 글 상세, 댓글 목록, 내가 쓴 글, 내 댓글, 공감한 글, 감정 통계, 알림 미리보기. `US1-AC2 다른 회원의 피드, 상세, 공감한 글에 없고 주소로 열면 404 POST_NOT_FOUND`, `US1-AC3 작성자는 상세와 내 글 목록에서 보고 safety.hidden이 true`, `US1-AC5 숨긴 댓글은 다른 회원에게 hidden=true이고 content와 author가 null이며 답글은 그대로`, `US1-AC8 숨긴 글에 공감, 댓글, 댓글 공감은 404이고 HP가 그대로`
- [x] T014 [P] [US1] apps/api/src/test/kotlin/com/ogu/safety/ScreeningApiTests.kt: `US1-AC1 위기 표현이 든 글을 쓰면 201이고 상세의 safety가 CRISIS, hidden`, 글 작성 응답 직후 다른 회원의 피드 첫 쪽에 없다, `US1-AC4 우려 표현이 든 글은 숨기지 않고 작성자의 safety만 CONCERN`, `US1-AC5 위기 표현이 든 댓글의 작성 응답에 safety가 실린다`, `US2-AC1 AI가 실패해도 키워드 규칙으로 위기 판정`, `US2-AC2 AI가 위험 없음이어도 키워드가 위기면 위기`, 다른 회원의 응답에는 `safety` 필드가 없다, `risk_assessment`에 본문이나 걸린 표현이 저장되지 않는다, 도움 리소스 API가 순서대로 3개를 준다 **구현 메모**: `US2-AC1`, `US2-AC2`는 AI 분류기가 있어야 만들 수 있어 T027에서 쓴다.
- [x] T015 [P] [US1] apps/api/src/test/kotlin/com/ogu/safety/ScreeningVisibilityTests.kt(동시성, SC-001): 위기 표현이 든 글 100개를 여러 스레드로 쓰는 동안 다른 회원이 피드를 계속 불러도 그 글이 한 번도 나오지 않는다
- [x] T016 [P] [US1] apps/api/src/test/kotlin/com/ogu/notification/SafetyNotificationTests.kt: `US1-AC6 위기와 우려 판정이 SUPPORT_NOTICE 알림을 만들고 문구 재료에 본문이 없다`, 같은 대상에 같은 단계는 한 번만, 단계가 올라가면 한 번 더. `US1-AC7 숨긴 글에는 댓글, 공감, 몬스터 알림이 새로 생기지 않고 이미 있는 알림의 post가 null이다`. 받는 사람이 작성자면 숨긴 글의 미리보기가 그대로 온다
- [x] T017 [P] [US1] 웹 단위 테스트: apps/web/src/widgets/safety-banner/ui/*.test.tsx(`US1-AC1 위기면 닫을 수 없는 안내와 전화 링크 3개`, `US1-AC3 숨김 설명`, `US1-AC4 우려면 접을 수 있는 안내`, safety가 없으면 아무것도 그리지 않음), apps/web/src/entities/comment/ui/comment-item.test.tsx(`US1-AC5 hidden이고 content가 null이면 "가려진 댓글이에요"만, 답글은 그대로`), apps/web/src/entities/notification/model/message.test.ts(새 종류 셋의 문구) **구현 메모**: 배너 테스트는 `widgets/post-detail/ui/safety-notice.test.tsx`에 있다.
- [x] T018 [US1] apps/web/e2e-full/safety.spec.ts: `US1-AC1`, `US1-AC2`(다른 컨텍스트의 피드와 주소 직접 열기), `US1-AC3`(`/my`), `US1-AC4`, `US1-AC5`, `US1-AC6`(홈을 열어 둔 채 토스트)

### Implementation for User Story 1

- [x] T019 [US1] `safety` 판정: application/TextNormalizer(정규화와 원문 위치 대응표), KeywordRule(단계 판정, 순수 함수), TermCache(`safety_term`을 메모리에 올리고 `term-refresh-interval`마다 `count(*)`와 `max(updated_at)`으로 변경 확인. 읽지 못하면 마지막 목록, 한 번도 못 읽었으면 내장 최소 목록) **구현 메모**: 아래아(ㆍ, ᆞ)는 글자로 분류되지만 가운뎃점처럼 끼워 넣는 데 쓰여 기호와 같이 버린다.
- [x] T020 [US1] application/ScreeningListener: `PostWritten`, `CommentWritten`을 `@EventListener`로 같은 트랜잭션에서 받는다. `PostModerationApi.contentOf`로 원문을 읽어 키워드 판정, `risk_assessment` 행(이전 행은 `SUPERSEDED`), `markRisk`, 위기면 `hide(reason = RISK)`. 단계가 이전 최종 단계보다 높으면 커밋 뒤 `RiskDetected`를 낸다. 리스너 안의 예외가 글 저장을 막지 않도록 판정 계산 밖의 실패(목록 읽기)는 TermCache가 흡수한다
- [x] T021 [US1] 응답에 `safety`: `feed`의 글 상세가 `PostApi.findForViewer`의 `hidden`, `riskLevel`을 작성자에게만 `safety`로 내보낸다(`reviewRequested`는 T046에서 채우고 지금은 false). 내 글 목록 항목의 `hidden`. `post`의 댓글 응답에 `hidden`, 숨긴 댓글의 `content`, `author` null 처리(다른 회원), 작성자에게 `safety`. 댓글 작성 응답도 같다. ContractTests에서 바뀐 스키마 확인
- [x] T022 [US1] `safety` presentation/SafetyController `GET /api/v1/safety/support-resources`(활성인 것만 `display_order` 순). `pendingPaths`에서 `getSupportResources`를 뺀다 **구현 메모**: 조회 한 문장이라 저장소 클래스 없이 컨트롤러가 JdbcClient로 읽는다.
- [x] T023 [US1] `notification`: `NotificationType`에 종류 셋, `NotificationEventListener`가 `RiskDetected`를 받아 `SUPPORT_NOTICE`를 만든다(멱등 키 `RISK:{type}:{id}:{level}`, 행동한 회원 없음, 받는 사람은 작성자). 대상 확인은 작성자 기준 조회를 쓴다. `PostApi.previews`가 숨긴 글을 `deleted = true`로 주되 `viewerId`가 작성자면 그대로 준다
- [x] T024 [P] [US1] 웹 엔티티와 위젯: apps/web/src/entities/safety/(model/types.ts, api/use-support-resources-query.ts, ui/support-resources.tsx: 이름, `tel:` 링크, 운영 시간), apps/web/src/widgets/safety-banner/(위기는 고정 안내와 숨김 설명, 우려는 접기), apps/web/src/widgets/post-detail에 배너 배치. `entities/comment`의 항목에 가려진 댓글 자리와 내 숨긴 댓글 표시, `entities/post`의 카드에 내 글 목록의 숨김 표시 **구현 메모**: 위젯끼리는 서로 가져올 수 없어 `widgets/safety-banner`를 따로 두지 않고, 안내(`SafetyNotice`)와 도움 리소스 조회를 `widgets/post-detail` 안에 두었다. `entities/safety`도 쓰는 곳이 이 위젯 하나라 만들지 않았다(004의 `insignificant-slice` 경험). 댓글의 안내는 댓글마다 띄우지 않고 목록 아래에 하나만 둔다.
- [x] T025 [P] [US1] 웹 알림: apps/web/src/entities/notification/model/message.ts에 새 종류 셋의 문구, `widgets/notification-bell`에서 `SUPPORT_NOTICE` 토스트는 8초 머문다. 댓글 작성 응답의 `safety`가 CONCERN 이상이면 댓글 아래에 도움 안내를 보인다

**Checkpoint**: 목록에 있는 위기 표현은 한 번도 공개되지 않고 작성자는 안내를 받는다(AI 없이)

---

## Phase 4: User Story 2 - 분석이 실패해도 위험 감지는 빠지지 않는다 (Priority: P1)

**Goal**: AI 분류를 더하고, 실패하면 다시 시도하며, 끝내 실패하면 키워드 판정이 최종이 된다

**Independent Test**: 가짜 분류기의 머리말로 실패와 지연을 만들고 판정과 대응이 기대대로인지 본다

### Tests for User Story 2 ⚠️

- [ ] T026 [P] [US2] apps/api/src/test/kotlin/com/ogu/ai/RiskClassifierTests.kt: 응답 `{"level":"CRISIS"}` 파싱, 목록 밖의 값과 깨진 JSON은 `RiskClassificationFailed`, 타임아웃 분류, 응답 원문이 로그에 남지 않는다. `FakeRiskClassifier`의 머리말(`[위기]`, `[우려]`, `[위험분류실패]`, `[위험분류실패:2]`)
- [ ] T027 [P] [US2] apps/api/src/test/kotlin/com/ogu/safety/RiskAssessmentTests.kt(시계 주입): `US2-AC3 AI가 실패해도 글 저장은 201이고 일정이 30초, 60초 뒤로 밀리며 성공하면 DONE`, 24시간이 지나면 `FALLBACK`이고 단계는 키워드 그대로, AI만 위기로 본 글은 분류가 끝난 뒤 숨겨지고 `RiskDetected`가 한 번 나간다, AI가 낮게 봐도 단계가 내려가지 않는다, `US2-AC5 고치면 다시 판정하고 위기 표현을 지워도 숨김은 그대로`, 고친 뒤 늦게 온 이전 내용의 결과는 버린다(`content_version`), 두 스케줄러가 같은 행을 함께 맡지 않는다(`SKIP LOCKED`)
- [ ] T028 [US2] apps/web/e2e-full/safety.spec.ts에 더한다: `US2-AC1`(`[위험분류실패]`와 위기 표현), `US2-AC3`(`[위험분류실패:2]` 글이 오류 없이 올라감)

### Implementation for User Story 2

- [ ] T029 [US2] `ai`: `RiskClassifier`, `RiskClassification(level)`, `RiskClassificationFailed(kind)`, infrastructure/SpringAiRiskClassifier(프롬프트 apps/api/src/main/resources/prompts/risk-classification.st, 타임아웃과 서킷 브레이커 `riskClassifier`, SDK 재시도 끔, 응답은 단계만), DisabledRiskClassifier(키가 없으면 바로 실패), FakeRiskClassifier(`e2e` 프로필). `ai`의 루트 타입은 `safety`의 `RiskLevel`을 모르므로 자기 열거형을 두고 `safety`가 옮긴다
- [ ] T030 [US2] `safety` application/RiskAssessmentService: 커밋 뒤 `@ApplicationModuleListener`로 `PostWritten`, `CommentWritten`을 받아 바로 한 번 분류한다(LLM 호출은 트랜잭션 밖, 맡기와 기록은 각각 짧은 트랜잭션). 성공하면 `ai_level`, `level = max`, `DONE`. 최종 단계가 올라갔으면 `markRisk`, 위기면 `hide`, `RiskDetected`. `content_version`이 지금과 다르면 버린다
- [ ] T031 [US2] application/RiskRetryScheduler: `poll-interval`마다 차례가 된 `PENDING`을 `FOR UPDATE SKIP LOCKED`로 맡아 다시 시도. 실패하면 `min(initial × 2^(n-1), max)` 뒤로. `give-up-after`가 지나면 `FALLBACK`

**Checkpoint**: 키워드와 AI가 함께 판정하고, AI 장애에도 감지와 쓰기가 유지된다

---

## Phase 5: User Story 3 - 글과 댓글을 신고한다 (Priority: P2)

**Goal**: 회원이 다른 회원의 글과 댓글을 사유와 함께 신고한다

**Independent Test**: 신고가 기록되고, 중복과 자기 신고가 막히고, 한 시간 20건 제한이 걸리는지 본다

### Tests for User Story 3 ⚠️

- [ ] T032 [P] [US3] apps/api/src/test/kotlin/com/ogu/safety/ReportApiTests.kt: `US3-AC1 사유와 함께 신고하면 204이고 기록된다`(OTHER는 설명 200글자까지, 201글자는 400, 다른 사유에 설명을 보내면 무시), `US3-AC2 같은 대상을 다시 신고하면 409 ALREADY_REPORTED이고 수가 늘지 않는다`, `US3-AC3 자기 글과 댓글은 403 CANNOT_REPORT_OWN_CONTENT`, `US3-AC4 글 상세, 피드, 알림 어디에도 신고 수나 신고 여부가 없다`, `US3-AC5 한 시간에 21번째는 429 REPORT_RATE_LIMITED와 Retry-After`(시계 주입, 동시 요청 30개 중 20개만 통과), `US3-AC6 여러 회원이 신고해도 글은 그대로 보인다`, 숨겼거나 지운 대상은 404, 대상이 지워지면 열린 신고가 `CLOSED`
- [ ] T033 [P] [US3] 웹 단위 테스트: apps/web/src/features/report-content/ui/*.test.tsx(사유 넷, 기타를 고르면 설명 입력과 200자 세기, `US3-AC2 409면 "이미 신고한 글이에요"`, 429 안내, 성공하면 닫히고 "신고가 접수됐어요"), 내 글과 댓글에는 신고 메뉴가 없다(`US3-AC3`)
- [ ] T034 [US3] apps/web/e2e-full/safety.spec.ts에 더한다: `US3-AC1`, `US3-AC2`, `US3-AC3`

### Implementation for User Story 3

- [ ] T035 [US3] `safety` application/ReportService(대상 확인은 `PostApi.find`, `findComment`, 자기 것 거절, 유일 제약 위반을 409로)와 ReportRateLimit(회원 단위 advisory lock, 새 이름공간, 최근 한 시간 수), RemovalListener(`PostRemoved`, `CommentRemoved`를 같은 트랜잭션에서 받아 열린 신고와 재검토 요청을 닫는다). presentation `POST /api/v1/reports`. `pendingPaths`에서 `reportContent`를 뺀다
- [ ] T036 [P] [US3] 웹: apps/web/src/features/report-content/(model/schema.ts, api/use-report-mutation.ts, ui/report-dialog.tsx), `widgets/post-detail`의 글과 댓글 메뉴에 "신고"(내 것에는 없음). `shared/lib/scrub-event.ts`에 신고와 글쓰기 경로를 더해 폼 값이 breadcrumb에 실리지 않게 한다

**Checkpoint**: 신고가 접수되고 운영자 조회를 기다린다

---

## Phase 6: User Story 4 - 운영자가 확인하고 처리한다 (Priority: P2)

**Goal**: 운영자가 판정, 신고, 재검토 요청을 조회하고 숨김, 해제, 닫기를 한다. 작성자는 재검토를 요청한다

**Independent Test**: 운영자 토큰으로 조회와 처리를 하고 결과가 회원 화면에 반영되는지, 일반 회원에게는 404인지 본다

### Tests for User Story 4 ⚠️

- [ ] T037 [P] [US4] apps/api/src/test/kotlin/com/ogu/safety/OperatorApiTests.kt: `US4-AC1 판정 조회가 최신순이고 단계, 방법, 상태가 있으며 level과 reviewed로 거른다`, `US4-AC2 신고 조회에 사유, 설명, 같은 대상의 열린 신고 수가 있다`, `US4-AC3 숨김을 풀면 다른 회원에게 다시 보이고 CONTENT_RESTORED 알림이 간다`, `US4-AC4 숨기면 그 대상의 열린 신고가 모두 RESOLVED로 닫힌다`, `US4-AC5 기각하면 REJECTED로 닫히고 대상은 그대로`, `US4-AC6 운영자가 아니면 모든 운영자 경로가 404 NOT_FOUND`(권한을 거두면 같은 토큰으로 바로 404), `US4-AC7 처리마다 moderation_action에 운영자와 시각이 남는다`, 키셋 이어 불러오기에 중복과 누락 없음, 작성자가 지운 글은 숨김을 풀어도 보이지 않는다, 숨김과 해제를 동시에 불러도 상태와 기록이 어긋나지 않는다, 낱말 더하기와 빼기가 정규화해 저장하고 기록을 남긴다
- [ ] T038 [P] [US4] apps/api/src/test/kotlin/com/ogu/safety/ReviewRequestApiTests.kt: `US4-AC8 숨겨진 내 글에 재검토를 요청하면 204이고 운영자 조회에 나타나며 두 번째는 409 REVIEW_ALREADY_REQUESTED`, 남의 글, 숨겨지지 않은 글은 404, 요청 뒤 상세의 `safety.reviewRequested`가 true, `US4-AC9 유지로 닫으면 REVIEW_KEPT 알림이 가고 글은 숨긴 채`, 해제로 닫으면 `US4-AC3`과 같고 요청은 `RESTORED`
- [ ] T039 [P] [US4] 웹 단위 테스트: apps/web/src/features/request-review/ui/*.test.tsx(`US4-AC8 누르면 "요청했어요"로 바뀌고 다시 누를 수 없다`, 409도 같은 상태), apps/web/src/app/api/[...path]/route.test.ts에 `operator/**`는 404(`US4-AC6`)
- [ ] T040 [US4] apps/web/e2e-full/safety.spec.ts에 더한다: `US4-AC3`(운영자 토큰으로 API를 불러 해제한 뒤 다른 컨텍스트의 피드에 보임), `US4-AC8`, `US4-AC9`. 운영자는 테스트가 DB에서 지정한다

### Implementation for User Story 4

- [ ] T041 [US4] `safety` application/OperatorQueryService(판정, 신고, 재검토 요청의 키셋 조회, 대상 본문은 `PostModerationApi.contentOf`로 일괄)와 OperatorService(숨기기, 풀기, 신고 닫기, 재검토 닫기, 판정 확인 표시, 낱말 더하기와 빼기. 모두 `moderation_action` 기록. 풀면 `ContentRestored`, 재검토를 닫으면 `ReviewResolved`). presentation/OperatorController(`/api/v1/operator/**`, 요청마다 `MemberApi.isOperator`, 아니면 `NOT_FOUND`). `pendingPaths`에서 운영자 연산 11개를 뺀다
- [ ] T042 [US4] application/ReviewRequestService와 `POST /api/v1/review-requests`. 접수하면 `PostModerationApi.markReviewRequested`로 대상의 `review_requested_at`을 적어, `feed`의 글 상세와 `post`의 댓글 응답이 `safety.reviewRequested`를 자기 열에서 읽는다(단계와 같은 방식, research R7, R11). `pendingPaths`에서 `requestReview`를 뺀다
- [ ] T043 [US4] `notification`: `ContentRestored` → `CONTENT_RESTORED`(멱등 키 `RESTORED:{type}:{id}:{actionId}`), `ReviewResolved(kept)` → `REVIEW_KEPT`(멱등 키 `REVIEW:{requestId}`)
- [ ] T044 [P] [US4] 웹: apps/web/src/features/request-review/(뮤테이션과 버튼), `widgets/safety-banner`에 배치, BFF 프록시가 `operator/**`를 넘기지 않게 한다

**Checkpoint**: 잘못 숨겨진 글을 되돌릴 길이 있다

---

## Phase 7: User Story 5 - 욕설은 가려서 보인다 (Priority: P3)

**Goal**: 욕설이 다른 회원에게 가려져 보인다. 원문은 보관한다

**Independent Test**: 욕설이 든 글과 댓글을 쓰고 다른 회원과 작성자의 화면을 견준다

### Tests for User Story 5 ⚠️

- [ ] T045 [P] [US5] apps/api/src/test/kotlin/com/ogu/safety/application/ProfanityMaskTest.kt(표 형식): `US5-AC1 욕설을 글자 수만큼 *로`, `US5-AC2 사이에 낀 공백과 기호까지 가린다`, `US5-AC3 허용 목록의 낱말 안에 든 욕설은 가리지 않는다`, 여러 번 나오면 모두, 겹치는 낱말은 긴 것 먼저, 결합 이모지 옆에서도 위치가 어긋나지 않는다, 욕설이 없으면 같은 문자열 객체를 돌려준다
- [ ] T046 [P] [US5] apps/api/src/test/kotlin/com/ogu/safety/MaskingApiTests.kt: `US5-AC1 피드 미리보기, 글 상세, 댓글, 내 댓글의 글 앞부분, 알림의 글 앞부분에서 가려진다`, 미리보기는 가린 뒤에 자른다(경계에 걸친 욕설), `US5-AC4 감정 분석과 위험 감지는 원문으로 한다`(욕설과 위기 표현이 함께 든 글), `US5-AC5 작성자에게는 원문`, `US5-AC6 낱말을 더하면 예전 글도 가려지고 빼면 다시 보인다`(캐시 갱신을 테스트에서 직접 부른다), DB의 본문은 바뀌지 않는다
- [ ] T047 [US5] apps/web/e2e-full/safety.spec.ts에 더한다: `US5-AC1`, `US5-AC5`

### Implementation for User Story 5

- [ ] T048 [US5] `safety` application/ProfanityMask(`ContentMask` 구현, TermCache의 욕설과 허용 목록, TextNormalizer의 위치 대응표)
- [ ] T049 [US5] 응답에 적용: `feed`(피드와 내 활동의 미리보기, 글 상세 본문), `post`(댓글 목록과 내 댓글의 본문, 글 앞부분), `notification`(미리보기). 보는 사람이 작성자면 건너뛴다. 미리보기는 가린 뒤 자른다

**Checkpoint**: 다섯 스토리가 모두 동작한다

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T050 [P] `safety` application/SafetyBackfill(research R14): `ApplicationRunner`가 advisory lock을 잡고 `safety_backfill`의 `last_id`부터 `batch-size`씩 `PostModerationApi.scan`으로 훑는다. 위기면 숨기고 `RiskDetected`, AI 분류는 예약하지 않는다. 끝나면 `finished_at`. 테스트: 다시 떠도 한 번만, 중간에 멈췄다 이어서
- [ ] T051 [P] application/SafetyPurgeJob: 1년 지난 `risk_assessment`, `report`, `review_request`, `moderation_action`을 1,000행씩 지운다. 숨김 상태는 그대로다. 테스트(시계 주입)
- [ ] T052 [P] 평가(research R15): apps/api/src/test/resources/safety/eval-set.tsv(위기 50, 우려 50, 위험 없음 100, 직접 쓴 문장), KeywordRuleEvalTest가 재현율과 오탐률을 `EVAL`로 출력하고 "목록에 있는 표현은 모두 잡는다", "위험 없음을 위기로 보는 비율 10% 이하"를 단언한다. 놓친 위기 문장을 보고 시드의 위기, 우려 낱말을 보탠다
- [ ] T053 ContractTests `pendingPaths`가 비어 있고 루트 계약의 모든 연산이 구현과 일치하는지 확인한다. `pnpm --filter web gen:api` 뒤 차이가 없어야 한다
- [ ] T054 [P] `grep -rn "US[1-5]-AC[0-9]*" apps/`로 인수 조건 34개가 모두 테스트 이름에 있는지 확인하고 빠진 것을 더한다
- [ ] T055 [P] 민감 정보 확인(SC-008): 위기 표현이 든 글을 쓰고 판정, 신고, 운영자 조회를 거친 뒤 API 로그에 본문과 걸린 표현이 없는지 테스트로 확인한다(`OutputCaptureExtension`). `event_publication`의 직렬화된 이벤트에도 본문이 없다
- [ ] T056 [P] 문서: apps/api/AGENTS.md에 `safety` 절(모듈 경계, 같은 트랜잭션의 키워드 판정, 조회 조건 둘, `ContentMask`의 배선, 운영자, 재시도), apps/web/docs/ARCHITECTURE.md에 안전 절, docs/architecture/overview.md 5.1 표와 그래프, 5.6을 구현과 맞춘다. README에 한 단락. docs/adr/0005의 "결과"와 004 plan의 Complexity Tracking에 예외가 끝난 날짜를 적는다
- [ ] T057 성능 측정: 글 쓰기와 댓글 쓰기 각 100번의 p95(SC-004, M2 수치와 비교), 욕설이 든 글이 섞인 피드 한 쪽 100번(가리기 비용), 위기 글의 응답에서 상세의 안내까지 20번(SC-003), 숨김 해제에서 다른 회원의 피드까지(SC-006), 신고에서 운영자 조회까지(SC-007). `ScreeningVisibilityTests`를 20번 `--rerun-tasks`(SC-001). 결과를 quickstart.md에 표로 남긴다
- [ ] T058 quickstart.md의 수동 시나리오 35개를 로컬에서 끝까지 실행하고 다르면 문서나 코드를 고친다. AI 포함 평가(SC-005)는 키가 있으면 재고, 없으면 재지 못했다고 적는다
- [ ] T059 일관성을 확인하고 PR을 연다(스펙 링크, 인수 조건 34개 체크리스트, 운영 준비 항목: 도움 리소스 번호 확인, 운영자 지정, 낱말 목록, 이미 있는 글 훑기, AI 사용량)

---

## Dependencies & Execution Order

- **Setup**: T001 → T002. T003은 병렬
- **Foundational**: Setup 뒤. T004 → T005, T006 → T007. T008~T011은 병렬. 모든 스토리를 막는다
- **US1**: Foundational 뒤(MVP). T019 → T020 → T021, T023. T022는 독립. 웹(T024, T025)은 T021의 계약만 있으면 시작할 수 있다
- **US2**: US1 뒤(`ScreeningListener`가 만든 `risk_assessment` 행에 AI 결과를 더한다). T029 → T030 → T031
- **US3**: Foundational 뒤. US1, US2와 독립
- **US4**: US1 뒤(숨긴 글이 있어야 풀 수 있다). 신고 처리는 US3 뒤. T041 → T042, T043
- **US5**: US1 뒤(T019의 TextNormalizer와 TermCache를 쓴다). 다른 스토리와 독립
- **Polish**: 모든 스토리 뒤. T052는 US1 뒤면 언제든

### Parallel Opportunities

- Foundational: T008, T009, T010, T011
- US1 테스트 T012~T017은 모두 다른 파일
- US3(T032~T036)과 US5(T045~T049)는 US1이 끝나면 US2, US4와 함께 진행할 수 있다

## Implementation Strategy

### MVP First

1. Setup → Foundational(기존 테스트가 그대로 통과하는지 확인) → US1
2. **멈추고 검증**: 목록에 있는 위기 표현이 든 글이 한 번도 공개되지 않고(T015), 조회 표가 모두 맞고(T013), 작성자가 안내를 받는다
3. US2(AI와 재시도) → 배포할 수 있는 최소 단위. 이 시점에 ADR-0005의 예외가 끝난다
4. US3(신고) → US4(운영자, 재검토) → US5(욕설 가리기) → 마무리

### Suggested Batches

| 배치 | 작업 | 내용 |
|---|---|---|
| 1 | T001~T005 | 계약, 설정, V5 |
| 2 | T006~T011 | 조회 조건 나누기, 파사드와 이벤트, 모듈 뼈대 |
| 3 | T012~T016, T019~T023 | US1 API |
| 4 | T017, T018, T024, T025 | US1 웹 |
| 5 | T026~T031 | US2 |
| 6 | T032~T036 | US3 |
| 7 | T037, T038, T041~T043 | US4 API |
| 8 | T039, T040, T044 | US4 웹 |
| 9 | T045~T049 | US5 |
| 10 | T050~T055 | 훑기, 정리, 평가, 점검 |
| 11 | T056~T059 | 문서, 측정, 수동 시나리오, PR |

### Notes

- 커밋 메시지와 PR 제목은 Conventional Commits
- `webbb-be/`, `webbb-fe/`는 수정하지 않는다
- 테스트와 문서에 쓰는 위기, 욕설 문장은 지어낸 것만 쓴다. 실제 회원의 글을 옮기지 않는다
- 로컬에서 `docker build`는 `--builder default`를 붙인다(004 기록). e2e는 호스트의 jar로 돌릴 수 있다
- M3 회고: shellcheck와 detekt는 CI와 로컬의 판이 다를 수 있다. PR 전에 CI 결과를 본다
