---

description: "Task list for 007-recommend (비슷한 고민 추천)"
---

# Tasks: 비슷한 고민 추천

**Input**: Design documents from `/specs/007-recommend/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 23개다(US1 8, US2 7, US3 5, US4 3).

**Organization**: plan의 구현 순서(묶음 1~9)를 따른다.

## Phase 1: Setup (묶음 1)

- [ ] T001 `contracts/recommend.openapi.yaml`을 루트 계약에 합친다(0.7.0, `getSimilarPosts`, `SimilarPosts`, `RecommendationBasis`). `pnpm --filter web gen:api`
- [ ] T002 `V7__recommend.sql`: `CREATE EXTENSION IF NOT EXISTS vector`, `post_embedding`(halfvec 2048), HNSW와 기다림 색인, CHECK. `RecommendMigrationTests`
- [ ] T003 `recommend` 모듈 뼈대(`package-info.java`, `RecommendProperties`, `application.yml`의 `ogu.recommend.*`, `ogu.ai.embedding-model`, resilience4j `embedder`). `ModularityTests`에 더한다

## Phase 2: Foundational (묶음 2)

- [ ] T004 [P] `ai`: `Embedder`, `Embedding`, `EmbeddingFailed`, `SpringAiEmbedder`(OpenAI 호환 `/v1/embeddings`, `input_type=passage`, 서킷 브레이커와 타임아웃, 응답을 로그에 남기지 않는다), `FakeEmbedder`(`[주제:이름]`, `[임베딩실패]`, `[임베딩실패:N]`), 설정 빈(`e2e`면 가짜, 키가 없으면 바로 실패)
- [ ] T005 [P] `SpringAiEmbedderTest`(가짜 HTTP 서버: 요청의 모델과 `input_type`, 차원이 다르면 실패, 타임아웃, 서킷 열림, 로그에 본문이 없다), `EmbedderLiveTest`(켰을 때만: 2048차원, 비슷한 문장이 무관한 문장보다 가깝다)
- [ ] T006 [P] `recommend` domain/PostEmbeddingRepository(기다림 만들기와 다시 기다림, 맡기, 성공과 실패 적기, 지우기, 가까운 후보 찾기, 행이 없는 글 찾기)

## Phase 3: 처리 일정 US2, US3 (묶음 3)

- [ ] T007 [P] `EmbeddingPipelineTests`: `US2-AC1 임베딩이 실패해도 글 쓰기는 201`, `US2-AC4 실패하면 간격을 늘려 다시 시도하고 성공하면 DONE`, `US2-AC5 24시간 뒤에는 GIVEN_UP`, `US3-AC3 고치면 다시 처리한다`, 고친 뒤 늦게 온 이전 결과를 버린다, `US3-AC4 다시 처리하는 동안 이전 값이 남는다`, `US3-AC1 글을 지우면 행이 없어진다`, 이벤트가 다시 와도 행은 하나, 이벤트와 로그에 본문과 값이 없다(SC-008)
- [ ] T008 application/EmbeddingListener(`PostWritten` 커밋 뒤, `PostRemoved`), EmbeddingStore, EmbeddingRunner, EmbeddingRetryScheduler(`ogu.recommend.retry.scheduler-enabled`)

## Phase 4: 추천 조회 US1, US2 (묶음 4)

- [ ] T009 [P] `SimilarPostsApiTests`: `US1-AC1 가까운 순서로 최대 5개`, `US1-AC3 지금 글과 내 글은 없다`, `US1-AC4 기준보다 먼 글은 없다`, `US1-AC5 욕설이 가려진다`, `US1-AC6 피드와 같은 항목이다`, `US1-AC8 온보딩 전은 403`, `US2-AC2 임베딩이 없으면 같은 감정의 최근 글과 basis SAME_EMOTION, pending true`, `US2-AC3 둘 다 없으면 NONE과 빈 목록`, `US2-AC6 대신한 목록에도 내 글, 숨긴 글, 지운 글이 없다`, 가까운 글이 없으면 같은 감정으로 넘어간다, 다른 모델로 만든 값은 견주지 않는다, 없는 글은 404, 쿼리 수가 글 수와 상관없다
- [ ] T010 `EmotionApi.recentPostIds`, `PostApi.visibleSummaries`, application/SimilarPostsService(`RecommendApi`), `feed`의 SimilarPostsQuery와 `GET /api/v1/posts/{postId}/similar`

## Phase 5: 숨김과 삭제 US3 (묶음 5)

- [ ] T011 `HiddenContentMatrixTests`에 추천 경로를 더한다: `US3-AC2 숨긴 글은 다른 회원의 추천에 없고 풀면 다시 나온다`, `US3-AC5 숨겨진 내 글의 추천은 보인다`, `US3-AC1 지운 글은 없다`(SC-006)

## Phase 6: 이미 있는 글 US4 (묶음 6)

- [ ] T012 `PostApi.idsAfter`, application/EmbeddingBackfill(기동 뒤 따로 도는 스레드, 행이 없는 글에 기다림 만들기, 옛 모델의 행 다시 기다림). `EmbeddingBackfillTests`: `US4-AC1`, `US4-AC2 새 글이 먼저 처리된다`, `US4-AC3 다시 떠도 처리한 글을 다시 보내지 않는다`

## Phase 7: 웹 (묶음 7)

- [ ] T013 [P] `entities/post/api/use-similar-posts-query.ts`(`pending`이면 3초마다, 최대 30초), `widgets/post-detail/ui/similar-posts.tsx`(제목이 basis에 따라 다르다, NONE이면 그리지 않는다, 실패해도 그리지 않는다). 단위 테스트: `US1-AC1`, `US1-AC2`, `US1-AC7`, `US2-AC2`, `US2-AC3`, `US2-AC7`
- [ ] T014 `e2e-full/recommend.spec.ts`: `US1-AC1`과 `US1-AC4`, `US1-AC2`, `US2-AC2`, `US3-AC1`

## Phase 8: 평가와 성능 (묶음 8)

- [ ] T015 `recommend/eval-set.tsv`(주제 10개 × 6문장), `eval-holdout.tsv`(30문장), `RecommendEvalTest`(켰을 때만). 기준값을 바꿔 가며 SC-001, SC-002를 출력하고 `max-distance`를 정한다
- [ ] T016 `seed/m6-recommend-perf.sql`과 성능 측정(SC-003, SC-004, SC-005). 결과를 quickstart.md에 남긴다

## Phase 9: Polish (묶음 9)

- [ ] T017 [P] 인수 조건 23개가 모두 테스트 이름에 있는지 확인한다
- [ ] T018 [P] 문서: apps/api/AGENTS.md, apps/web/docs/ARCHITECTURE.md, overview 5.1과 5.5, README
- [ ] T019 quickstart의 수동 시나리오 13개를 로컬에서 실행한다
- [ ] T020 PR을 연다
