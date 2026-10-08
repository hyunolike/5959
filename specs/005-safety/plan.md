# Implementation Plan: 위험 감지와 안전장치

**Branch**: `005-safety` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-safety/spec.md`

## Summary

글과 댓글에서 위기 신호를 감지해 위기면 작성자 말고는 보이지 않게 숨기고, 작성자에게 도움받을 곳을 안내한다. 회원은 글과 댓글을 신고하고, 숨겨진 글의 작성자는 재검토를 요청한다. 운영자는 판정, 신고, 재검토 요청을 API로 조회하고 처리한다. 욕설은 원문을 보관한 채 보여 줄 때 가린다.

API에는 `safety` 모듈을 추가한다. 글이 저장되는 트랜잭션 안에서 키워드 규칙이 먼저 판정해, 목록에 있는 위기 표현이 든 글은 한 번도 공개되지 않는다. AI 분류는 커밋 뒤 비동기로 돌고 M2의 감정 분석과 같은 방식의 일정 테이블로 재시도하며, 끝내 실패하면 키워드 판정이 최종이 된다. 숨김 상태는 `post`가 소유하고 `safety`가 새 파사드로 숨기고 푼다. 욕설 가리기는 `shared`의 인터페이스를 `safety`가 구현해, `post`가 `safety`를 모른 채로 댓글 본문을 가린다. 위험 감지 결과는 M3의 알림 체계에 종류 셋을 더해 전달하고, 숨긴 글은 알림에서 지운 글처럼 보인다(ADR-0005의 약속).

웹은 글 상세의 도움 안내와 숨김 설명, 신고 대화상자, 재검토 요청, 가려진 댓글 자리를 만든다. 운영자 화면은 없다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI(M2와 같은 OpenAI 호환 엔드포인트), Resilience4j(서킷 브레이커 `riskClassifier` 추가). 새 의존성은 없다
- 웹: Next.js 16, TanStack Query 5, React Hook Form, Zod. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. Flyway `V5__safety.sql`로 `posts`, `comments`에 숨김과 단계 열, `member.role`, 피드 부분 인덱스 재생성, 새 테이블 7개(`risk_assessment`, `report`, `review_request`, `moderation_action`, `safety_term`, `support_resource`, `safety_backfill`)와 낱말, 도움 리소스 시드를 만든다. Redis는 M3 그대로이고 새로 쓰지 않는다

**Testing**:
- API: JUnit 5, MockMvc, Testcontainers(Postgres, Redis). 정규화와 가리기는 표 형식 단위 테스트. 판정과 숨김은 통합 테스트(저장 응답 직후 다른 회원의 조회). AI 실패는 `FakeRiskClassifier`의 머리말, 재시도 일정은 시계 주입. 조회 경로와 보는 사람을 곱한 숨김 표 테스트. 평가용 문장 묶음으로 키워드 규칙의 성적 출력
- 웹: Vitest, Testing Library, Playwright(`e2e-full/safety.spec.ts`, 브라우저 컨텍스트 둘)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API, Caddy 뒤), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 목록에 있는 위기 표현이 든 글이 다른 회원에게 보이는 일 0건(SC-001)
- 키워드로 판정된 글의 작성자는 3초 안에 도움 리소스를 본다(SC-003)
- 글과 댓글 쓰기 p95 300ms 이하를 지킨다(SC-004, M2의 목표)
- 숨김 해제가 5초 안에 다른 회원에게 반영된다(SC-006), 신고가 1초 안에 운영자 조회에 나타난다(SC-007)

**Constraints**:
- AI 분류가 실패해도 쓰기는 성공하고 감지는 빠지지 않는다(constitution IV, V)
- 로그, 이벤트, 오류 수집에 본문과 걸린 표현을 남기지 않는다(SC-008). `risk_assessment`에도 저장하지 않는다
- 월 인프라 비용 증가 0원(SC-009). AI 호출은 글과 댓글마다 한 번씩 늘어난다(사용료는 constitution VI의 예외 범위)
- `post`는 `safety`를 모른다. 모듈 순환을 만들지 않는다
- 숨긴 글과 댓글은 다른 회원의 모든 조회에서 지운 것과 같이 다룬다. 조건이 한 곳이라도 빠지면 위기 글이 새어 나간다

**Scale/Scope**: 인수 조건 34개(US1 8, US2 5, US3 6, US4 9, US5 6). 새 모듈 1개, 새 테이블 7개, 바뀌는 테이블 4개, 계약 연산 16개 추가와 기존 스키마 4개 변경. `deleted_at IS NULL`을 거는 기존 조회 30여 곳을 보이는 것만과 작성자 포함 둘로 나눈다

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `safety`는 `post`, `ai`, `member`, `shared`에만 의존하고 `notification → safety`가 더해진다. overview 5.1의 그래프와 같다. `post`가 `safety`의 가리기를 쓰는 길은 `shared`의 인터페이스로 뒤집어 `post → safety`를 만들지 않는다(R6). `ModularityTests`가 확인한다. 웹의 새 슬라이스는 steiger가 검사한다. M3에서 `insignificant-slice`에 걸린 경험이 있어 `entities/safety`는 위젯과 feature 둘 이상이 쓰도록 잡았다(R16) | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/safety.openapi.yaml](contracts/safety.openapi.yaml)에 연산 16개와 기존 스키마의 변경을 먼저 적었다. 구현 첫 작업에서 루트 계약에 합치고 ContractTests의 `pendingPaths`에 올린 뒤 스토리마다 뺀다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 34개에 ID를 붙였다. SC-005의 AI 포함 성적과 실제 전화 연결은 자동화할 수 없어 quickstart의 수동 절차에 둔다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 이 마일스톤이 그 원칙의 구현이다. AI가 실패해도 키워드 규칙이 같은 트랜잭션에서 판정하고(R2), AI는 그 위에 더할 뿐 낮추지 못한다(US2-AC2). M3가 남긴 예외(ADR-0005)는 `PostApi.find`, `previews`가 숨긴 글을 거르면서 끝난다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI 분류는 커밋 뒤 비동기이고 일정 테이블로 재시도한다. 같은 트랜잭션에서 도는 것은 메모리 안의 키워드 규칙뿐이다. 낱말 목록을 읽지 못해도 마지막 목록이나 내장 목록으로 판정해 글 저장을 막지 않는다(R2) | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너와 외부 서비스가 없다. AI 호출이 늘어나는 만큼의 사용료만 든다 | 통과 |

**Phase 1 설계 후 재확인**:
- 새 테이블 7개는 모두 `safety`가 소유한다. 숨김과 단계 열은 `post`의 테이블에 있고 `post`만 쓴다(`PostModerationApi`의 구현). `member.role`은 `member`가 소유한다.
- 이벤트 방향이 의존 그래프와 같다. `safety`는 `post`의 이벤트 넷을 받고, `notification`은 `safety`의 이벤트 셋을 받는다.
- `PostApi.previews`의 시그니처가 바뀐다(`viewerId` 추가). 쓰는 곳은 `notification` 하나다.
- 계약의 모든 JSON 응답이 `ApiResponse` 봉투를 따른다. 접수만 하는 요청은 204다.
- 운영자 경로는 요청마다 역할을 DB에서 확인하고, 운영자가 아니면 404다. BFF는 넘기지 않는다(R10).
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/005-safety/
├── spec.md
├── plan.md                    # 이 문서
├── research.md                # 결정 R1~R17
├── data-model.md              # 바뀌는 테이블 4개, 새 테이블 7개, 이벤트, 파사드, 의존 그래프
├── quickstart.md              # 수동 시나리오, 평가 절차, 운영 준비
├── contracts/
│   └── safety.openapi.yaml    # 이번 추가분(연산 16개). 구현 때 루트 contracts/openapi.yaml에 합친다
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks가 만든다
```

