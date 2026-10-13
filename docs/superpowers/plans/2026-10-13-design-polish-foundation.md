# 화면 완성도 개편 1: 토큰과 공용 부품 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모서리, 높이, 버튼의 무게 같은 모양 규칙을 동아리 서비스들에서 되풀이되는 값으로 맞추고, 뒤의 화면 작업이 쓸 공용 부품을 갖춘다.

**Architecture:** 값은 `globals.css`의 토큰에서 바꾸고 부품은 `shared/ui`에서 고친다. 화면은 부품을 따라 바뀐다. 새 부품(`SegmentedControl`, `EmptyState`, `PageHeader`)은 이 PR에서 만들고 카탈로그에 올린다. 화면에 끼우는 일은 빈 화면만 여기서 하고, 홈과 글 상세, 로그인의 구성은 다음 계획에서 한다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, class-variance-authority, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-polish-and-logo-design.md` ("토큰", "공용 부품", "나누는 순서"의 PR 1)

이 계획은 쓴 세션이 곧바로 실행한다. 사용자가 권장안대로 진행하라고 했다.

## Global Constraints

- 브랜치 `design/polish-and-logo`에서 일하고 PR은 `main`으로 보낸다. `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 색 토큰은 `color-kakao` 하나만 더한다. 잉크, 감정 색, 게임 노랑, 위험과 주의는 그대로다.
- 모서리: `radius-inline` 8px, `radius-control-sm` 12px, `radius-control` 16px, `radius-surface` 24px.
- 높이: 작은 것 40px, 기본 48px, 큰 것 56px. 보이는 높이가 44px보다 작으면 `::before`로 누르는 영역을 44px로 넓힌다.
- 입력란은 `line-strong` 테두리를 유지한다(흰 바탕과 3:1).
- 기능과 주소, 이름표와 버튼 이름, `aria-pressed`, `role`을 바꾸지 않는다. e2e가 이것들에 기댄다.
- 참고한 저장소의 코드와 에셋은 가져오지 않는다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 옅은 채움이 된 보조 버튼과 칩이 옅은 면(`bg-surface-sunken`) 위에 놓이면 사라진다. 그런 자리는 `outline`으로 바꾼다. → Task 2, 3
2. 높이가 바뀌어 320px에서 줄이 넘치거나 버튼이 두 줄이 되는 화면. → Task 8
3. 40px 버튼과 칩의 누르는 영역이 44px인지. → Task 2, 3
4. 토스트가 잉크 바탕이 되면 초점 표시(잉크 외곽선)가 보이지 않는다. 안쪽에 흰 외곽선으로 그린다. → Task 6
5. 빈 화면을 옮기면서 `role="alert"`와 "다시 불러오기"가 빠지지 않는지. → Task 5

---

### Task 1: 토큰

**Files:** `apps/web/src/core/styles/globals.css`, `tokens.test.ts`

- [ ] 이 계획과 스펙의 로고 결정을 커밋한다.
- [ ] 실패하는 테스트: "POLISH-AC1 모서리는 8, 12, 16, 24px이다", "POLISH-AC1 떠 있는 것과 고른 칸의 그림자가 있다", "POLISH-AC2 카카오 바탕 위의 잉크 글자는 4.5:1 이상이다".
- [ ] `--radius-control-sm: 12px`, `--radius-control: 16px`, `--radius-surface: 24px`, `--shadow-float`, `--shadow-raised`, 원색 `--kakao-yellow: #fee500`과 역할 `--color-kakao`를 더한다.
- [ ] 커밋: `feat(web): 모서리와 그림자 토큰을 맞추고 카카오 색을 더한다`

### Task 2: Button

**Files:** `shared/ui/button.tsx`(+test), `subtle`과 `secondary`를 쓰는 화면

- `secondary`는 옅은 채움(`bg-surface-sunken hover:bg-line`), `outline`은 옛 `secondary`(테두리). `subtle`은 없앤다.
- 크기: `sm`은 보이는 높이 40px(`h-10`, `rounded-control-sm`, `relative`와 `before:-inset-y-0.5`로 44px), `md`는 48px, `lg`는 56px.
- [ ] 실패하는 테스트: "POLISH-AC3 보조 버튼은 테두리 없이 옅은 채움이다", "POLISH-AC3 테두리 버튼은 outline이다", "POLISH-AC4 크기는 40, 48, 56px이고 작은 것의 누르는 영역은 44px이다".
- [ ] 구현한다. `subtle`을 쓰던 곳을 `secondary`로, 옅은 면 위의 `secondary`를 `outline`으로 옮긴다.
- [ ] 커밋: `feat(web): 보조 버튼을 옅은 채움으로 바꾸고 높이를 세 단계로 맞춘다`

### Task 3: Chip과 SegmentedControl

**Files:** `shared/ui/chip.tsx`(+test), `shared/ui/segmented-control.tsx`(+test), `shared/ui/index.ts`

