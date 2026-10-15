---

description: "Task list for 016-first-post-guide (첫 글로 이어지는 안내)"
---

# Tasks: 첫 글로 이어지는 안내

**Input**: Design documents from `/specs/016-first-post-guide/`

**Tests**: 포함한다(헌법 III). 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 31개다(US1 7, US2 9, US3 6, US4 3, US5 6). 각 스토리에서 테스트를 먼저 쓰고 실패를 본 뒤 구현한다.

**경로**: 따로 적지 않으면 `apps/web/src/` 아래다. 명령은 저장소 루트에서 `pnpm --filter web <script>`로 돌린다.

**문구 규칙**: 사람에게 보이는 문구는 `~해요`체로 쓰고, 가운뎃점을 늘어놓지 않는다. 공개 범위 문구는 첫 화면(`widgets/landing/ui/landing.tsx`의 "안심하고 말할 수 있는 곳")과 어긋나지 않게 한다(FR-003).

## Phase 1: Setup

- [x] T001 `origin/main`을 다시 받아 이 브랜치를 그 위로 올리고, `specs/` 번호 016이 아직 비어 있는지 본다. 다른 브랜치가 016을 먼저 썼으면 디렉터리와 문서 안의 번호를 바꾼다

## Phase 2: Foundational

US1(실마리 없이도), US2, US3이 함께 쓰는 "내 글이 있는가" 쿼리다.

- [x] T002 `shared/config/constants.ts`의 `QUERY_KEYS`에 `hasMyPost: ["members", "me", "posts", "exists"] as const`를 더한다(마이페이지 무한 목록 `myPosts`와 섞이지 않는 키)
- [x] T003 `entities/post/api/use-has-my-post-query.test.ts`를 먼저 쓴다: 항목이 있으면 `true`, 빈 배열이면 `false`, 실패하거나 받는 중이면 `undefined`, 요청 주소가 `/api/members/me/posts?size=1`인지
- [x] T004 `entities/post/api/use-has-my-post-query.ts`: `fetchMyPostsPage`와 같은 `requestApi`로 `/api/members/me/posts?size=1`(`cache: "no-store"`)을 받아 `items.length > 0`을 돌려주는 `useHasMyPostQuery()`. 반환은 `boolean | undefined`. `refetchOnMount: "always"`. `entities/post/index.ts`에서 내보낸다
- [x] T005 [P] `features/write-post/api/use-create-post-mutation.ts`의 성공 처리와 `features/manage-post/api/use-delete-post-mutation.ts`의 성공 처리에서 `QUERY_KEYS.hasMyPost`도 무효화한다. 각 테스트 파일에 무효화 확인을 더한다

**Checkpoint**: `pnpm --filter web test -- use-has-my-post-query` 통과

## Phase 3: User Story 1 - 안심하고 첫 글을 쓴다 (P1) 🎯 MVP

**Goal**: 쓰기 화면이 공개 범위를 한 묶음으로 보이고, 빈 입력란에 실마리를 주고, 말투마다 댓글 예시를 보인다.

**Independent Test**: 쓰기 화면을 열어 공개 범위 묶음, 실마리 칩, 말투 예시를 확인한다(quickstart 6~8).

### Tests

- [x] T006 [P] [US1] `features/write-post/ui/privacy-scope.test.tsx`를 먼저 쓴다: `US1-AC1 회원, 로그인하지 않은 방문자, 누구에게도 보이지 않는 것을 세 줄로 나눈다`(각 줄에 "닉네임", "직군과 경력", "글 전체" / "앞 50자", "닉네임 없이" / "이름", "이메일", "회사"), `US3-AC1(011) 앞 50자가 로그인하지 않은 방문자에게 닉네임 없이 보인다고 알린다`
- [x] T007 [P] [US1] `features/write-post/ui/write-form.test.tsx`에 먼저 더한다: `US1-AC3 입력란이 비어 있으면 실마리가 3~5개 보인다`, `US1-AC4 실마리를 누르면 그 문장이 입력란에 들어가고 초점이 간다`, `US1-AC5 글자가 있으면 실마리가 없고 다 지우면 다시 보인다`(공백만 있어도 빈 것으로 본다), `US1-AC6 말투를 고르면 그 말투의 예시가 보이고 다른 말투를 고르면 바뀐다`
- [x] T008 [P] [US1] `widgets/post-editor/ui/post-editor.test.tsx`에 먼저 더한다: `FR-007 고치기 화면에는 공개 범위 묶음이 있고 실마리는 없다`
- [x] T009 [US1] `app/write/page.test.tsx`를 고친다: 지금의 `US3-AC1` 테스트(머리말의 50자 문장)를 지우고 `US1-AC2 공개 범위를 말하는 문장은 묶음 안에만 있다`(묶음 밖에서 "50자", "회원에게만"이 없다), `US1-AC7 머리말에 몬스터, 함께 물리치기, AI 친구가 먼저 답한다는 안내가 있다`를 쓴다. `US4-AC7(012)` AI 친구 테스트는 그대로 둔다

