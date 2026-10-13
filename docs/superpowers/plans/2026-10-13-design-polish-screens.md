# 화면 완성도 개편 2: 화면 틀, 홈, 글 상세 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 홈에서 첫 글이 위로 올라오게 위쪽을 정리하고, 글 상세에서 댓글 입력을 화면 아래에 고정한다.

**Architecture:** 화면 틀(`widgets/app-shell`)이 글 상세에서 좁은 화면의 하단 메뉴를 숨긴다. 그 자리에 댓글 입력줄이 온다. 입력줄은 `features/comment`의 `WriteCommentForm`을 한 줄 배치로 바꾸고 `widgets/post-detail`이 좁은 화면에서만 아래에 붙인다. 홈의 정렬은 1번 계획에서 만든 `SegmentedControl`을, 글 상세의 머리말은 `PageHeader`를 쓴다.

**Tech Stack:** Next.js 16, React 19, react-hook-form, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-polish-and-logo-design.md` ("화면"의 화면 틀, 홈, 글 상세)

이 계획은 쓴 세션이 곧바로 실행한다. 브랜치 `design/polish-screens`는 1번(`design/polish-and-logo`, PR #72) 위에 쌓는다.

## Global Constraints

- `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 이름표와 버튼 이름("댓글", "답글", "등록", "답글 취소", "필터", "필터 지우기", "최신순", "인기순", "로그아웃", "고민 쓰기")과 `aria-pressed`를 바꾸지 않는다. 실제 API e2e가 기댄다.
- 넓은 화면(768px 이상)의 구성은 그대로다. 옆 메뉴, 위쪽의 고민 쓰기, 댓글 입력의 자리가 지금과 같다.
- 좁은 화면에서 아래에 붙는 것은 `env(safe-area-inset-bottom)`만큼 띄운다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 아래에 붙은 입력줄이 댓글 목록의 마지막 줄과 "댓글 더 보기"를 가리지 않아야 한다. → Task 4
2. 답글을 고르면 입력줄 위에 누구에게 다는지가 보이고, 취소하면 초점이 입력 칸으로 돌아와야 한다(지금 동작). → Task 3
3. 가상 키보드가 올라온 좁은 화면에서 입력줄이 키보드 위에 보여야 한다. 자동 검사로는 볼 수 없어 남는 위험으로 적는다. → Task 4
4. 글 상세에서 하단 메뉴가 없어도 돌아갈 길(뒤로 가기)이 키보드로 닿아야 한다. → Task 1, 4
5. 홈에서 로그아웃을 빼도 로그아웃할 길(기록 화면)이 있고 e2e가 그 길로 간다. → Task 2

---

### Task 1: 화면 틀

**Files:** `widgets/app-shell/ui/app-shell.tsx`(+test)

- 머리말 높이 좁은 화면 56px(`h-14`), 넓은 화면은 그대로.
- 주소가 `/post/{숫자}`이면 좁은 화면의 하단 메뉴를 그리지 않는다. 넓은 화면의 옆 메뉴는 그린다. 글 고치기(`/post/{id}/edit`)에는 하단 메뉴가 있다.
- [x] 이 계획을 커밋한다.
- [x] 실패하는 테스트: "POLISH-AC12 글 상세에서는 좁은 화면의 하단 메뉴가 없고 옆 메뉴는 있다", "POLISH-AC12 글 고치기에서는 하단 메뉴가 있다", "POLISH-AC12 좁은 화면의 머리말은 56px이다".
- [x] 구현, 커밋: `feat(web): 글 상세에서 하단 메뉴를 숨기고 머리말을 낮춘다`

### Task 2: 홈

**Files:** `app/home/page.tsx`(+test), `widgets/feed-list/ui/feed-controls.tsx`(+tests), `e2e-full/login-logout.spec.ts`, `e2e-full/notification.spec.ts`