- `Chip`: 고르지 않은 것은 `bg-surface-sunken text-fg-muted`, 고른 것은 `bg-action text-on-action`. 테두리 없음. 높이 40px, 누르는 영역 44px.
- `SegmentedControl<T extends string>({ label, options, value, onChange })`: `role="group"`과 `aria-label`, 칸마다 `aria-pressed`. 바탕은 `bg-surface-sunken rounded-control-sm p-1`, 고른 칸은 `bg-surface shadow-raised`. 칸의 보이는 높이 32px, 누르는 영역 44px.
- [ ] 실패하는 테스트: "POLISH-AC5 칩은 테두리 없이 채움으로 고른 것을 알린다", "POLISH-AC6 세그먼트는 고른 칸만 눌린 상태이고 누르면 그 값으로 onChange를 부른다", "POLISH-AC6 이미 고른 칸을 눌러도 onChange를 부르지 않는다".
- [ ] 구현, 커밋: `feat(web): 칩을 채움으로 바꾸고 세그먼트를 더한다`

### Task 4: 입력

**Files:** `shared/ui/input.tsx`, `textarea.tsx`, `select.tsx`, `text-field.tsx`(+tests)

- 높이 56px(`h-14`). 테두리는 그대로 두고 초점에서 `border-fg`와 2px(`focus-visible:border-2`는 크기가 흔들리므로 `outline` 공통 규칙을 그대로 쓰고 테두리 색만 바꾼다).
- [ ] 실패하는 테스트: "POLISH-AC7 입력은 56px이고 테두리가 남아 있다".
- [ ] 구현, 커밋: `feat(web): 입력의 높이와 모서리를 맞춘다`

### Task 5: EmptyState

**Files:** `shared/ui/empty-state.tsx`(+test), `shared/ui/index.ts`, 빈 화면과 오류를 그리는 위젯(`feed-list`, `post-detail`, `notification-list`, `my-activity`, `monster-collection`, `weekly-report` 가운데 같은 모양을 손으로 그린 곳)

- `EmptyState({ imageSrc, title, description, tone = "empty", children })`: 그림 132px(`alt=""`), 제목 `text-title font-extrabold`, 설명 `text-body-sm text-fg-subtle`, `children`은 버튼 자리. `tone="error"`면 제목이 `role="alert"`다.
- [ ] 실패하는 테스트: "POLISH-AC8 빈 화면은 그림, 제목, 설명, 버튼 자리를 가진다", "POLISH-AC8 오류일 때 제목이 alert로 읽힌다", "그림은 장식이라 이름이 없다".
- [ ] 구현하고 위젯들을 옮긴다. 문구와 버튼 이름은 그대로 둔다. 위젯 테스트가 그대로 통과해야 한다.
- [ ] 커밋: `feat(web): 빈 화면과 오류를 한 부품으로 맞춘다`

### Task 6: ConfirmDialog와 Toaster

**Files:** `shared/ui/confirm-dialog.tsx`(+test), `shared/ui/toast.tsx`(+test)

- 시트: `rounded-t-surface`, 맨 위에 손잡이(`aria-hidden`, 36×4px), 버튼 두 개는 `md` 크기로 같은 너비(`grid grid-cols-2`). 가운데 대화상자는 지금 배치를 유지한다.
- 토스트: `bg-action text-on-action shadow-float rounded-control`, 테두리 없음. hover는 `bg-action-hover`. 초점 표시는 `focus-visible:outline-on-action`과 `-outline-offset-2`.
- [ ] 실패하는 테스트: "POLISH-AC9 시트에는 손잡이가 있고 버튼 두 개의 너비가 같다", "POLISH-AC9 가운데 대화상자에는 손잡이가 없다", "POLISH-AC10 토스트는 잉크 바탕이고 떠 있다".
- [ ] 구현, 커밋: `feat(web): 시트에 손잡이를 두고 토스트를 잉크 띠로 바꾼다`

### Task 7: PageHeader

**Files:** `shared/ui/page-header.tsx`(+test), `shared/ui/index.ts`

- `PageHeader({ title, backHref, backLabel })`: 왼쪽 뒤로 가기 링크(44px, `aria-label`은 `backLabel`), 가운데 `h1`(`text-body-lg font-bold`), 오른쪽은 같은 너비의 빈 칸. 높이 56px.
- [ ] 실패하는 테스트: "POLISH-AC11 안쪽 화면의 머리말은 뒤로 가기와 h1 제목을 가진다", "뒤로 가기의 누르는 영역은 44px이다".
- [ ] 구현, 커밋: `feat(web): 안쪽 화면의 머리말 부품을 더한다`

### Task 8: 문서, 카탈로그, 전체 확인

**Files:** `apps/web/docs/DESIGN_SYSTEM.md`, `app/debug/design/page.tsx`, 높이나 모서리의 옛 클래스를 보는 테스트

- [ ] 디자인 문서의 "모양과 움직임", "컴포넌트" 표, "에셋과 출처"(참고한 저장소와 가져오지 않는다는 것)를 고친다. 카탈로그에 새 부품을 올린다.
- [ ] 확인 명령과 `pnpm --filter web test:e2e`를 돌린다. 320, 390, 1440px의 넘침 검사가 통과해야 한다.
- [ ] 390px에서 홈, 글 상세, 글쓰기, 기록, 로그인을 캡처해 눈으로 본다.
- [ ] 별도 검토자가 브랜치를 본다. 지적을 고친 뒤 `main`으로 PR을 연다.