### Implementation

- [x] T010 [P] [US1] `entities/post/model/types.ts`에 `COMMENT_TONE_EXAMPLES: Record<CommentTone, string>`을 더하고 `entities/post/index.ts`에서 내보낸다. 값은 research R5의 표(대신 욕해주기 "그건 진짜 너무했다. 듣는 내가 다 화나네요." 등). `types.test.ts`에 네 말투 모두 예시가 있는지 더한다
- [x] T011 [P] [US1] `features/write-post/model/starters.ts`: `WRITE_STARTERS`(research R5의 다섯 문장, 각 문장 끝에 공백 하나). 위기 표현, 감정 단정, 사람이나 회사 이름이 없어야 한다(FR-005)
- [x] T012 [US1] `features/write-post/ui/privacy-scope.tsx`: 제목 "이 글은 이렇게 보여요"와 세 줄(`dl` 또는 목록). 회원에게 "닉네임, 직군과 경력, 글 전체"(온보딩 라벨과 같은 "경력"), 로그인하지 않은 방문자에게 "글의 앞 50자까지, 닉네임 없이", 누구에게도 "이름, 이메일, 회사는 보이지 않아요". 옅은 면(`bg-surface-sunken`) 안에 둔다. `features/write-post/index.ts`에서 내보낸다
- [x] T013 [US1] `features/write-post/ui/post-form.tsx`: (1) `starters?: readonly string[]` 프롭. `content.trim()`이 비어 있고 `starters`가 있으면 입력란 바로 아래(글자 수 줄 다음)에 `Chip` 버튼으로 그리고, 누르면 `setValue("content", starter, { shouldDirty: true })` 뒤 `setFocus("content")`. 칩마다 `data-umami-event="write-starter"`. (2) 입력란 설명을 "형식 없이 편하게 적어 주세요."로 줄인다. (3) 말투를 고르면 `COMMENT_TONE_EXAMPLES[field.value]`를 "이런 댓글이 달려요: …" 한 줄로 칩 아래에 보이고 `aria-live="polite"`로 둔다
- [x] T014 [US1] `features/write-post/ui/write-form.tsx`: `PostForm`에 `starters={WRITE_STARTERS}`를 넘기고 올리기 버튼에 `data-umami-event="write-submit"`이 붙도록 `PostForm`에 `submitEvent?: string` 프롭을 더해 넘긴다
- [x] T015 [US1] `app/write/page.tsx`: 머리말의 50자 문장을 지우고, 몬스터와 AI 친구 문장은 남긴 채 그 아래 `<PrivacyScope />`를 둔다. 주석의 근거를 `016 US1-AC1`로 바꾼다
- [x] T016 [US1] `widgets/post-editor/ui/post-editor.tsx`: 폼 위에 `<PrivacyScope />`를 둔다. `starters`는 넘기지 않는다

**Checkpoint**: US1 테스트 통과. `/write`에서 quickstart 6~8

## Phase 4: User Story 2 - 가입을 마치면 첫 글로 이어진다 (P1)

**Goal**: 온보딩이 까닭을 말하고, 마치면 홈 맨 위에 환영 안내가 한 번 보이고, 글이 없는 회원에게는 첫 고민 카드가 보인다.

**Independent Test**: 새 계정으로 온보딩을 마치고 홈을 열고 새로 고친다(quickstart 1~5).

### Tests

