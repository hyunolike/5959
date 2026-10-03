---

description: "Task list for 003-core-loop (핵심 루프)"
---

# Tasks: 핵심 루프 (고민, 감정 몬스터, 공감과 댓글)

**Input**: Design documents from `/specs/003-core-loop/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/core-loop.openapi.yaml, quickstart.md

**Tests**: 포함한다. constitution III에 따라 스토리마다 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다(예: `` `US3-AC9 ...` ``). ID가 없는 보조 테스트는 한국어 설명만 쓴다.

**Organization**: 사용자 스토리별로 묶었다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 미완료 작업에 의존하지 않아 병렬로 할 수 있다
- **[Story]**: 해당 사용자 스토리(US1~US5)

## Path Conventions

- API: `apps/api/src/main/kotlin/com/ogu/`, 테스트 `apps/api/src/test/kotlin/com/ogu/`, 리소스 `apps/api/src/main/resources/`
- 웹: `apps/web/src/`, 전체 흐름 E2E `apps/web/e2e-full/`
- 계약: 저장소 루트 `contracts/openapi.yaml`

---

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 계약을 저장소 루트로 옮긴다(research R13). `git mv specs/002-auth/contracts/openapi.yaml contracts/openapi.yaml` 후 `specs/002-auth/contracts/openapi.yaml` 자리에 루트 파일을 가리키는 짧은 README(`specs/002-auth/contracts/README.md`)를 둔다. `specs/003-core-loop/contracts/core-loop.openapi.yaml`의 tags, paths, components(parameters, schemas, responses)를 루트 파일에 합친다. 이름이 같은 스키마(`ErrorResponse`, `ErrorEnvelope`, `JobRole`, `CareerYear`)는 루트 정의 하나만 남긴다. `info.title`을 `오구오구 API`, `info.version`을 `0.3.0`으로 바꾼다. `npx @redocly/cli lint contracts/openapi.yaml`이 오류 없이 통과해야 한다
- [x] T002 계약 경로 참조를 루트로 바꾼다: apps/web/package.json의 `gen:api`(`../../contracts/openapi.yaml`), apps/api/src/test/kotlin/com/ogu/ContractTests.kt의 `CONTRACT_PATH`와 KDoc, .github/workflows/ci.yml의 web 경로 필터에 `contracts/**` 추가. `pnpm --filter web gen:api`로 generated.ts를 다시 만들고 커밋한다. ContractTests의 `pendingPaths`에 이번에 추가된 연산 14개를 넣어 지금은 통과하게 한다
- [x] T003 [P] apps/api/build.gradle.kts에 Spring AI BOM `org.springframework.ai:spring-ai-bom:2.0.1`과 `spring-ai-starter-model-openai`, `io.github.resilience4j:resilience4j-spring-boot4:2.4.0`을 추가한다. `./gradlew build`가 통과해야 한다
- [x] T004 [P] apps/web에 `three@0.186`, `@react-three/fiber@9`, `@react-three/drei@10`, `@types/three`(dev)를 추가한다. 빌드와 기존 테스트가 통과해야 한다
- [x] T005 [P] apps/api/src/main/resources/application.yml에 `ogu.ai` 블록을 추가한다: `base-url`(`${AI_BASE_URL:https://integrate.api.nvidia.com/v1}`), `api-key`(`${AI_API_KEY:}`), `model`(`${AI_MODEL:qwen/qwen3-next-80b-a3b-instruct}`), `temperature: 0.1`, `max-tokens: 200`, `timeout: 20s`, `prompt-version: v1`. `ogu.emotion.retry`: `initial: 30s`, `max-interval: 5m`, `deadline: 24h`, `poll-interval: 10s`, `batch-size: 20`. `ogu.post.rate-limit`: `max-per-hour: 10`. infra/.env.example과 infra/compose.prod.yaml에 `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`을 연결한다. ProdAuthSettingsCheck(또는 새 ProdAiSettingsCheck)가 prod에서 `ogu.ai.api-key`가 비면 기동을 실패시키고 테스트한다. CI 스모크 테스트 단계에 가짜 `AI_API_KEY`를 넘긴다

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: 이 단계가 끝나야 스토리 작업을 시작한다

- [x] T006 apps/api/src/main/resources/db/migration/V3__core_loop.sql을 data-model.md 그대로 작성한다: `posts`(`content varchar(2000)`, `comment_tone varchar(20)`, `author_job_role`, `author_career_year`, `like_count`, `comment_count` CHECK ≥ 0, `deleted_at`, 인덱스 4개), `comments`(`parent_id` 자기 참조, `content varchar(1200)`, 인덱스 `(post_id, parent_id, id)`), `post_likes` PK `(post_id, member_id)`, `comment_likes` PK `(comment_id, member_id)`, `emotion_analysis`(PK `post_id`, `status`, `attempts`, `next_attempt_at`, 부분 인덱스 `WHERE status = 'PENDING'`, 체크 제약), `monsters`(`post_id UNIQUE`, `max_hp IN (10,20,30)`, `0 ≤ hp ≤ max_hp`, `(status = 'DEFEATED') = (hp = 0)`), `monster_hp_log`(`UNIQUE (monster_id, member_id, action, target_id)`, `retroactive`)
- [x] T007 apps/api/src/test/kotlin/com/ogu/FlywayMigrationTests.kt에 V3 테스트를 추가한다: 7개 테이블 존재, `monster_hp_log` 유일 키가 같은 공격 두 번째 삽입을 거부, `monsters` 체크 제약이 `hp > max_hp`와 `DEFEATED`인데 `hp > 0`을 거부, `emotion_analysis`의 `ANALYZED`인데 `emotion` NULL 거부
- [x] T008 모듈 뼈대와 공개 타입을 만든다(각 모듈 `package-info.java` 포함): `post`(`PostApi`, `PostCreated`, `PostLiked`, `CommentCreated`, `CommentLiked`, `CommentTone`, `Attack(memberId, action, targetId)`, `AttackAction`), `ai`(`EmotionAnalyzer`, `EmotionClassification(emotion, intensity, reason)`, `EmotionAnalysisFailed`), `emotion`(`EmotionApi`, `EmotionAnalyzed`, `EmotionType`, `Intensity`(`LOW`→10, `MEDIUM`→20, `HIGH`→30), `AnalysisStatus`), `monster`(`MonsterApi`, `MonsterView`, `MonsterDefeated`), `feed`(패키지만). `ModularityTests`가 통과하고, 의존 방향이 plan.md Constitution Check 표와 같아야 한다. `ai`가 `emotion` 타입을 쓰지 않도록 `EmotionClassification`은 `ai` 모듈 안의 자체 enum을 쓰고 `emotion`이 변환한다
- [x] T009 [P] apps/api/src/main/kotlin/com/ogu/shared/error/ErrorCode.kt에 `POST_NOT_FOUND`(404), `COMMENT_NOT_FOUND`(404), `NOT_AUTHOR`(403), `CANNOT_LIKE_OWN_POST`(403), `ALREADY_LIKED`(409), `INVALID_PARENT_COMMENT`(400), `POST_RATE_LIMITED`(429)를 추가한다
- [x] T010 [P] apps/api/src/main/kotlin/com/ogu/shared/text/Grapheme.kt와 테스트(TDD): `Grapheme.count(text)`가 `BreakIterator.getCharacterInstance()`로 글자를 센다. 한글, 영문, 이모지 1개(👍=1), 결합 이모지(👨‍👩‍👧=1), 국기(🇰🇷=1) 사례
- [x] T011 [P] apps/web/src/shared/lib/grapheme.ts와 테스트: `Intl.Segmenter('ko', { granularity: 'grapheme' })`로 같은 사례가 같은 값을 내야 한다
- [x] T012 [P] `member` 모듈의 `MemberApi`에 `getMembers(ids: Collection<Long>): Map<Long, MemberInfo>`를 추가한다(쿼리 한 번). 테스트
- [x] T013 apps/api/src/main/kotlin/com/ogu/monster/application/PostLock.kt: `pg_advisory_xact_lock(postId)`을 현재 트랜잭션에서 잡는 헬퍼와, 트랜잭션 밖에서 부르면 실패하는 테스트
- [x] T014 [P] 웹 엔티티 타입: apps/web/src/entities/post/(model/types.ts: generated.ts의 `PostDetail`, `FeedItem`, `CommentTone`과 말투 표시 문구 맵, index.ts), apps/web/src/entities/monster/(model/types.ts: `MonsterView`, `EmotionType`, 감정 한국어 이름 맵, model/hp-stage.ts와 테스트: `full`(>66%), `hurt`(>33%), `weak`(>0), `defeated`(0)), apps/web/src/entities/comment/(model/types.ts)

**Checkpoint**: 스키마, 모듈 경계, 공통 타입 준비 완료

---

## Phase 3: User Story 1 - 고민을 쓰면 감정 몬스터가 생긴다 (Priority: P1) 🎯 MVP

**Goal**: 글이 바로 저장되고, 비동기 분석 뒤 몬스터가 생긴다. AI 장애에도 글은 저장되고 결국 몬스터가 생긴다

**Independent Test**: quickstart 시나리오 1, 2, 9, 10

### Tests for User Story 1 ⚠️

- [x] T015 [P] [US1] apps/api/src/test/kotlin/com/ogu/post/presentation/PostCreateApiTests.kt: `US1-AC1 1~500자 본문과 말투로 작성하면 201과 PENDING`, `US1-AC2 빈 본문, 공백만, 501자, 말투 없음은 400`(500자 이모지 경계 포함), `US1-AC7 온보딩 전 회원은 403 ONBOARDING_REQUIRED`, 작성자 직군과 경력이 글에 스냅숏으로 저장됨, 1시간에 11번째 글은 429 `POST_RATE_LIMITED`(FR-018, 지운 글 포함), 커밋 후 `PostCreated`가 발행됨(`Scenario` DSL)
- [x] T016 [P] [US1] apps/api/src/test/kotlin/com/ogu/emotion/domain/EmotionAnalysisTest.kt: 상태 전이(PENDING→ANALYZED, PENDING→PENDING 재시도, PENDING→DEFAULTED), 백오프(30s, 60s, 120s, 240s, 300s, 300s…), 24시간 경계(23:59:59는 재시도, 24:00:00은 기본값)
- [x] T017 [P] [US1] apps/api/src/test/kotlin/com/ogu/emotion/application/EmotionPipelineTests.kt(가짜 분석기, 시계 주입): `US1-AC4 분석이 끝나면 감정과 강도에 맞는 최대 HP의 몬스터가 HP 가득 찬 상태로 생긴다`(강도 3단계 각각), `US1-AC5 분석기가 실패해도 글은 저장되고 회복 후 재시도로 몬스터가 생긴다`(`[실패:2]`), `US1-AC6 24시간 동안 실패하면 무기력 HP 10 기본 몬스터가 생긴다`, 스케줄러가 `SKIP LOCKED`로 같은 행을 두 번 처리하지 않는다(두 실행기 동시), 분석기가 목록에 없는 감정이나 잘못된 JSON을 주면 실패로 본다
- [x] T018 [P] [US1] apps/api/src/test/kotlin/com/ogu/ai/infrastructure/SpringAiEmotionAnalyzerTest.kt(`MockRestServiceServer` 또는 로컬 HTTP 스텁): 요청에 프롬프트, 모델, temperature가 들어감, 정상 JSON 파싱, 20초 타임아웃은 `EmotionAnalysisFailed`, 서킷이 열리면 호출 없이 실패, 응답 원문을 로그에 남기지 않음
- [x] T019 [P] [US1] apps/api/src/test/kotlin/com/ogu/feed/presentation/PostDetailApiTests.kt: 분석 중이면 `analysisStatus=PENDING`, `monster=null`, 분석 뒤 `ANALYZED`와 몬스터, `mine`, 삭제된 글은 404 `POST_NOT_FOUND`
- [x] T020 [P] [US1] apps/web/src/features/write-post/model/schema.test.ts(글자 수 1~500, 공백만 거부, 말투 필수)와 apps/web/src/entities/post/api/use-post-detail-query.test.ts(PENDING이면 3초 폴링, 2분 뒤 15초, ANALYZED면 중단)
- [x] T021 [P] [US1] apps/web/e2e-full/write-post.spec.ts: `US1-AC1`, `US1-AC3`(분석 중 → 새로고침 없이 몬스터), `US1-AC2`(501자 안내), `US1-AC7`(온보딩 전 /write → /onboarding)

### Implementation for User Story 1

- [x] T022 [US1] post 모듈: domain/Post.kt(본문은 저장 전 trim, `Grapheme.count`로 1~500 검증), PostRepository, application/PostService.create(작성 제한 R9, `MemberApi`로 작성자 프로필 스냅숏, `PostCreated` 발행), presentation/PostController `POST /api/v1/posts`. ContractTests `pendingPaths`에서 `createPost`를 뺀다
- [x] T023 [US1] ai 모듈: infrastructure/SpringAiEmotionAnalyzer(`ChatClient`, `ogu.ai.*` 설정, 응답 JSON을 `EmotionClassification`으로 엄격 파싱, Resilience4j 타임아웃과 서킷 브레이커 `emotionAnalyzer`), resources/prompts/emotion-analysis-v1.st(원본 webbb-be의 `prompts/emotion-analysis-v2.st`에서 감정 5종 정의와 강도 기준을 옮기고 욕설 탐지 과제는 뺀다. 출력 JSON 형식을 명시한다), infrastructure/FakeEmotionAnalyzer(`@Profile("e2e")`와 테스트 설정에서만, research R3 규칙)
- [x] T024 [US1] emotion 모듈: domain/EmotionAnalysis(상태 전이와 `Backoff`), 리포지토리(`findDueForUpdateSkipLocked(now, limit)` 네이티브 쿼리), application/PostCreatedListener(`@ApplicationModuleListener`, 행 생성 후 첫 시도), AnalysisRunner(시도 한 번: 성공→ANALYZED, 실패→다음 시각, 기한 초과→DEFAULTED, 결과에 따라 `EmotionAnalyzed` 발행), RetryScheduler(`@Scheduled(fixedDelayString = poll-interval)`), `EmotionApi.findByPostIds`
- [x] T025 [US1] monster 모듈: domain/Monster, MonsterRepository, application/MonsterFactory(`EmotionAnalyzed` 구독, `PostLock` 안에서 몬스터 생성. 소급 반영은 US3의 T040에서 추가), `MonsterApi.findByPostIds`
- [x] T026 [US1] feed 모듈: presentation/FeedController `GET /api/v1/posts/{postId}`(글, 작성자, 분석 상태, 몬스터, 공감 수, 내가 공감했는지, `mine`, `myCommentCounted`는 US3 전까지 false). ContractTests `pendingPaths`에서 `getPostDetail`을 뺀다
- [x] T027 [P] [US1] 웹 글쓰기: apps/web/src/features/write-post/(model/schema.ts, api/use-create-post-mutation.ts, ui/write-form.tsx: 글자 수 카운터는 `grapheme.ts`, 말투 버튼 4개, 429는 "잠시 뒤 다시 써 주세요" 안내), apps/web/src/app/write/page.tsx, proxy.ts 보호 경로에 `/write`가 이미 있는지 확인. 작성 성공 시 `/post/{id}`로 이동
- [x] T028 [P] [US1] 웹 상세(US1 범위): apps/web/src/entities/post/api/use-post-detail-query.ts(폴링 R10), apps/web/src/widgets/post-detail/(글, 작성자, 말투, 분석 중 표시, 몬스터 자리: US5 전까지 감정 이름과 HP 바만), apps/web/src/app/post/[id]/page.tsx, 404면 "삭제된 글" 안내

**Checkpoint**: 글 작성과 몬스터 탄생이 단독으로 동작한다 (MVP)

---

## Phase 4: User Story 2 - 피드에서 다른 사람의 고민을 본다 (Priority: P1)

**Goal**: 최신순, 인기순, 직군과 경력 필터, 무한 스크롤 피드

**Independent Test**: quickstart 시나리오 11

### Tests for User Story 2 ⚠️

- [x] T029 [P] [US2] apps/api/src/test/kotlin/com/ogu/feed/presentation/FeedApiTests.kt: `US2-AC1 최신 20개와 항목 필드`, `US2-AC2 커서로 다음 20개, 중복과 누락 없음`(페이지 사이에 새 글 삽입), `US2-AC3 인기순은 공감 수 내림차순, 같으면 최신`(같은 공감 수 커서 경계 포함), `US2-AC4 직군 여러 개 OR, 경력 여러 개 OR, 둘은 AND`, `US2-AC5 삭제된 글 제외`, 본문 미리보기 50자와 "...", 분석 중 글은 `monster=null`, 쿼리 수가 4개로 고정(Hibernate 통계 또는 datasource-proxy)
- [x] T030 [P] [US2] apps/web/src/widgets/feed-list/model/filter-params.test.ts(필터와 정렬을 URL 검색어로 직렬화, 되돌리기)
- [x] T031 [P] [US2] apps/web/e2e-full/feed.spec.ts: `US2-AC1`, `US2-AC2`(스크롤로 다음 페이지), `US2-AC3`, `US2-AC4`

### Implementation for User Story 2

- [x] T032 [US2] post 모듈 `PostApi.page(order, jobRoles, careerYears, cursor, size)`: 키셋 쿼리(R7, 불투명 커서 base64url), 삭제 제외
- [x] T033 [US2] feed 모듈 `GET /api/v1/feed`: `PostApi.page` + `MonsterApi.findByPostIds` + `EmotionApi.findByPostIds` + `MemberApi.getMembers` + 내 공감 여부 일괄 조회로 `FeedPage` 조립. size 1~50, 잘못된 커서는 400. ContractTests `pendingPaths`에서 `getFeed`를 뺀다
- [x] T034 [P] [US2] apps/web/src/entities/post/api/use-feed-query.ts(`useInfiniteQuery`), apps/web/src/entities/post/ui/post-card.tsx(작성자, 미리보기, 감정, 몬스터 자리: US5 전까지 감정 이름과 HP 바, 공감 수, 댓글 수), apps/web/src/widgets/feed-list/(정렬 토글, 직군과 경력 다중 선택 필터, `IntersectionObserver` 무한 스크롤, 빈 목록 안내), apps/web/src/app/home/page.tsx를 피드로 교체하고 글쓰기 버튼을 둔다

**Checkpoint**: 피드가 단독으로 동작한다

---

## Phase 5: User Story 3 - 공감과 댓글로 몬스터를 물리친다 (Priority: P1)

**Goal**: 공격 규칙(작성자 제외, 공감 1회, 첫 댓글만 −3, 댓글 공감 1회), 처치, 소급 반영, 동시성

**Independent Test**: quickstart 시나리오 3~10, 동시성 확인

### Tests for User Story 3 ⚠️

- [x] T035 [P] [US3] apps/api/src/test/kotlin/com/ogu/post/presentation/LikeApiTests.kt: `US3-AC1 공감하면 공감 수 +1, HP −1, 다시 공감은 409 ALREADY_LIKED`, `US3-AC4 댓글 공감은 HP −1, 다시는 409`, `US3-AC6 공감 취소 후 다시 공감해도 HP는 그대로`, `US3-AC8 작성자는 자기 글 공감 403 CANNOT_LIKE_OWN_POST, 자기 글 댓글 공감은 HP 변화 없음`, 삭제된 글과 댓글은 404
- [x] T036 [P] [US3] apps/api/src/test/kotlin/com/ogu/post/presentation/CommentApiTests.kt: `US3-AC2 첫 댓글은 HP −3, 두 번째 댓글은 HP 그대로`, `US3-AC3 답글은 원 댓글 아래, 답글의 답글은 400 INVALID_PARENT_COMMENT`, 첫 댓글을 지우고 다시 달아도 HP 그대로, 300자 경계, `US3-AC8 작성자 댓글은 HP 변화 없음`, 댓글 목록은 원 댓글 오래된 순 50개 커서와 답글, 지운 댓글과 지운 원 댓글의 답글 제외
- [x] T037 [P] [US3] apps/api/src/test/kotlin/com/ogu/monster/MonsterRulesTests.kt: `US3-AC5 HP는 0에서 멈추고 DEFEATED, 이후 공격은 hp_before=hp_after=0으로 기록`, `MonsterDefeated`가 한 번만 발행, 몬스터가 없으면 공격은 HP 기록 없이 통과
- [x] T038 [P] [US3] apps/api/src/test/kotlin/com/ogu/monster/MonsterConcurrencyTest.kt: `US3-AC7 서로 다른 회원 100명이 동시에 공감하면 HP가 정확히 줄고 기록이 정확한 개수다`(최대 HP 30, 100명이면 0에서 멈추고 기록 100개), 같은 회원이 댓글 두 개를 동시에 달면 HP는 3만 준다, 몬스터 생성과 공감이 동시에 와도 공감은 정확히 한 번 반영된다(생성 트랜잭션을 잠금 대기에 붙잡는 결정적 테스트. M1의 `pg_stat_clear_snapshot` 교훈을 따른다)
- [x] T039 [P] [US3] apps/api/src/test/kotlin/com/ogu/monster/RetroactiveAttackTests.kt: `US3-AC9 분석 중에 다른 회원 둘이 공감하고 하나가 댓글을 달면 몬스터 HP는 최대 HP − 5이고 기록 3개는 retroactive=true`, 그 사이 취소된 공감과 지운 댓글은 반영하지 않음, 작성자 행동 제외, 소급 반영으로 0이 되면 DEFEATED로 생성
- [x] T040 [P] [US3] 웹 테스트: apps/web/src/features/like/model/optimistic-hp.test.ts(작성자, 몬스터 없음, 이미 반영된 댓글, 처치됨이면 줄이지 않음, 0 아래로 내려가지 않음), apps/web/e2e-full/attack.spec.ts(`US3-AC1`, `US3-AC2`, `US3-AC5` 처치, `US3-AC8` 작성자, `US3-AC10` 공격 직후 HP 표시 변경)

### Implementation for User Story 3

- [x] T041 [US3] post 모듈: domain/Comment, PostLike, CommentLike과 리포지토리, application/LikeService(글 공감, 취소, 댓글 공감, 취소: 카운터는 원자적 `UPDATE ... SET like_count = like_count ± 1`, PK 충돌은 409, 작성자 자기 글 공감 403, 이벤트 발행), CommentService(작성: 부모 검증, 300자, `comment_count` 증가, `CommentCreated` 발행 / 목록: 원 댓글 커서와 답글 일괄 조회, 작성자 정보는 `MemberApi.getMembers`), presentation/LikeController와 CommentController(`POST/GET /api/v1/posts/{id}/comments`, `POST /api/v1/posts/{id}/likes`, `DELETE .../likes/me`, `POST /api/v1/comments/{id}/likes`, `DELETE .../likes/me`). ContractTests `pendingPaths`에서 해당 연산 6개를 뺀다
- [x] T042 [US3] post 모듈 `PostApi.attacksSoFar(postId)`: 작성자를 뺀 살아 있는 글 공감, 회원별 가장 오래된 살아 있는 댓글 하나(답글 포함), 작성자를 뺀 살아 있는 댓글 공감(지운 댓글 제외)
- [x] T043 [US3] monster 모듈: domain/MonsterHpLog와 리포지토리(`insertIfAbsent(...) ON CONFLICT DO NOTHING RETURNING id`, 원자적 `decrementHp(id, delta) RETURNING hp_before/hp_after/status`), application/AttackListener(`PostLiked`, `CommentCreated`, `CommentLiked`를 `@EventListener`로 같은 트랜잭션에서 받아 data-model.md 반영 규칙 1~4 적용, 처치 시 `MonsterDefeated` 발행), MonsterFactory에 소급 반영 추가(`PostApi.attacksSoFar`, `retroactive=true`)
- [x] T044 [US3] feed 모듈 상세의 `myCommentCounted`를 채운다(`MonsterApi`에 "이 회원의 COMMENT 기록이 있는가" 조회 추가)
- [x] T045 [P] [US3] 웹: apps/web/src/features/like/(post-like-button: 작성자에게는 숨김, comment-like-button, model/optimistic-hp.ts, mutation 성공 후 상세 쿼리 무효화), apps/web/src/entities/comment/(api/use-comments-query.ts, ui/comment-item.tsx: 답글 들여쓰기, 공감 수), apps/web/src/features/comment/(write: 300자 카운터, 답글 대상 표시 / reply), apps/web/src/widgets/post-detail에 공감과 댓글 영역을 붙이고 공격 성공 시 몬스터 맞는 반응 트리거(US5 전까지 HP 바 흔들림)

**Checkpoint**: 핵심 루프 전체가 동작한다

---

## Phase 6: User Story 4 - 자기 글과 댓글을 고치거나 지운다 (Priority: P2)

**Independent Test**: quickstart에 없음. 아래 테스트로 확인

### Tests for User Story 4 ⚠️

- [ ] T046 [P] [US4] apps/api/src/test/kotlin/com/ogu/post/presentation/PostManageApiTests.kt: `US4-AC1 본문과 말투를 고쳐도 몬스터 감정, HP, 상태는 그대로`(처치된 몬스터 포함), `US4-AC2 삭제하면 피드와 상세에서 사라지고 상세는 404`, `US4-AC3 원 댓글을 지우면 답글도 지워지고 댓글 수가 그만큼 준다`, `US4-AC4 남의 글과 댓글 수정, 삭제는 403 NOT_AUTHOR`, 수정 검증은 작성과 같은 규칙
- [ ] T047 [P] [US4] apps/web/e2e-full/manage.spec.ts: `US4-AC1`, `US4-AC2`, `US4-AC3`

### Implementation for User Story 4

- [ ] T048 [US4] post 모듈: `PATCH/DELETE /api/v1/posts/{id}`, `PATCH/DELETE /api/v1/comments/{id}`(작성자 확인, 원 댓글 삭제 시 답글 일괄 삭제와 카운터 감소). ContractTests `pendingPaths`가 비어야 한다
- [ ] T049 [P] [US4] 웹: apps/web/src/features/manage-post/(글 수정 폼: 작성 폼 재사용, 삭제 확인 대화상자 후 `/home`), 댓글 수정과 삭제 메뉴(내 댓글에만)

---

## Phase 7: User Story 5 - 몬스터가 살아 있는 것처럼 보인다 (Priority: P2)

**Independent Test**: quickstart 시나리오 12, 정지 이미지 20장 확인

### Tests for User Story 5 ⚠️

- [ ] T050 [P] [US5] apps/web/src/entities/monster/model/appearance.test.ts: `US5-AC1 감정 5종은 색, 형태, 움직임 파라미터가 서로 다르다`, `US5-AC2 HP 비율이 낮아질수록 크기와 채도가 줄고 금 간 정도가 늘며 0이면 쓰러짐`(단계 경계 66%, 33%, 0 포함), 같은 입력은 같은 출력(순수 함수)
- [ ] T051 [P] [US5] apps/web/src/entities/monster/ui/monster-view.test.tsx: `US5-AC4 WebGL 미지원이나 움직임 줄이기면 정지 이미지를 렌더링한다`, `US5-AC3 피드 카드는 항상 정지 이미지이고 감정과 단계에 맞는 파일을 쓴다`

### Implementation for User Story 5

- [ ] T052 [US5] apps/web/src/entities/monster/model/appearance.ts(research R11의 감정별 형태와 단계별 변화)
- [ ] T053 [US5] apps/web/src/entities/monster/ui/monster-3d.tsx(R3F 장면: 기본 도형과 셰이더로 감정 5종, 대기 애니메이션, 맞는 반응 0.4초, 쓰러짐), ui/monster-sprite.tsx(`/monsters/{emotion}-{stage}.png`), ui/monster-view.tsx(WebGL 감지와 `prefers-reduced-motion`으로 3D 또는 정지 이미지 선택, 3D는 `next/dynamic` `ssr: false`), index.ts. 상세와 피드 카드의 임시 몬스터 자리를 교체한다. T014에서 `entities/post`, `entities/monster`, `entities/comment`가 아직 아무 데서도 안 쓰여 steiger의 `fsd/insignificant-slice`에 걸려 `apps/web/steiger.config.ts`에 임시 override를 추가했다. 이 작업으로 세 슬라이스가 모두 실제로 쓰이게 되면(entities/post, entities/comment는 T020/T027/T028/T034/T045에서 먼저 쓰이기 시작한다) 그 override 블록을 지운다
- [ ] T054 [US5] apps/web/scripts/render-monsters.ts(Playwright로 3D 장면을 투명 배경 512×512로 캡처, 감정 5 × 단계 4 = 20장)와 `pnpm --filter web render:monsters` 스크립트. 생성한 `apps/web/public/monsters/*.png`를 커밋한다. 첫 로딩 번들에 three가 들어가지 않았는지 `next build` 출력으로 확인한다

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T055 ContractTests `pendingPaths`가 비어 있고 루트 계약의 모든 연산(M1 8개 + M2 14개)이 구현과 일치하는지 확인한다
- [ ] T056 [P] `grep -rn "US[1-5]-AC[0-9]*" apps/`로 스펙의 인수 조건 30개가 모두 테스트 이름에 있는지 확인하고 빠진 것을 추가한다
- [ ] T057 [P] 문서: apps/api/AGENTS.md(새 모듈 5개, 공개 파사드와 이벤트, 공격 반영 규칙, 분석 재시도), apps/web/docs/ARCHITECTURE.md(새 슬라이스, 폴링, 3D와 정지 이미지), docs/architecture/overview.md 5.1, 5.2, 5.3, 6.2를 구현과 맞춘다(동기 공격 반영, 소급 반영, 비동기 분석 재시도 테이블). README에 핵심 루프 소개 한 단락
- [ ] T058 성능 측정: 글 1만 개를 넣고 피드 첫 페이지와 다음 페이지 각 100회, 공감 API 100회의 p95를 재서 specs/003-core-loop/quickstart.md에 표로 남긴다(SC-003, plan 성능 목표). 추가로 (1) Chrome 성능 추적(CPU 4배 감속, 모바일 뷰포트)으로 피드를 빠르게 스크롤하며 끊긴 프레임 비율을 재고(SC-005), (2) 실제 감정 분석 엔드포인트로 글 20개를 써서 몬스터가 나타나기까지의 시간 분포를 잰다(SC-001). 키가 없으면 그 사실을 적는다. 측정 환경 부하를 함께 적는다
- [ ] T059 specs/003-core-loop/quickstart.md의 수동 시나리오 12개를 로컬에서 끝까지 실행하고, 다르면 문서나 코드를 고친다
- [ ] T060 `/speckit-analyze`로 일관성을 확인하고 PR을 연다(스펙 링크, 인수 조건 체크리스트, 운영 준비 항목)

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: T001 → T002 순서. T003~T005는 병렬
- **Foundational (Phase 2)**: Setup 뒤. 모든 스토리를 막는다. T006 → T007, T008 → T013
- **US1 (Phase 3)**: Foundational 뒤 (MVP)
- **US2 (Phase 4)**: Foundational 뒤. 피드 항목의 몬스터는 US1의 `MonsterApi`가 필요하므로 T033은 T025 뒤
- **US3 (Phase 5)**: US1 뒤(몬스터와 상세가 필요). T043은 T041, T042 뒤
- **US4 (Phase 6)**: US3 뒤(댓글이 필요)
- **US5 (Phase 7)**: US1 뒤. 웹 몬스터 자리를 교체하므로 T028, T034 뒤
- **Polish**: 모든 스토리 뒤

### Parallel Opportunities

- Setup: T003, T004, T005
- Foundational: T009, T010, T011, T012, T014
- US1 테스트 T015~T021, 웹 구현 T027, T028
- US2와 US5는 US1 뒤에 동시에 진행할 수 있다

## Implementation Strategy

### MVP First

1. Setup → Foundational → US1
2. **멈추고 검증**: 글 작성, 분석 중, 몬스터 탄생, AI 장애 복구(quickstart 1, 2, 9, 10)
3. US2(피드) → US3(공격) → 배포: 서비스의 핵심 루프 완성
4. US4(관리), US5(3D 몬스터) → 배포

### Notes

- 커밋 메시지와 PR 제목은 Conventional Commits
- `webbb-be/`, `webbb-fe/`는 수정하지 않는다(프롬프트 원문은 읽어서 옮긴다)
- M1 회고: 동시성 테스트는 `pg_stat_activity`를 읽을 때 `pg_stat_clear_snapshot()`을 먼저 부르고, 시각 비교는 DB 정밀도(마이크로초)에 맞춰 자른다
