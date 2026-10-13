---

description: "Task list for 011-guest-feed (비회원 둘러보기 피드)"
---

# Tasks: 비회원 둘러보기 피드

**Input**: Design documents from `/specs/011-guest-feed/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 15개다(US1 7, US2 6, US3 2). 테스트를 먼저 쓰고 실패를 본 뒤 구현한다.

## Phase 1: Setup

- [X] T001 `specs/011-guest-feed/contracts/guest-feed.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.12.0): `getPublicFeed`(`security: []`), `PublicFeedItem`(`required: [postId, jobRole, careerYear, contentPreview, analysisStatus, monster, likeCount, commentCount, createdAt]`), `PublicFeed`(`maxItems: 20`). `pnpm --filter web gen:api`

## Phase 2: API (US1, US2)

- [X] T002 [US2] `apps/api/src/test/kotlin/com/ogu/safety/RiskApiTests.kt`에 `US2-AC3 판정이 끝나지 않은 글만 unsettledPostIds에 든다`(끝난 글, 기다리는 글, 닫힌 글, 기록이 없는 글)를 먼저 쓰고, `safety`의 `RiskApi.unsettledPostIds`와 구현
- [X] T003 [US1] `apps/api/src/test/kotlin/com/ogu/feed/presentation/PublicFeedApiTests.kt`를 먼저 쓴다: `US1-AC1 로그인 없이 최근 글부터 받는다`, `US1-AC2 감정, 직군과 경력, 본문, 몬스터, 수가 있다`, `US1-AC3 응답 원문에 닉네임과 회원 번호가 없다`, `US1-AC5 스무 개까지만 주고 커서가 없다`, `US1-AC6 보일 글이 없으면 빈 목록이다`, `US2-AC1 지운 글과 숨긴 글은 없다`, `US2-AC2 우려와 위기 글은 없다`, `US2-AC3 판정이 끝나지 않은 글은 없고 끝나면 보인다`, `US2-AC4 욕설을 가린다`, `US2-AC5 로그인 없이는 회원의 피드와 글 상세와 댓글이 401이다`, 쿼리는 4개다
- [X] T004 [US1] `feed`: `application/PublicFeedQuery.kt`, `presentation/dto/PublicFeedResponse.kt`, `presentation/PublicFeedController.kt`. `apps/api/src/main/java/com/ogu/feed/package-info.java`와 `ModularityTests`의 허용 표에 `safety`. `SecurityPaths.PUBLIC`에 `/api/v1/public/feed`

## Phase 3: 웹 (US1, US2, US3)

- [X] T005 [P] [US1] `apps/web/src/entities/post`: `PublicFeedItem` 타입, `usePublicFeedQuery`, `PublicPostCard`(버튼, 닉네임 없음). 단위 테스트: `US1-AC2`, `US1-AC3 닉네임 자리가 없다`, 누르면 `onSelect`가 불린다
- [X] T006 [US1] `apps/web/src/widgets/guest-home`: 목록, `US1-AC4 글을 누르면 로그인 안내가 그 글로 열린다`, `US1-AC5 더 보려면 로그인`, `US1-AC6 빈 목록`, `US1-AC7 불러오지 못해도 소개와 로그인은 남는다`, `US3-AC2 옛 문구가 없다`. `app/home/page.test.tsx` 갱신
- [X] T007 [P] [US2] `apps/web/src/app/home/layout.tsx`의 `robots: noindex`(`US2-AC6`)와 `app/write/page.tsx`의 공개 범위 안내(`US3-AC1`). 단위 테스트
- [X] T008 [US1] `apps/web/e2e/guest-home.spec.ts`(가짜 API)와 `apps/web/e2e-full/guest-security.spec.ts`(실제 API)를 새 동작에 맞춘다: 둘러보기로 목록이 보인다, 글을 누르면 로그인 안내, 글 상세 주소는 로그인으로 간다

## Phase 4: 마무리

- [X] T009 [P] 문서: `apps/api/AGENTS.md`(공개 조회, `feed → safety`), `apps/web/docs/ARCHITECTURE.md`, `docs/architecture/overview.md`의 그래프
- [X] T010 `./gradlew clean build`, 웹의 `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`, `test:e2e`. 390px 화면을 눈으로 본다
- [ ] T011 별도 검토자가 브랜치 전체를 본다. 지적을 고친 뒤 `main`으로 PR을 연다. PR에 API 배포가 따로 필요하다고 적는다

## Dependencies

- T001 → T004, T005. T002 → T004. T003은 T004 앞에 쓰고 실패를 본다
- T005 → T006 → T008. T004 → T008의 e2e-full