- [x] T017 [P] [US2] `features/onboarding/ui/onboarding-form.test.tsx`에 먼저 더한다: `US2-AC1 직군과 경력을 묻는 까닭과 이름과 회사는 묻지 않는다는 문장이 있다`, `US2-AC2 가려던 곳이 없으면 성공 뒤 sessionStorage의 ogu.welcome.pending이 "1"이다`, `US2-AC4 가려던 곳이 있으면 값을 남기지 않고 그곳으로 간다`, `저장소가 막혀도 성공 뒤 이동한다`
- [x] T018 [P] [US2] `widgets/first-post-prompt/ui/first-post-prompt.test.tsx`를 먼저 쓴다: `US2-AC2 welcome.pending이 있으면 환영 안내와 "털어놓기", "먼저 둘러볼게요"가 보이고 첫 고민 카드는 없다`, `US2-AC3 "털어놓기"는 /write로 가는 링크이고 "먼저 둘러볼게요"는 안내를 닫는다`, `US2-AC5 한 번 그린 뒤 값이 지워져 다시 그리면 환영 안내가 없다`, `US2-AC6 hasMyPost가 false면 첫 고민 카드와 /write로 가는 버튼이 보인다`, `US2-AC7 hasMyPost가 true면 아무것도 없다`, `US2-AC8 닫으면 사라지고 localStorage에 남아 다시 그려도 없다`, `US2-AC9 hasMyPost가 undefined면 아무것도 없다`, `localStorage가 막혀도 닫기는 이번 화면에서 된다`
- [x] T019 [US2] `app/home/page.test.tsx`에 먼저 더한다: `US2-AC6 회원 홈에서 첫 고민 카드가 보스 안내와 피드보다 위에 있다`

### Implementation

- [x] T020 [US2] `features/onboarding/ui/onboarding-form.tsx`: (1) 직군 칸 위나 경력 칸 아래에 한 줄 "비슷한 일을 하는 사람의 고민을 찾고 내 글 옆에 함께 보여요. 이름과 회사는 묻지 않아요."를 두고 두 `Select`의 `aria-describedby`에 그 id를 더한다. (2) 성공했고 `destination === "/home"`이면 `router.push` 전에 `try { sessionStorage.setItem("ogu.welcome.pending", "1") } catch {}`. `ALREADY_ONBOARDED`일 때는 남기지 않는다
- [x] T021 [US2] `widgets/first-post-prompt/model/storage.ts`: `consumeWelcomePending(): boolean`(읽고 바로 지운다), `isPromptDismissed(): boolean`, `dismissPrompt(): void`. 키는 `ogu.welcome.pending`(sessionStorage), `ogu.firstPostPrompt.dismissed`(localStorage). 모든 접근을 `try/catch`로 감싸고 실패하면 `false`
- [x] T022 [US2] `widgets/first-post-prompt/ui/first-post-prompt.tsx`: 마운트할 때 한 번 `consumeWelcomePending()`과 `isPromptDismissed()`를 상태로 읽는다(서버 렌더와 어긋나지 않게 `useEffect`에서). StrictMode의 두 번째 effect가 값을 다시 읽어 상태를 끄지 않도록 `useRef`로 한 번만 읽고, 읽은 값이 참일 때만 `setState(true)`한다. T018에 `StrictMode로 감싸도 환영 안내가 보인다`를 더한다. `useHasMyPostQuery()`를 쓴다. 모양 `welcome`(오구 그림 `/brand/ogu-hello.webp`, "가입을 마쳤어요" 류 제목, 요즘 마음에 걸리는 일을 묻는 한 줄, `Button asChild` "털어놓기" → `/write` `data-umami-event="welcome-write"`, 보조 버튼 "먼저 둘러볼게요" `data-umami-event="welcome-browse"`는 안내만 닫는다)과 `nudge`(한 줄 "첫 고민을 남기면 몬스터가 나타나요", "첫 고민 남기기" → `/write` `data-umami-event="first-post-card-write"`, 닫기 `IconButton` `aria-label="첫 고민 안내 닫기"` `data-umami-event="first-post-card-dismiss"`). 조건은 data-model.md의 "화면 상태" 그대로. `widgets/first-post-prompt/index.ts`에서 내보낸다
- [x] T023 [US2] `app/home/page.tsx`의 `MemberHome`: 머리말 다음, `<BossBanner />`보다 위에 `<FirstPostPrompt />`를 둔다

**Checkpoint**: US2 테스트 통과. `pnpm --filter web lint:fsd` 통과(새 위젯이 `entities`, `features`, `shared`만 부른다)

## Phase 5: User Story 3 - 첫 글을 올리면 앞으로 일어날 일을 안다 (P2)

**Goal**: 첫 글의 글 화면에 HP, AI 친구, 알림 안내가 한 번 보이고, 몬스터 등장이 눈에 보이는 글자로 알려진다.

**Independent Test**: 글이 없는 계정으로 글을 올리고, 새로 고치고, 두 번째 글을 올린다(quickstart 9~12).

