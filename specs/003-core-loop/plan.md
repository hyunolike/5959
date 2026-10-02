# Implementation Plan: 핵심 루프 (고민, 감정 몬스터, 공감과 댓글)

**Branch**: `003-core-loop` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-core-loop/spec.md`

## Summary

회원이 고민을 쓰면 바로 저장하고, AI가 비동기로 감정과 강도를 분석해 몬스터를 만든다. 다른 회원은 피드에서 글을 찾아 공감(−1), 첫 댓글(−3), 댓글 공감(−1)으로 몬스터를 공격하고, HP가 0이면 처치된다.

API에는 `post`, `ai`, `emotion`, `monster`, `feed` 모듈을 추가한다. 분석은 `emotion_analysis` 테이블로 재시도 일정을 관리한다. 공격은 같은 트랜잭션 안에서 글 단위 잠금과 `monster_hp_log`의 유일 키로 정확히 한 번 반영하고, 분석 전 공격은 몬스터 생성 때 소급 반영한다.

웹은 글쓰기, 피드, 글 상세 화면과 React Three Fiber 3D 몬스터를 만든다. 피드는 미리 렌더링한 정지 이미지를 쓴다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI 2.0.1(OpenAI 호환 클라이언트), Resilience4j 2.4.0(`resilience4j-spring-boot4`)
- 웹: Next.js 16, TanStack Query 5, React Hook Form, Zod, three 0.186, @react-three/fiber 9, @react-three/drei 10

**Storage**: PostgreSQL 17. Flyway `V3__core_loop.sql`로 `posts`, `comments`, `post_likes`, `comment_likes`, `emotion_analysis`, `monsters`, `monster_hp_log` 테이블을 만든다

**Testing**:
- API: JUnit 5, `@ApplicationModuleTest`, MockMvc, Testcontainers, 동시성 테스트(100 스레드), 시계 주입
- 웹: Vitest, Testing Library, Playwright(`e2e-full`)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 피드 첫 페이지와 다음 페이지 p95 1초 이하(글 1만 개)
- AI 정상 시 작성 후 30초 안에 몬스터 95%
- 공감과 댓글 API p95 300ms 이하
- 피드 스크롤 60fps에 가깝게(중급 휴대폰)

**Constraints**:
- 분석은 글 저장과 분리한다(constitution V)
- 공격은 정확히 한 번만 반영한다(SC-004)
- 모듈 순환 의존이 없어야 한다(constitution I)
- 새 유료 서비스가 없다(constitution VI, 무료 LLM 엔드포인트 기본)

**Scale/Scope**: 글 1만 개, 몬스터당 동시 공격 100건, 화면 3개(`/write`, `/home` 피드, `/post/[id]` 상세)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 확인 | 결과 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 5개의 의존 방향은 overview 5.1 그래프 그대로다(`post`→`member`, `emotion`→`post`·`ai`, `monster`→`post`·`emotion`, `feed`→`post`·`monster`·`emotion`·`member`). `post`는 `monster`를 모르며, 공격 응답의 HP는 웹이 상세를 다시 불러 맞춘다(R6). 웹은 `entities/post`, `entities/monster`, `features/write-post`, `features/like`, `features/comment`, `widgets/feed-list`, `widgets/post-detail`을 FSD 규칙대로 두고 steiger가 검사한다 | 통과 |
| II. 계약이 코드보다 먼저다 | `contracts/core-loop.openapi.yaml`을 먼저 확정했고 Redocly 검증을 통과했다. 구현 첫 작업이 루트 누적 계약으로 합치고 타입을 생성하는 것이다(R13) | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 30개(US1 7, US2 5, US3 10, US4 4, US5 4)에 테스트 ID를 붙인다. 3D 외형(US5-AC1~AC3)은 외형 함수 단위 테스트와 정지 이미지 스냅숏으로 자동화한다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위기 감지는 M4다. M2는 레이드처럼 참여를 늘리는 기능이 아니라 서비스의 기본 루프이므로 순서 위반이 아니다. M4 전까지 글이 검사 없이 공개된다는 점은 스펙 Assumptions에 적었다 | 통과(주의) |
| V. AI 장애가 핵심 흐름을 막지 않는다 | 글 저장과 분석을 분리하고, 재시도와 24시간 기본 몬스터로 끝까지 보장한다(R2). 장애 주입 테스트를 둔다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | LLM 기본값은 무료 엔드포인트이고, 작성 요청 제한(R9)으로 남용을 막는다. Redis 없이 Postgres로 처리한다 | 통과 |

**Phase 1 설계 후 재확인**:
- 데이터 모델의 테이블은 각각 한 모듈이 소유한다. 다른 모듈은 파사드(`PostApi.attacksSoFar`, `MonsterApi.findByPostIds` 등)만 쓴다.
- 이벤트 구독 방향이 의존 그래프와 같다(`monster`가 `post`와 `emotion` 이벤트를 구독).
- 계약의 모든 응답이 `ApiResponse` 봉투를 따른다.
- 위반 사항이 없어 Complexity Tracking은 비운다.

## Project Structure

### Documentation (this feature)

```text
specs/003-core-loop/
├── spec.md
├── plan.md                      # 이 문서
├── research.md                  # 결정 R1~R13
├── data-model.md                # 테이블 7개, 상태 전이, HP 반영 규칙, 이벤트
├── quickstart.md                # 수동 시나리오 12개, 동시성, 운영 준비
├── contracts/
│   └── core-loop.openapi.yaml   # 이번 추가분(경로 8개, 연산 14개). 구현 때 루트 contracts/openapi.yaml에 합친다
├── checklists/requirements.md
└── tasks.md                     # /speckit-tasks
```

### Source Code (repository root)

```text
contracts/openapi.yaml                       # 누적 계약(002 이동 + 003 추가)