### Source Code (repository root)

```text
apps/api/src/main/kotlin/com/ogu/
├── safety/                                      # 새 모듈
│   ├── RiskLevel.kt, TargetType.kt
│   ├── RiskDetected.kt, ContentRestored.kt, ReviewResolved.kt
│   ├── application/
│   │   ├── ScreeningListener.kt                 # PostWritten, CommentWritten 동기 수신: 키워드 판정, 숨김, 일정 행
│   │   ├── RiskAssessmentService.kt             # AI 분류 반영, 단계 올리기, 이벤트 발행
│   │   ├── RiskRetryScheduler.kt                # 차례가 된 PENDING 다시 시도, 24시간 뒤 FALLBACK
│   │   ├── KeywordRule.kt, TextNormalizer.kt    # 정규화와 단계 판정(순수 함수)
│   │   ├── ProfanityMask.kt                     # shared ContentMask의 구현
│   │   ├── TermCache.kt                         # 낱말 목록 메모리 캐시(30초마다 변경 확인)
│   │   ├── ReportService.kt, ReportRateLimit.kt
│   │   ├── ReviewRequestService.kt
│   │   ├── OperatorService.kt                   # 숨김, 해제, 신고와 재검토 닫기, 처리 기록
│   │   ├── OperatorQueryService.kt              # 판정, 신고, 재검토 조회(키셋)
│   │   ├── RemovalListener.kt                   # PostRemoved, CommentRemoved: 열린 신고와 재검토 닫기
│   │   ├── SafetyBackfill.kt                    # 이미 있는 글 한 번 훑기
│   │   └── SafetyPurgeJob.kt                    # 1년 지난 기록 정리
│   ├── domain/                                  # RiskAssessment, Report, ReviewRequest, ModerationAction, SafetyTerm, SupportResource
│   └── presentation/  SafetyController(support-resources, reports, review-requests), OperatorController(/api/v1/operator/**)
├── post/
│   ├── PostApi.kt                               # find, findComment는 보이는 것만. previews(postIds, viewerId)
│   ├── PostModerationApi.kt                     # contentOf, markRisk, hide, unhide, scan (새 파사드)
│   ├── PostWritten.kt, CommentWritten.kt, PostRemoved.kt, CommentRemoved.kt
│   ├── application/Visibility.kt                # 조회 조건 둘(VISIBLE, OWNED_OR_VISIBLE)
│   └── application/ ...                         # 기존 조회 30여 곳을 Visibility로 바꾼다
├── ai/
│   ├── RiskClassifier.kt, RiskClassification.kt, RiskClassificationFailed.kt
│   └── infrastructure/ SpringAiRiskClassifier, FakeRiskClassifier(e2e), DisabledRiskClassifier, risk 프롬프트
├── member/
│   ├── MemberApi.kt                             # isOperator
│   └── domain/Member.kt                         # role
├── notification/
│   ├── domain/NotificationType.kt               # SUPPORT_NOTICE, CONTENT_RESTORED, REVIEW_KEPT
│   └── application/NotificationEventListener.kt # safety 이벤트 셋 수신
├── feed/                                        # 글 상세와 내 글 목록에 safety, hidden. 본문과 미리보기에 ContentMask
└── shared/text/ContentMask.kt                   # 인터페이스와 아무것도 가리지 않는 기본 구현

apps/api/src/main/resources/
├── db/migration/V5__safety.sql
└── prompts/risk-classification.st

apps/api/src/test/resources/safety/eval-set.tsv  # 위기 50, 우려 50, 위험 없음 100

apps/web/src/
├── app/post/[id]/page.tsx                       # 안전 배너 자리
├── entities/
│   ├── safety/         types, api/use-support-resources-query, ui/support-resources
│   ├── comment/        ui/comment-item(가려진 댓글 자리, 내 숨긴 댓글 표시)
│   ├── post/           ui/post-card(내 글 목록의 숨김 표시)
│   └── notification/   model/message.ts(새 종류 셋의 문구)
├── features/
│   ├── report-content/ 신고 대화상자, 사유 스키마, 뮤테이션
│   └── request-review/ 재검토 요청 버튼과 뮤테이션
├── widgets/
│   ├── safety-banner/  글 상세의 숨김 설명, 도움 안내(위기는 고정, 우려는 접기), 재검토 요청
│   ├── post-detail/    신고 메뉴, 배너 배치
│   └── notification-bell/ SUPPORT_NOTICE 토스트는 8초
├── shared/lib/scrub-event.ts                    # 신고와 글쓰기 경로의 폼 값을 breadcrumb에서 지운다
└── app/api/[...path]/route.ts                   # operator/** 직접 전달 거부
apps/web/e2e-full/safety.spec.ts
```