### Tests

- [x] T024 [P] [US3] `features/write-post/ui/write-form.test.tsx`에 먼저 더한다: `US3-AC1 내 글이 없던 회원은 올린 뒤 /post/{id}?first=1로 간다`, `US3-AC3 내 글이 있던 회원은 /post/{id}로 간다`, `내 글이 있는지 모르면 /post/{id}로 간다`
- [x] T025 [P] [US3] `widgets/post-detail/ui/post-detail.test.tsx`에 먼저 더한다: `US3-AC1 first이고 내 글이면 HP 1과 HP 3, AI 친구, 알림을 말하는 첫 글 안내가 보인다`, `US3-AC2 몬스터 없이 연 내 글에 몬스터가 오면 "몬스터가 나타났어요"가 눈에 보이는 글자로 있다`, `US3-AC4 닫으면 사라지고, 주소의 first를 replace로 지운다`, `US3-AC5 safety가 도움 안내를 띄우는 값(우려, 위기, 숨김)이면 첫 글 안내가 없다`, `FR-016 남의 글은 몬스터 등장 문장이 화면 낭독기에만 있다`, `남의 글에 first가 붙어도 안내가 없다`, `US3-AC6 설치를 권할 수 있으면 설치 길이 있고 이미 설치했으면 없다`

### Implementation

- [x] T026 [US3] `features/write-post/ui/write-form.tsx`: `useHasMyPostQuery()`를 부르고, 성공했을 때 값이 `false`면 `router.push(\`/post/${id}?first=1\`)`, 그 밖에는 지금처럼 `/post/${id}`
- [x] T027 [US3] `features/install-app/index.ts`에서 `useInstallApp`을 내보낸다
- [x] T028 [US3] `widgets/post-detail/ui/first-post-guide.tsx`: 제목 "첫 고민을 남겼어요" 류, 세 항목(공감 한 번에 HP 1, 첫 댓글에 HP 3이 줄고 다 떨어지면 몬스터가 쓰러져요 / 한동안 사람 댓글이 없으면 AI 친구가 먼저 답해요(`AI_FRIENDS_PATH` 링크) / 공감과 댓글이 오면 알림으로 알려 드려요), 닫기 버튼. `useInstallApp()`의 `environment`가 있고 `installed`가 아니면 "앱으로 설치하면 알림을 놓치지 않아요"와 홈 화면 추가 방법으로 가는 길(설치 권한을 스스로 묻지 않는다). 몬스터가 막 나타났으면 맨 위에 "{감정} 몬스터가 나타났어요"를 보인다
- [x] T029 [US3] `widgets/post-detail/ui/post-detail.tsx`: `useSearchParams`로 `first`를 처음 그릴 때 `useState`에 옮기고 `useEffect`에서 `router.replace(pathname, { scroll: false })`로 지운다. `first && detail.mine && !needsSafetyNotice(detail.safety)`이고 닫지 않았으면 `MonsterStage` 바로 아래에 `<FirstPostGuide />`. `openedWithoutMonster && monster && detail.mine`일 때 지금의 `sr-only` 상태 문장을 눈에 보이는 글자로 바꾼다(남의 글은 지금처럼 `sr-only`). `app/post/[id]/page.tsx`가 `Suspense`로 감싸고 있는지 확인하고 없으면 감싼다

**Checkpoint**: US3 테스트 통과

## Phase 6: User Story 4 - 남의 글을 읽다 나도 털어놓는다 (P2)

**Goal**: 남의 글과 AI 친구 글 아래에 쓰기로 가는 길이 있다.

**Independent Test**: 남의 글, 내 글, AI 친구 글을 연다(quickstart 13).

- [x] T030 [P] [US4] `widgets/post-detail/ui/post-detail.test.tsx`에 먼저 더한다: `US4-AC1 남의 글에는 댓글 다음, 비슷한 고민 위에 /write로 가는 "나도 털어놓기"가 있다`, `US4-AC2 내 글에는 없다`, `US4-AC3 AI 친구가 쓴 글에도 있다`
- [x] T031 [US4] `widgets/post-detail/ui/write-your-own.tsx`: 한 줄 "비슷한 마음이 있다면 털어놓아 보세요"와 `/write`로 가는 링크 버튼 "나도 털어놓기"(`data-umami-event="detail-write-own"`). `post-detail.tsx`에서 `</article>`과 `<SimilarPosts />` 사이에 `!detail.mine`일 때 둔다

