# Implementation Plan: AI 친구

**Branch**: `009-ai-companions` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-ai-companions/spec.md`

## Summary

글이 올라온 뒤 10분 동안 다른 회원의 댓글이 없으면 AI 친구 한 명이 댓글 하나와 공감 하나를 남긴다. AI 친구는 세 명이고 닉네임 옆에 언제나 "AI 친구"라고 표시된다. 위기나 우려로 판정된 글, 숨긴 글, 판정이 끝나지 않은 글은 건너뛰고, AI가 쓴 댓글은 올리기 전에 욕설과 위험 검사를 거친다.

API에는 `companion` 모듈을 추가한다. AI 친구는 `member`의 행이고(`kind = 'AI'`), 로그인 수단이 없다. 글마다 `companion_reaction`에 행을 하나 두고, 30초마다 도는 주기 작업이 차례가 된 행을 맡아 대상인지 확인하고, AI에 댓글을 청하고, 검사한 뒤 `post`의 파사드로 댓글과 공감을 한 트랜잭션에 남긴다. 댓글과 공감은 사람이 쓰는 서비스를 그대로 타므로 몬스터 HP, 알림, 위험 판정이 고치지 않아도 따라온다. "한 글에 한 번"은 유일 제약이, 하루의 몫(AI에 댓글을 청하는 횟수 30번)은 조건부 갱신 한 문장이 지킨다.

기능은 꺼진 채로 나간다(`AI_COMPANION_ENABLED`). 저장소 소유자가 실제 모델의 댓글을 읽어 본 뒤 켠다.

웹은 댓글과 알림에 AI 표시를 더하고, AI 친구 안내 화면과 닉네임 안내 하나를 더한다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI(채팅 클라이언트, 편지 쓰기와 같다), Resilience4j(`companionComment` 인스턴스 추가). 새 라이브러리는 없다. ShedLock은 넣지 않는다(R4)
- 웹: Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. Flyway `V9__ai_companions.sql`로 `member.kind`와 제약, AI 회원 셋, `companion`, `companion_reaction`, `companion_daily_usage`를 만든다

**Testing**:
- API: JUnit 5, Testcontainers. 시계를 주입하고 주기 작업을 끈 채 직접 부른다. 가짜 댓글 쓰기가 답과 실패를 정한다. 실제 `post`, `monster`, `notification`, `safety`가 함께 도는 통합 테스트로 HP와 알림을 본다. 실제 모델의 댓글은 켰을 때만 도는 테스트가 출력한다
- 웹: Vitest, Playwright(`e2e-full/ai-companions.spec.ts`)

**Target Platform**: VM의 docker compose(API), Cloud Run(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 대상 글의 95% 이상이 올라온 지 15분 안에 반응을 받는다(SC-001). 기다리는 시간 10분, 주기 30초, 댓글 쓰기와 위험 분류의 시간 제한이 20초씩이다
- AI가 답하지 않는 동안에도 글쓰기와 사람의 댓글, 공감의 시간이 평소와 같다(SC-006)

**Constraints**:
- 위기나 우려, 숨김, 판정 대기 글에는 AI를 부르지 않는다(constitution IV, FR-007)
- AI의 댓글은 판정한 뒤에 저장한다. 검사에 걸린 댓글은 한 번도 보이지 않는다(SC-005)
- AI 호출은 사용자 요청 밖, 트랜잭션 밖에서 한다(constitution V)
- 하루에 AI에 댓글을 청하는 횟수는 30번까지다. 공급자 호출은 하루 최대 90번이다(R8)
- 공급자에 본문, 말투, 감정만 보낸다. 본문과 댓글을 로그, 이벤트, 기록에 남기지 않는다(FR-012, FR-020)
- `posts`, `comments`, `risk_assessment`, `member`를 `companion`에서 직접 읽지 않는다
- 월 인프라 비용 증가 0원

**Scale/Scope**: 인수 조건 31개(US1 9, US2 5, US3 8, US4 4, US5 5). 새 모듈 1개, 새 테이블 3개와 `member`의 열 하나, 계약은 스키마 셋과 오류 코드 하나(새 경로 없음), 파사드 추가(`post` 1, `safety` 1, `ai` 1, `member`의 필드 1), 웹은 표시 하나와 화면 하나, 문구 둘

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `companion`은 `shared`, `post`, `safety`, `emotion`, `ai`에 의존하고, `companion`에 의존하는 모듈은 없다. 다른 모듈의 데이터는 새 파사드(`PostCompanionApi`, `RiskApi`)로만 읽고 쓴다(R1, R5, R7). `ModularityTests`의 허용 표에 더한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/ai-companions.openapi.yaml](contracts/ai-companions.openapi.yaml)에 `CommentAuthor`, `NotificationActor.ai`, `NicknameAvailability.reason`의 `RESERVED`, `NICKNAME_RESERVED`를 먼저 적었다. 구현의 첫 일이 루트 계약에 합치는 것이다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 31개에 ID가 있다. 말투에 맞는지(US4-AC1의 질), 대신 욕해주기의 선(US3-AC8)은 자동으로 재지 못해 quickstart에 사람이 읽는 절차로 적었다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위기, 우려, 숨김, 판정 대기 글은 AI를 부르기 전에 걸러진다(R4). AI의 댓글은 모양, 규칙, 욕설, 키워드 위험, AI 위험 분류를 통과해야 저장된다(R6). AI 분류가 실패하면 키워드 규칙이 대신한다. 기능은 꺼진 채로 나간다(R12) | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI 호출은 주기 작업이 트랜잭션 밖에서 한다. 글쓰기는 행 하나를 커밋 뒤에 넣을 뿐 AI를 기다리지 않는다. 실패는 일정 테이블로 세 번까지 다시 한다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너가 없다. 공급자의 사용료는 없다. 호출 한도는 하루의 몫과 글마다의 시도 횟수로 묶는다(R8) | 통과 |

**Phase 1 설계 후 재확인**:
- `companion`, `companion_reaction`, `companion_daily_usage`는 `companion`이 소유한다. 다른 모듈의 테이블에 FK를 걸지 않고 조인하지 않는다. AI 회원 행은 `member`가 소유한다.
- `companion`은 이벤트를 내지 않는다. `post.PostCreated`를 받고, 댓글과 공감은 `post`의 이벤트로 나간다.
- `safety`에 처음으로 조회 파사드가 생긴다(`RiskApi`). `post`는 여전히 `safety`를 모른다.
- `post`에 처음으로 쓰기 파사드가 생긴다(`PostCompanionApi.react`). 사람의 서비스(`CommentService.write`, `LikeService.likePost`)를 그대로 부르는 얇은 겉이라 규칙이 두 군데로 갈라지지 않는다.
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/009-ai-companions/
├── spec.md, plan.md, research.md(R1~R14), data-model.md, quickstart.md
├── contracts/ai-companions.openapi.yaml
├── checklists/requirements.md
└── tasks.md                                       # /speckit-tasks가 만든다
```

