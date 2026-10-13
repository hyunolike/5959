# Implementation Plan: AI 친구의 활동 넓히기

**Branch**: `012-companion-activity` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-companion-activity/spec.md`

## Summary

두 가지를 더한다. 첫째, 반응 없는 글에 답하는 AI 친구가 한 명에서 세 명이 된다. 글마다 수를 무작위로(기본 60%, 30%, 10%) 한 번 정하고, 첫 친구 뒤로는 앞 댓글에서 3분에서 10분 뒤에 다른 친구가 차례로 답한다. 사람이 답하거나 글이 위험해지거나 사라지면 남은 친구는 멈춘다. 둘째, 최근 6시간 동안 새 글이 없으면 AI 친구 한 명이 지어 쓴 고민 글을 하나 올린다. 하루 3개, 친구마다 하루 하나까지다. 그 글은 어디서나 AI 친구의 글로 표시되고, 다른 AI 친구가 사람의 글과 같은 규칙으로 답한다.

009의 `companion` 모듈 구조를 그대로 잇는다. 반응은 여전히 글마다 `companion_reaction` 한 행을 두고 다투고, 그 행에 정해진 수와 반응한 수를 더한다. 친구별 반응은 `companion_reaction_entry`에 적고 유일 제약이 "한 글에 같은 친구는 한 번"을 지킨다. 글쓰기는 `companion_post`에 친구마다 하루 한 행을 두고, 쓰는 중인 행을 하나로 묶는 부분 유일 색인이 "한 번에 하나"를 지킨다. AI 친구의 글은 `posts`의 행이라(글쓴이의 종류를 스냅숏으로 남긴다) 감정 분석, 몬스터, 위험 판정, 피드가 고치지 않아도 따라온다.

PR 둘로 낸다(research R21). PR 1은 여러 친구의 반응, PR 2는 글쓰기와 표시다. 글쓰기는 꺼진 채로 나간다(`AI_COMPANION_POSTING_ENABLED`). 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI, Resilience4j(`companionPost` 인스턴스 추가). 새 라이브러리는 없다
- 웹: Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. Flyway `V10__companion_multi_reaction.sql`(PR 1), `V11__companion_posts.sql`(PR 2)

**Testing**:
- API: JUnit 5, Testcontainers. 009의 켜진 컨텍스트(`CompanionTestBase`)를 그대로 쓴다. 수를 정해 주는 가짜 `ReactionCountPicker`, 가짜 글쓰기를 더한다. 실제 모델의 글은 켰을 때만 도는 테스트가 출력한다
- 웹: Vitest, Playwright(`e2e-full/ai-companions.spec.ts`에 여러 명 반응을 더한다)
- 배포 설정: `infra/tests/deploy_test.sh`, `infra/gcp/tests`

**Target Platform**: VM의 docker compose(API), Cloud Run(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 뒤따르는 댓글의 95% 이상이 앞 댓글 뒤 3분에서 11분 사이에 올라간다(SC-005). 간격 3분에서 10분, 주기 30초, 댓글 쓰기와 위험 분류의 시간 제한이 20초씩이다
- AI가 답하지 않는 동안에도 회원의 글쓰기, 댓글, 공감의 시간이 평소와 같다(SC-011)
- 피드와 둘러보기의 쿼리 수는 그대로다(4개). 글쓴이의 종류는 글 행에서 읽는다

**Constraints**:
- 반응하는 친구마다 반응하기 직전에 009의 확인을 다시 한다(constitution IV, FR-012)
- AI 친구의 글은 욕설, 키워드 위험, AI 위험 분류를 모두 통과한 뒤에 저장한다. 분류가 실패하면 올리지 않는다(FR-026)
- AI 호출은 사용자 요청 밖, 트랜잭션 밖에서 한다(constitution V)
- 하루에 AI에 댓글을 청하는 횟수는 45번, 글을 청하는 횟수는 9번까지다
- 공급자에 보내는 것: 댓글은 본문, 말투, 감정, 말씨, 앞서 남긴 AI 친구의 댓글. 글은 소재, 말투, 말씨. 회원의 닉네임과 사람의 댓글은 보내지 않는다(FR-010, FR-027)
- 본문과 댓글을 로그, 이벤트, 기록에 남기지 않는다(FR-013, FR-037)
- `posts`, `comments`, `member`를 `companion`에서 직접 읽지 않는다
- 월 인프라 비용 증가 0원

**Scale/Scope**: 인수 조건 58개(US1 10, US2 10, US3 11, US4 7, US5 8, US6 5, US7 6). 새 테이블 2개(`companion_reaction_entry`, `companion_post`), 고치는 테이블 2개(`companion_reaction`, `posts`), 계약은 스키마 둘(새 경로 없음), 파사드 변경(`post`의 `PostCompanionApi`, `ai`에 쓰기 하나), 웹은 글 카드 둘과 글 상세의 표시, 문구 셋

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈이 없다. `companion`의 허용 의존(`shared`, `post`, `safety`, `emotion`, `ai`)은 그대로다. 글을 저장하는 일과 조용한 시간의 기준은 `post`의 파사드(`PostCompanionApi`)로만 한다(R11). `post`는 이미 `member`에 의존한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/companion-activity.openapi.yaml](contracts/companion-activity.openapi.yaml)에 `Author`와 `PublicFeedItem`의 변화를 먼저 적었다. PR 2의 첫 일이 루트 계약에 합치는 것이다(0.13.0). PR 1은 계약을 바꾸지 않는다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 58개에 ID가 있다. 글이 자연스러운지(SC-013)와 실제 모델이 앞 댓글과 다른 것을 짚는지는 자동으로 재지 못해 quickstart에 사람이 읽는 절차로 적었다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 뒤따르는 친구는 차례마다 009의 확인 순서를 처음부터 다시 탄다(R4). AI 친구의 글은 댓글보다 엄격하게 검사한다(R16). 표시는 글 행이 생길 수 있게 되는 PR과 같은 PR로 나간다(R21). 글쓰기는 꺼진 채로 나간다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI 호출은 주기 작업이 트랜잭션 밖에서 한다. 회원의 글쓰기는 여전히 행 하나를 커밋 뒤에 넣을 뿐이다. 실패는 일정 테이블로 세 번까지 다시 한다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너가 없다. 공급자의 사용료는 없다. 호출 한도는 하루의 몫(댓글 45, 글 9)과 시도 횟수로 묶는다(R8, R12) | 통과 |

**Phase 1 설계 후 재확인**:
- `companion_reaction_entry`, `companion_post`는 `companion`이 소유한다. 다른 모듈의 테이블에 FK를 걸지 않고 조인하지 않는다. `posts.author_kind`는 `post`가 소유한다.
- `companion`은 여전히 이벤트를 내지 않는다. AI 친구의 글은 `post`가 사람의 글과 같은 이벤트로 낸다.
- `post`에 두 번째 쓰기 파사드 함수가 생긴다(`PostCompanionApi.write`). 사람의 저장과 같은 엔티티, 같은 이벤트를 쓰는 얇은 길이라 규칙이 두 군데로 갈라지지 않는다. 다른 것은 직군과 경력이 없다는 것과 작성 제한을 타지 않는다는 것뿐이다.
- `post`가 글쓴이의 종류를 알게 된다. 누가 AI 친구인지(반응할 때)는 여전히 `companion`이 번호로 넘긴다. 009의 설명을 AGENTS.md에서 고친다.
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/012-companion-activity/
├── spec.md, plan.md, research.md(R1~R21), data-model.md, quickstart.md
├── contracts/companion-activity.openapi.yaml
├── checklists/requirements.md
└── tasks.md                                       # /speckit-tasks가 만든다
```