**Checkpoint**: US4 테스트 통과

## Phase 7: User Story 5 - 사용법을 눈으로 본다 (P3)

**Goal**: 첫 화면과 둘러보기의 사용법 옆에 소리 없는 반복 장면이 있고, 둘러보기의 사용법이 최근 이야기보다 위에 있다.

**Independent Test**: 로그아웃한 채 `/`와 `/home`을 열고 움직임 줄이기를 켠다(quickstart 14~16).

### Tests

- [x] T032 [P] [US5] `entities/monster/ui/monster-lifecycle-loop.test.tsx`를 먼저 쓴다: `US5-AC1 쓰기, 등장, HP 감소와 쓰러짐의 세 칸이 있고 HP 막대가 있다`, `US5-AC3 role="img"이고 장면을 설명하는 이름이 있으며 안의 그림은 alt가 비어 있다`, `US5-AC2 멈춘 그림용 마크업(motion-reduce 클래스)이 있다`, `그림은 lazy로 받는다`
- [x] T033 [P] [US5] `widgets/guest-home/ui/guest-home.test.tsx`에 먼저 더한다: `US5-AC4 사용법이 최근 이야기보다 앞에 있다`(문서 순서), `US5-AC5 마지막 단계에 도감 카드가 있다`. `widgets/landing/ui/landing.test.tsx`에 `US5-AC1 사용법 자리에 시각 자료가 있다`
- [x] T034 [P] [US5] `apps/web/e2e/landing.spec.ts`와 `apps/web/e2e/guest-home.spec.ts`에 더한다: `US5-AC2 reduced-motion이면 시각 자료의 애니메이션이 멈춘다`(`page.emulateMedia({ reducedMotion: "reduce" })` 뒤 계산된 `animation-name`이 `none`), `US5-AC6 몬스터 그림 요청을 막아도 사용법 글이 보인다`(`page.route("**/monsters/**", r => r.abort())`, 경로는 `spritePath`로 확인)

### Implementation

- [x] T035 [US5] `entities/monster/ui/monster-lifecycle-loop.tsx`: 서버 컴포넌트(`"use client"` 없음). 세 칸: ① 글 한 줄이 써지는 말풍선 ② 불안 몬스터 `full` 등장 ③ 공감, 댓글 아이콘과 함께 `HpBar` 모양이 100 → 0으로 줄고 `MonsterSprite`의 `hurt`, `weak`, `defeated`가 겹쳐 차례로 보인다. 한 바퀴 8초 `infinite`, `opacity`와 `transform`만 움직인다. 키프레임은 전역 스타일 파일의 Tailwind `@theme`에 `--animate-*`와 `@keyframes`로 둔다(파일 위치는 `app/layout.tsx`가 불러오는 CSS를 확인). `motion-reduce:animate-none`과 멈춘 세 칸 배치. 그림은 `loading="lazy"`, `alt=""`. 바깥에 `role="img"`, `aria-label="고민을 쓰면 몬스터가 나타나고, 공감과 댓글이 모이면 HP가 줄어 쓰러져요"`. `entities/monster/index.ts`에서 내보낸다
- [x] T036 [US5] `widgets/landing/ui/landing.tsx`: "오구오구는 이렇게 써요" 제목 아래, 단계 목록 옆(넓은 화면)이나 위(좁은 화면)에 `<MonsterLifecycleLoop />`. 첫 화면 위쪽(히어로)에는 두지 않는다(SC-006)
- [x] T037 [US5] `widgets/guest-home/ui/guest-home.tsx`: "오구오구는 이렇게 써요" 섹션을 머리말 바로 아래로 옮기고 `<MonsterLifecycleLoop />`를 함께 둔다. `STEPS`의 세 번째 본문을 "공감과 댓글이 모일수록 몬스터가 약해지고, 물리친 몬스터는 도감 카드로 남아요."로 바꾼다. 다섯 몬스터 섹션은 사용법 아래로 둔다

**Checkpoint**: US5 테스트 통과. 375px에서 가로 스크롤 없음

## Phase 8: Polish & Cross-Cutting