apps/api/src/main/kotlin/com/ogu/
├── member/                                  # MemberApi.getMembers 일괄 조회 추가
├── post/
│   ├── PostApi.kt, PostCreated.kt, PostLiked.kt, CommentCreated.kt, CommentLiked.kt, CommentTone.kt
│   ├── domain/        Post, Comment, PostLike, CommentLike, 리포지토리, Grapheme(글자 수)
│   ├── application/   PostService, CommentService, LikeService, PostRateLimit, AttackQuery
│   └── presentation/  PostController, CommentController, LikeController, DTO
├── ai/
│   ├── EmotionAnalyzer.kt, EmotionClassification.kt
│   └── infrastructure/ SpringAiEmotionAnalyzer(+프롬프트), FakeEmotionAnalyzer(e2e, test)
├── emotion/
│   ├── EmotionApi.kt, EmotionAnalyzed.kt, EmotionType.kt, Intensity.kt
│   ├── domain/        EmotionAnalysis(상태 전이), 리포지토리
│   └── application/   PostCreatedListener, AnalysisRunner, RetryScheduler, Backoff
├── monster/
│   ├── MonsterApi.kt, MonsterDefeated.kt
│   ├── domain/        Monster, MonsterHpLog, 리포지토리(원자적 UPDATE, ON CONFLICT)
│   └── application/   AttackListener(동기), MonsterFactory(EmotionAnalyzed, 소급 반영), PostLock
└── feed/
    └── presentation/  FeedController(/api/v1/feed, GET /api/v1/posts/{id}), FeedAssembler
apps/api/src/main/resources/
├── db/migration/V3__core_loop.sql
└── prompts/emotion-analysis-v1.st

apps/web/src/
├── app/
│   ├── write/page.tsx
│   ├── home/page.tsx                        # 피드로 교체
│   └── post/[id]/page.tsx
├── entities/
│   ├── post/       types, queries(useFeedQuery, usePostDetailQuery + 분석 중 폴링), ui/post-card
│   ├── monster/    model/appearance.ts, model/hp-stage.ts, ui/monster-3d.tsx(dynamic), ui/monster-sprite.tsx, ui/hp-bar.tsx
│   └── comment/    types, queries, ui/comment-item
├── features/
│   ├── write-post/  schema(글자 수), form, mutation
│   ├── like/        post-like, comment-like(낙관적 HP)
│   ├── comment/     write, edit, delete, reply
│   └── manage-post/ edit, delete
├── widgets/
│   ├── feed-list/   정렬, 필터, 무한 스크롤
│   └── post-detail/ 글, 몬스터, 공감, 댓글 조립
└── shared/lib/grapheme.ts                   # Intl.Segmenter 글자 수
apps/web/scripts/render-monsters.ts          # 정지 이미지 20장 생성
apps/web/public/monsters/{emotion}-{stage}.png
apps/web/e2e-full/core-loop.spec.ts, feed.spec.ts
```

**Structure Decision**: M1의 모노레포 구조를 그대로 쓴다.
- API는 Modulith 모듈 5개를 추가한다.
- 웹은 FSD의 `entities`, `features`, `widgets`에 슬라이스를 추가하고, 새 BFF 라우트 없이 기존 범용 프록시를 쓴다. 새 경로는 모두 `auth/` 밖이라 프록시가 막지 않는다.
- 계약은 루트 누적 파일로 옮긴다(R13).

## Complexity Tracking

위반 사항 없음.