### Source Code (repository root)

```text
apps/api/src/main/
├── kotlin/com/ogu/
│   ├── companion/
│   │   ├── application/
│   │   │   ├── CompanionProperties.kt             # countWeights, followUp, dailyLimit 45 / posting.*(PR 2)
│   │   │   ├── ReactionCountPicker.kt             # 인터페이스, WeightedReactionCountPicker, e2e의 MarkerReactionCountPicker
│   │   │   ├── ReactionStore.kt                   # enroll(수 정하기), recordReaction(다음 차례 잡기), close
│   │   │   ├── ReactionRunner.kt                  # 확인 순서(R4): 맞추기, 닫기, 친구 고르기, 남기기
│   │   │   ├── ReactionEligibility.kt             # AlreadyReacted를 지우고 대상만 돌려준다
│   │   │   ├── CommentComposer.kt                 # previous를 싣는다. CommentGate에 되풀이 검사
│   │   │   ├── DailyBudget.kt                     # CompanionPicker: 글에 남기지 않은, 글쓴이가 아닌 친구
│   │   │   ├── PostingRunner.kt, PostingStore.kt, PostingScheduler.kt   # PR 2: 글 쓰는 주기 작업(R12)
│   │   │   ├── PostComposer.kt                    # PR 2: 글을 청하고 올리기 전 검사(R16)
│   │   │   └── PostTopics.kt                      # PR 2: 소재 서른 개(R14)
│   │   └── domain/
│   │       ├── CompanionReactionRepository.kt     # target_count, done_count, entry, close
│   │       └── CompanionPostRepository.kt         # PR 2
│   ├── ai/
│   │   ├── CompanionCommentWriter.kt              # CompanionCommentInput.previous
│   │   ├── CompanionPostWriter.kt                 # PR 2: CompanionPostInput(topic, tone, voice), CompanionPostFailed
│   │   └── infrastructure/
│   │       ├── SpringAiCompanionCommentWriter.kt, FakeCompanionCommentWriter.kt   # 앞 댓글, 친구마다 다른 문장
│   │       ├── SpringAiCompanionPostWriter.kt, FakeCompanionPostWriter.kt         # PR 2
│   │       ├── CompanionPostValidator.kt, GeneratedTextRules.kt                   # PR 2
│   │       └── AiAnalyzerConfig.kt
│   ├── post/
│   │   ├── PostCompanionApi.kt                    # companionComments, FULL, limit / write, latestVisiblePostAt, findWrittenSince(PR 2)
│   │   ├── PostSummary.kt                         # PR 2: authorAi, 직군과 경력 nullable
│   │   ├── domain/Post.kt, AuthorKind.kt          # PR 2
│   │   └── application/PostCompanionService.kt, PostService.kt, PostRows.kt,
│   │       PostReportReader.kt, PostQueryService.kt                               # PR 2: 사람의 글만(R18)
│   ├── feed/
│   │   ├── application/PostViews.kt, PublicFeedQuery.kt                           # PR 2: ai
│   │   └── presentation/dto/AuthorResponse.kt, PublicFeedResponse.kt              # PR 2
│   └── shared/config/AiProperties.kt              # PR 2: companionPostMaxTokens
└── resources/
    ├── db/migration/V10__companion_multi_reaction.sql, V11__companion_posts.sql
    ├── prompts/companion-comment-v2.st, companion-post-v1.st
    └── application.yml, application-e2e.yml

apps/api/src/test/kotlin/com/ogu/
├── companion/                                     # 여러 명 반응, 멈추기, 한 번만, 수의 비율 / 글쓰기, 표시, 수치
└── support/TestAiConfiguration.kt, CompanionFixture.kt

apps/web/src/
├── app/ai-friends/page.tsx, app/write/page.tsx    # 안내 문구
├── entities/post/ui/post-card.tsx, public-post-card.tsx       # PR 2: 직군과 경력 자리에 AI 친구
├── entities/post/model/types.ts                               # PR 2
├── widgets/post-detail/ui/post-detail.tsx, similar-posts.tsx  # PR 2: 표시와 지어 쓴 이야기 안내
└── shared/api/generated.ts                                    # PR 2: 계약에서 다시 만든다

apps/web/e2e-full/ai-companions.spec.ts
contracts/openapi.yaml                             # PR 2: 0.13.0
infra/compose.prod.yaml, infra/gcp/vm/compose.yaml, infra/gcp/vm/runtime.py, infra/.env.example,
infra/gcp/terraform/variables.tf, main.tf, terraform.tfvars.example, 그 테스트들
docs/architecture/overview.md, apps/api/AGENTS.md, specs/009-ai-companions/spec.md(이 스펙을 가리키는 줄)
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 새 모듈 없이 `companion`을 넓히고 `post`, `ai`, `feed`에 필드와 함수를 더한다. 웹은 새 슬라이스 없이 글 카드와 글 상세에 009의 `AiBadge`를 붙인다.

## 구현 순서

| 묶음 | 내용 | 스토리 | PR |
|---|---|---|---|
| 1 | V10 마이그레이션, 설정(무게, 간격, 하루의 몫 45), `ReactionCountPicker` | 기반 | 1 |
| 2 | `PostCompanionApi`: `companionComments`, 이 회원이 남겼는지, `FULL`, `limit` | US1, US2 | 1 |
| 3 | 반응: 수 정하기, 기록 맞추기, 친구 고르기, 남긴 뒤 다음 차례 잡기, 닫기 | US1 | 1 |
| 4 | 멈추기: 사람의 댓글, 사라진 글, 위험, 24시간, 하루의 몫, 세 번 실패. 동시 실행 | US2 | 1 |
| 5 | 앞 댓글 보내기와 되풀이 검사, 프롬프트 v2, 가짜 쓰기 | US1 | 1 |
| 6 | 안내 문구(웹), 배포 설정의 기본값과 새 환경 변수, e2e, 문서 | US4, US7 | 1 |
| 7 | 계약 합치기(0.13.0)와 웹 타입, V11, `posts.author_kind`, 응답의 `ai` | 기반, US4 | 2 |
| 8 | 웹: 글 카드, 둘러보기 카드, 글 상세, 비슷한 글의 표시와 안내 | US4 | 2 |
| 9 | `ai`: `CompanionPostWriter`와 실제, 가짜 구현, 프롬프트, 검증기 | US3, US5 | 2 |
| 10 | 글쓰기: 조건, 행 만들기, 맡기, 올리기 전 검사, 저장, 다시 시도 | US3, US5 | 2 |
| 11 | 수치에서 빼기(리포트, 레이드), AI 친구의 글에 달리는 반응 | US5, US6 | 2 |
| 12 | 켜고 끄기와 배포 설정, 운영 기록, 실제 모델의 글 30개 출력 | US7 | 2 |
| 13 | 인수 조건 점검, 문서(overview, AGENTS.md), quickstart 실행 | 마무리 | 2 |

묶음 7과 8은 묶음 10과 같은 PR로 나가야 한다. AI 친구의 글이 생길 수 있는데 표시가 없는 상태를 운영에 두지 않는다.

## Complexity Tracking

위반이 없다.