- [x] T038 `apps/web/e2e-full/first-post-guide.spec.ts`(새로): 이메일 가입 → 온보딩(까닭 문장 확인) → `/home`에 환영 안내 → "털어놓기" → `/write`에서 실마리를 눌러 이어 쓰고 말투를 고른다 → 올리기 → 주소에 `first`가 없고 첫 글 안내가 있다 → 새로 고치면 없다 → 홈으로 가면 첫 고민 카드가 없다. 테스트 이름에 `US2-AC2`, `US1-AC4`, `US3-AC1`, `US3-AC4`, `US2-AC7`
- [x] T039 [P] `apps/web/e2e-full/signup-onboarding.spec.ts`와 `write-post.spec.ts`를 돌려 바뀐 문구나 새 안내 때문에 깨진 곳만 고친다. 다른 회원 흐름 e2e는 온보딩 뒤 `/home`을 기대하므로 고칠 것이 없어야 한다. 깨지면 research R2를 다시 본다
- [x] T040 [P] `docs/features.md`와 `README.md`의 기능 표에 "첫 글 안내" 한 줄과 스펙 링크(`specs/016-first-post-guide/spec.md`)를 더한다
- [ ] T041 quickstart.md의 "출시 전에 기준값 적기" 표를 채울 수 있게 출시 전 4주 값을 DB와 Umami에서 뽑아 적는다(운영 접근이 필요하면 사람에게 맡기고 PR 본문에 남긴다)
- [ ] T042 전체 검증: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web test && pnpm --filter web build`, `pnpm --filter web test:e2e`, `pnpm --filter web test:e2e:full`. 그 뒤 quickstart의 수동 절차 1~17과, 로컬 프로덕션 빌드에서 변경 전후 `/`의 Lighthouse 모바일 LCP를 비교해 늦어지지 않았는지 본다(SC-006)
- [ ] T043 PR(`main`으로, 제목 `feat(web): 가입한 사람이 첫 글까지 가도록 안내를 잇는다 (016)`): 본문에 스펙 링크, 인수 조건 31개 체크리스트, plan 단계에서 바꾼 결정(환영 안내를 홈에 둔 것), 기준값 표. 필수 체크 `ci-ok`

## Dependencies & Execution Order

```text
Phase 1 Setup
   └─ Phase 2 Foundational (T002~T005: hasMyPost)
         ├─ US1 (T006~T016)   ← MVP. hasMyPost가 없어도 되지만 T014가 write-form을 함께 고친다
         ├─ US2 (T017~T023)   ← hasMyPost 필요
         ├─ US3 (T024~T029)   ← hasMyPost 필요. T026은 T014 뒤(같은 파일 write-form.tsx)
         └─ US4 (T030~T031)   ← 독립. T029와 같은 파일(post-detail.tsx)이라 US3 뒤가 편하다
US5 (T032~T037)               ← Foundational도 필요 없다. 언제든 따로 할 수 있다
Phase 8                       ← 모든 스토리 뒤
```

같은 파일을 고치는 작업:
- `features/write-post/ui/write-form.tsx`: T014(US1) → T026(US3)
- `features/write-post/ui/write-form.test.tsx`: T007(US1) → T024(US3)
- `widgets/post-detail/ui/post-detail.tsx`와 그 테스트: T025, T029(US3) → T030, T031(US4)

## Parallel Opportunities

- Phase 2 뒤에 US1, US2, US5를 동시에 진행할 수 있다(겹치는 파일 없음).
- US1 안에서 T006, T007, T008(테스트)과 T010, T011(고정 문구)은 서로 다른 파일이라 함께 한다.
- US2 안에서 T017과 T018은 함께 한다.
- US5 안에서 T032, T033, T034는 함께 한다.

```text
# 예: Phase 2 뒤 세 갈래
갈래 A: T006 T007 T008 → T009 → T010 T011 → T012 → T013 → T014 → T015 T016
갈래 B: T017 T018 → T019 → T020 → T021 → T022 → T023
갈래 C: T032 T033 T034 → T035 → T036 T037
그 뒤:  T024 T025 → T026 T027 → T028 → T029 → T030 → T031 → Phase 8
```

## Implementation Strategy

1. **MVP = Phase 1 + 2 + US1.** 첫 글을 막는 불안과 빈 입력란을 먼저 없앤다. 이것만 내도 쓰기 화면을 여는 모든 회원에게 효과가 있다.
2. **다음 = US2.** 가입에서 첫 글로 가는 길을 잇는다. SC-001, SC-002를 재기 시작한다.
3. **그다음 = US3 + US4.** 다시 오게 하는 고리와 두 번째 글의 길이다.
4. **마지막 = US5.** 시각 자료는 다른 스토리와 겹치지 않으므로 따로 PR로 내도 된다.

한 PR로 낼 때도 스토리마다 커밋을 나눈다(Conventional Commits).