### Source Code (repository root)

```text
apps/api/src/main/
├── java/com/ogu/companion/package-info.java       # allowedDependencies: shared, post, safety, emotion, ai
├── kotlin/com/ogu/
│   ├── companion/
│   │   ├── application/
│   │   │   ├── CompanionProperties.kt, CompanionConfig.kt
│   │   │   ├── ReactionEnrollListener.kt          # PostCreated(커밋 뒤) → WAITING 행
│   │   │   ├── ReactionStore.kt                   # 맡기, 미루기, 실패 적기, 닫기(짧은 트랜잭션들)
│   │   │   ├── ReactionRunner.kt                  # 한 행의 순서(R4). AI 호출은 트랜잭션 밖
│   │   │   ├── ReactionEligibility.kt             # 글의 상태와 위험 판정으로 대상인지 정한다
│   │   │   ├── CommentGate.kt                     # 욕설, 키워드 위험, AI 위험 분류(R6)
│   │   │   ├── DailyBudget.kt                     # 하루의 몫 받기와 돌려주기(R8)
│   │   │   ├── CompanionPicker.kt                 # 가장 오래 쉰 친구
│   │   │   └── ReactionScheduler.kt               # @Scheduled, enabled와 scheduler-enabled를 본다
│   │   └── domain/
│   │       ├── CompanionRepository.kt, CompanionReactionRepository.kt, DailyUsageRepository.kt
│   │       └── Voice.kt, SkipReason.kt
│   ├── ai/
│   │   ├── CompanionCommentWriter.kt              # CompanionCommentInput(content, tone, emotion, voice), CompanionCommentFailed
│   │   └── infrastructure/SpringAiCompanionCommentWriter.kt, FakeCompanionCommentWriter.kt,
│   │       CompanionCommentValidator.kt           # 모양과 규칙(R6)
│   ├── post/
│   │   ├── PostCompanionApi.kt, CompanionTarget.kt, CompanionReactionResult.kt
│   │   └── application/PostCompanionService.kt    # target, react(CommentService와 LikeService를 부른다)
│   ├── safety/
│   │   ├── RiskApi.kt, PostRisk.kt
│   │   └── application/RiskQueryService.kt        # 가장 새 판정 행, KeywordRule
│   ├── member/
│   │   ├── MemberInfo.kt                          # ai
│   │   ├── domain/Member.kt, MemberKind.kt, AuthMethod.kt(NONE), Nickname.kt, ReservedNickname.kt
│   │   └── application/OnboardingService.kt, ProfileService.kt   # RESERVED, NICKNAME_RESERVED
│   ├── post/presentation/dto/CommentResponses.kt  # CommentAuthorResponse.ai
│   ├── notification/
│   │   ├── application/NotificationWriter.kt      # 받는 회원이 AI면 쓰지 않는다(R10)
│   │   └── presentation/dto/NotificationResponse.kt   # NotificationActorResponse.ai
│   └── shared/config/AiProperties.kt              # companionMaxTokens, companionTemperature
└── resources/
    ├── db/migration/V9__ai_companions.sql
    ├── prompts/companion-comment-v1.st            # 규칙, 말투 넷, 말씨 셋
    └── application.yml, application-e2e.yml       # ogu.companion.*, resilience4j companionComment

apps/api/src/test/kotlin/com/ogu/
├── companion/                                     # 반응, 건너뛰기, 한 번만, 몫, 켜고 끄기
├── CompanionMigrationTests.kt
├── ModularityTests.kt                             # 허용 표에 companion
└── support/TestAiConfiguration.kt                 # ScriptedCompanionCommentWriter

apps/web/src/
├── entities/member/ui/ai-badge.tsx                # 글자 "AI 친구". 안내 화면으로 가는 링크(ui 폴더를 새로 둔다)
├── entities/member/model/profile-schema.ts        # RESERVED 안내
├── entities/comment/ui/comment-item.tsx           # 닉네임 옆 표시, 댓글의 이름
├── entities/notification/model/message.ts         # AI 친구가 한 일임을 문구에(목록과 토스트 공용)
├── widgets/post-editor/                           # 글쓰기 화면의 한 줄 안내(R13)
├── app/ai-friends/page.tsx, layout.tsx            # AI 친구 안내
└── shared/api/generated.ts                        # 계약에서 다시 만든다

apps/web/e2e-full/ai-companions.spec.ts
contracts/openapi.yaml                             # 0.9.0
infra/compose.prod.yaml, infra/gcp/vm/compose.yaml, infra/gcp/vm/runtime.py, infra/.env.example
docs/architecture/overview.md, apps/api/AGENTS.md  # companion 절, 5.1의 표와 그래프
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 모듈 하나를 더하고 `post`, `safety`, `ai`, `member`에 파사드나 필드를 하나씩 더한다. 웹은 새 슬라이스 없이 있는 엔티티에 표시를 더하고 화면 하나를 더한다.

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기(0.9.0)와 웹 타입 다시 만들기, V9 마이그레이션, `member.kind`와 `MemberInfo.ai`, `companion` 모듈 뼈대와 설정 | 기반 |
| 2 | 응답의 AI 표시(`CommentAuthor.ai`, `NotificationActor.ai`), AI 회원에게 알림을 만들지 않기, 닉네임 예약 | US2 |
| 3 | 파사드: `PostCompanionApi`(대상 읽기, 댓글과 공감 남기기), `RiskApi`(판정이 끝났는지, 키워드 단계) | US1, US3 |
| 4 | `ai`: `CompanionCommentWriter`와 실제, 가짜 구현, 프롬프트, 모양과 규칙 검사 | US4, US3 |
| 5 | 반응: 행 넣기, 맡기, 대상 확인, 올리기 전 검사, 남기기, 다시 시도. 통합 테스트로 HP와 알림 | US1, US3 |
| 6 | 하루의 몫, 켜고 끄기, 24시간 기한, 친구 고르기, 운영 기록 | US5, US4 |
| 7 | 웹: 댓글과 알림의 표시, 안내 화면, 글쓰기의 한 줄 안내, 닉네임 안내, e2e | US2 |
| 8 | 배포 설정(compose 둘, `runtime.py`), 실제 모델의 댓글 40개 출력, 측정 | 마무리 |
| 9 | 인수 조건 점검, 문서(overview, AGENTS.md), quickstart 실행, PR | 마무리 |

묶음 2는 AI 회원 행이 생기는 묶음 1과 같은 PR로 나가야 한다. AI 회원이 있는데 표시가 없는 상태를 운영에 두지 않는다(스펙 US2의 까닭). 주기 작업은 꺼져 있어 묶음 5가 나가기 전에는 AI 친구가 아무것도 쓰지 않는다.

## Complexity Tracking

위반이 없다.