**Structure Decision**: 기존 모노레포 구조를 그대로 쓴다. API는 `safety` 모듈 하나를 더하고 `post`, `ai`, `member`, `notification`, `feed`, `shared`를 고친다. 웹은 FSD 슬라이스 넷(`entities/safety`, `features/report-content`, `features/request-review`, `widgets/safety-banner`)을 더한다.

## 구현 순서

스토리 우선순위(P1 → P3)와 의존을 따라 묶는다. 자세한 작업은 `/speckit-tasks`가 만든다.

1. **기반**: 계약을 루트에 합치기, `V5__safety.sql`, `Visibility`로 기존 조회 나누기(숨김 열은 생겼지만 아직 아무것도 숨기지 않는다. 기존 테스트가 그대로 통과해야 한다), `PostModerationApi`, 새 이벤트 넷, `ContentMask` 인터페이스와 기본 구현.
2. **US2의 절반과 US1(키워드로 숨기기)**: 정규화와 `KeywordRule`, `TermCache`, `ScreeningListener`, 숨긴 글의 조회 표 테스트, 글 상세의 `safety` 필드, 도움 리소스 API, `RiskDetected`와 `SUPPORT_NOTICE` 알림, `previews`의 작성자 예외.
3. **US2의 나머지(AI와 재시도)**: `RiskClassifier`와 가짜 분류기, `risk_assessment` 일정과 재시도, 수정 때 다시 판정, 늦게 온 결과 버리기.
4. **US1 웹**: 안전 배너, 가려진 댓글 자리, 내 글 목록의 숨김 표시, 새 알림 문구와 토스트.
5. **US3 신고**: API와 웹.
6. **US4 운영자**: 역할, 조회, 처리, 처리 기록, 재검토 요청(API와 웹), `CONTENT_RESTORED`와 `REVIEW_KEPT` 알림.
7. **US5 욕설 가리기**: `ProfanityMask`, 세 모듈의 응답에 적용, 작성자 예외.
8. **마무리**: 이미 있는 글 훑기, 1년 정리 작업, 평가용 문장 묶음과 성적, 성능 측정, 문서(overview 5.6, AGENTS.md, ARCHITECTURE.md, ADR-0005에 예외가 끝난 날짜), quickstart 실행.

1번의 조회 나누기가 가장 위험하다. 조건을 하나라도 빠뜨리면 위기 글이 보인다. 그래서 2번에서 "모든 조회 경로 × 보는 사람(작성자, 다른 회원)" 표 테스트를 먼저 쓰고 조건을 채운다.

## Complexity Tracking

위반이 없어 비워 둔다.