- 홈에서 `LogoutButton`을 뺀다. 위쪽의 고민 쓰기는 `hidden md:inline-flex`. `HomeInstall`은 피드 아래로.
- 정렬은 `SegmentedControl`. 필터 여닫이(`details`)는 그대로 두고 같은 줄 오른쪽에 놓는다: 겹치는 격자 한 칸에 둘을 넣고, 여닫이의 `summary`를 오른쪽으로 민다. 펼친 칩은 그 아래 전체 너비다. `summary`는 옅은 채움의 40px 버튼 모양이다.
- [x] 실패하는 테스트: "POLISH-AC13 홈에는 로그아웃이 없다", "POLISH-AC13 위쪽의 고민 쓰기는 넓은 화면에서만 보인다", "POLISH-AC14 정렬은 세그먼트이고 필터 여닫이와 한 줄에 있다".
- [x] 구현한다. 실제 API e2e의 로그아웃은 기록 화면에서 누르게 고친다.
- [x] 커밋: `feat(web): 홈의 위쪽을 정리해 첫 글을 올린다`

### Task 3: 댓글 쓰기 폼

**Files:** `features/comment/ui/write-comment-form.tsx`(+test)

- 입력 칸과 등록 버튼을 한 줄에 둔다. 입력 칸은 한 줄에서 시작해 내용에 따라 네 줄(128px)까지 늘어난다(`scrollHeight`로 높이를 맞춘다. 높이를 알 수 없으면 건드리지 않는다).
- 글자 수는 남은 글자가 50자 아래일 때만 보인다. 넘었을 때의 `role="status"` 안내는 그대로다.
- 답글 대상, AI 친구 안내, 오류는 입력줄 위에 둔다.
- [x] 실패하는 테스트: "POLISH-AC15 글자 수는 남은 글자가 50자 아래일 때만 보인다", "POLISH-AC15 입력 칸과 등록 버튼이 한 줄에 있다". 기존 테스트(답글, 오류, 넘침 안내)는 그대로 통과해야 한다.
- [x] 구현, 커밋: `feat(web): 댓글 입력을 한 줄 배치로 바꾼다`

### Task 4: 글 상세

**Files:** `widgets/post-detail/ui/post-detail.tsx`, `post-comments.tsx`(+tests), `e2e/community-feed.spec.ts`

- `BackToFeed`를 `PageHeader`(제목은 "{닉네임}님의 고민", `hideTitle`)로 바꾼다. 글의 `h1`은 머리말의 것 하나다.
- 본문과 댓글 사이는 선 대신 옅은 면의 8px 띠.
- 댓글 쓰기 폼을 좁은 화면에서 화면 아래에 붙인다(`fixed inset-x-0 bottom-0`, 위쪽 선, 흰 면, 안전 영역). 넓은 화면에서는 지금 자리(댓글 목록 아래)에 둔다.
- [x] 실패하는 테스트: "POLISH-AC16 글 상세의 머리말은 뒤로 가기이고 h1은 하나다", "POLISH-AC17 댓글 입력줄은 좁은 화면에서 아래에 붙는다". e2e: 390px에서 하단 메뉴가 없고, 입력줄이 화면 아래에 있고, 맨 아래로 내렸을 때 마지막 댓글이 입력줄에 가리지 않는다. 1440px에서는 입력줄이 고정되지 않는다.
- [x] 구현, 커밋: `feat(web): 글 상세의 댓글 입력을 화면 아래에 고정한다`

### Task 5: 전체 확인

- [x] 확인 명령, `test:e2e`, 실제 API e2e(`infra/compose.e2e.yaml`)를 돌린다.
- [x] 390px에서 홈과 글 상세를 캡처해 눈으로 본다.
- [x] 별도 검토자가 본다. 지적을 고친 뒤 PR을 연다(#72가 병합되기 전이면 #72 위에 쌓였다고 적는다).

## 하지 않는 것

- 몬스터 무대 안의 배치(감정 이름과 HP 숫자의 자리)는 그대로 둔다. `MonsterDisplay`가 레이드와 함께 쓰는 부품이라 따로 손댈 만큼의 차이가 아니다.
- 알림, 글쓰기, AI 친구 안내의 머리말은 이번에 바꾸지 않는다. 그 화면들은 큰 제목과 설명이 화면의 첫 줄 역할을 한다.
