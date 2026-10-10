# Implementation Plan: 비슷한 고민 추천

**Branch**: `007-recommend` | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-recommend/spec.md`

## Summary

글 상세 아래에 그 글과 비슷한 고민을 최대 5개 보인다. 글의 임베딩으로 가까운 글을 찾고, 임베딩이 없거나 가까운 글이 없으면 같은 감정의 최근 글로 대신한다. 보는 사람 자신의 글, 숨긴 글, 지운 글은 나오지 않는다.

API에는 `recommend` 모듈을 추가한다. 글이 저장되거나 고쳐지면 커밋 뒤에 임베딩을 만들어 pgvector의 `halfvec(2048)`에 저장하고 HNSW 색인으로 찾는다. 처리 일정은 M2의 감정 분석과 같은 방식이라, 임베딩이 실패해도 글쓰기는 성공하고 다시 시도한다. 임베딩은 NVIDIA의 `nemotron-3-embed-1b`를 쓰고 모델 이름을 값과 함께 저장한다. `recommend`는 글의 ID만 돌려주고 카드 조립은 피드와 같은 길을 타서 숨김과 욕설 가리기가 똑같이 적용된다.

웹은 글 상세에 추천 구역을 더한다. 가까움의 기준은 직접 쓴 문장 묶음으로 실제 공급자를 불러 정하고, 글 1만 건에서의 응답 시간을 잰다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI(OpenAI 호환 임베딩 클라이언트), Resilience4j(`embedder` 인스턴스 추가), pgvector 확장. 새 라이브러리는 없다
- 웹: Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17 + pgvector 0.8(이미지 `pgvector/pgvector:pg17`, M0부터). Flyway `V7__recommend.sql`로 확장과 `post_embedding`(halfvec 2048, HNSW)을 만든다

**Testing**:
- API: JUnit 5, MockMvc, Testcontainers. 가짜 임베더가 문장의 표지로 방향을 정해 가까움을 결정적으로 만든다. 재시도는 시계 주입. 숨김 표 테스트에 추천 경로를 더한다. 실제 공급자를 부르는 평가와 확인은 켰을 때만 돈다
- 웹: Vitest, Playwright(`e2e-full/recommend.spec.ts`)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 글 1만 건에서 추천 조회 p95 300ms 이하(SC-003)
- 글을 쓰고 30초 안에 비슷한 고민이 보인다(SC-004)
- 글 쓰기의 응답 시간이 늘지 않는다(SC-005). 임베딩은 커밋 뒤 비동기다

**Constraints**:
- AI가 실패해도 글쓰기는 성공하고 추천 구역은 같은 감정의 글로 이어진다(constitution V)
- 숨긴 글과 지운 글이 추천으로 새지 않는다(SC-006). 카드 조립을 피드와 같은 길로 한다
- 공급자에 본문만 보낸다. 본문과 값을 로그와 이벤트에 남기지 않는다(FR-013, FR-014)
- `posts`를 `recommend`에서 직접 읽지 않는다. 모듈 경계를 지킨다
- 월 인프라 비용 증가 0원. 임베딩 호출은 글마다 한 번이다

**Scale/Scope**: 인수 조건 23개(US1 8, US2 7, US3 5, US4 3). 새 모듈 1개, 새 테이블 1개, 계약 연산 1개, 파사드 3개 추가, 웹은 기존 위젯에 구역 하나

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `recommend`는 `shared`, `post`, `ai`, `emotion`에 의존하고 `feed → recommend`가 더해진다. overview 5.1의 그래프에 `recommend → emotion`만 새로 생긴다. `posts`는 `PostApi`로만 읽는다(R5). `ModularityTests`가 확인한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/recommend.openapi.yaml](contracts/recommend.openapi.yaml)에 연산 1개를 먼저 적었다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 23개에 ID를 붙였다. SC-001, SC-002는 실제 공급자가 필요해 켰을 때만 도는 테스트로 재고 결과를 문서에 남긴다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 숨긴 글은 추천에 나오지 않는다. 카드 조립이 피드와 같아 M4의 숨김과 가리기 규칙을 따로 구현하지 않는다. 숨김 표 테스트에 추천 경로를 더해 새지 않는 것을 확인한다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | 임베딩은 커밋 뒤 비동기이고 일정 테이블로 재시도한다. 읽을 때는 공급자를 부르지 않는다. 임베딩이 없으면 감정 분석 결과로 대신한다(R6) | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너가 없다. pgvector는 지금 이미지에 들어 있다. 임베딩 공급자의 사용료는 없다 | 통과 |

**Phase 1 설계 후 재확인**:
- `post_embedding`은 `recommend`가 소유한다. 다른 모듈의 테이블에 FK를 걸지 않고 조인하지 않는다.
- `recommend`는 이벤트를 내지 않는다. `post`의 `PostWritten`, `PostRemoved`를 받는다.
- 추천 조회의 경로는 `feed`에 있고, `recommend`는 `RecommendApi`로 ID만 준다.
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/007-recommend/
├── spec.md, plan.md, research.md(R1~R10), data-model.md, quickstart.md
├── contracts/recommend.openapi.yaml
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
apps/api/src/main/
├── java/com/ogu/recommend/package-info.java       # allowedDependencies: shared, post, ai, emotion
├── kotlin/com/ogu/
│   ├── recommend/
│   │   ├── RecommendApi.kt, Recommendation.kt
│   │   ├── application/
│   │   │   ├── RecommendProperties.kt
│   │   │   ├── EmbeddingListener.kt               # PostWritten, PostRemoved
│   │   │   ├── EmbeddingStore.kt                  # 맡기, 결과 적기(짧은 트랜잭션)
│   │   │   ├── EmbeddingRunner.kt, EmbeddingRetryScheduler.kt
│   │   │   ├── SimilarPostsService.kt             # RecommendApi 구현: 가까운 글, 같은 감정으로 대신하기
│   │   │   └── EmbeddingBackfill.kt
│   │   └── domain/PostEmbeddingRepository.kt      # JdbcClient, halfvec
│   ├── ai/
│   │   ├── Embedder.kt, Embedding.kt, EmbeddingFailed.kt
│   │   └── infrastructure/SpringAiEmbedder.kt, FakeEmbedder.kt
│   ├── emotion/EmotionApi.kt                      # recentPostIds
│   ├── post/PostApi.kt                            # visibleSummaries, idsAfter
│   └── feed/                                      # SimilarPostsQuery, FeedController에 경로 하나
└── resources/db/migration/V7__recommend.sql

apps/web/src/
├── entities/post/api/use-similar-posts-query.ts
└── widgets/post-detail/ui/similar-posts.tsx

apps/web/e2e-full/recommend.spec.ts
apps/api/src/test/resources/recommend/eval-set.tsv, eval-holdout.tsv
apps/api/src/test/resources/seed/m6-recommend-perf.sql
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 모듈 하나를 더하고, 웹은 기존 글 상세 위젯에 구역 하나를 더한다.

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, V7 마이그레이션, `recommend` 모듈 뼈대, 설정 | 기반 |
| 2 | `Embedder`와 실제, 가짜 구현, 실제 공급자 확인 테스트 | 기반 |
| 3 | 임베딩 처리 일정(저장 이벤트, 재시도, 고침과 삭제) | US2, US3 |
| 4 | 가까운 글 찾기, 같은 감정으로 대신하기, 추천 조회 API | US1, US2 |
| 5 | 숨김과 삭제가 새지 않는지, 숨김 표 테스트 | US3 |
| 6 | 이미 있는 글 처리 | US4 |
| 7 | 웹: 추천 구역, e2e | US1, US2 |
| 8 | 평가 묶음으로 기준값 정하기, 성능 측정 | 마무리 |
| 9 | 인수 조건 점검, 문서, quickstart 실행, PR | 마무리 |

## Complexity Tracking

위반이 없다.
