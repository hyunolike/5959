# 글 상세와 댓글 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 글 상세를 감정 색 무대 중심으로 다시 짜고, 댓글과 안전 안내를 새 토큰으로 옮긴다. 앞선 PR(#55)의 검토에서 나온 두 가지도 함께 고친다.

**Architecture:** 무대(`MonsterStage`) 안에 큰 몬스터(`MonsterDisplay`의 `detail`), 큰 HP 막대, 공감 버튼(`GameButton`)을 둔다. 공감을 누르면 바로 위의 HP가 줄어드는 것이 한 화면에 보인다. 그 아래에 작성자와 본문, 댓글이 온다. 위기 안내와 신고, 재검토 요청은 감정 색과 게임 요소 없이 `warning`과 `danger` 토큰만 쓴다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, `@phosphor-icons/react`, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 4번)

이 계획은 쓴 세션이 곧바로 실행한다. 그래서 다시 짜는 부품(Task 1~4)은 인터페이스와 테스트를 적고, 색 이름만 바꾸는 단계(Task 5, 6)는 대응표로 적는다.

## Global Constraints

- PR은 `design/system-v2`로 보낸다. `main`으로 보내지 않는다. `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 기능과 라우트는 그대로다. 실제 API e2e가 기대는 것을 바꾸지 않는다.
  - 글자: "공감 N"(공감 버튼의 이름 전체), "댓글 N", "HP n/m", "처치됨", "분석 중", "이 고민에 달린 댓글", "마음 이야기로 돌아가기".
  - `aria-label="몬스터"` 자리 안에서 `/^HP \d+\/\d+$/`로 HP 글자를 찾는다. **"처치됨"을 HP 문단 안에 넣지 않는다.**
  - `aria-label="공감과 댓글 수"` 묶음, `article` "고민 글", 공감 버튼의 `aria-pressed`, `aria-busy`, `disabled`.
- 게임 요소는 무대 안의 큰 HP 막대와 공감 버튼뿐이다. 위기 안내, 숨김 표시, 신고, 재검토 요청에는 감정 색과 게임 요소를 쓰지 않는다(DS-US3-AC4).
- 이 계획이 고치는 파일에는 옛 색 이름과 Lucide를 남기지 않는다. 옮긴 뒤 `UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`로 목록을 갱신한다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 처치된 몬스터의 상세에서 HP 글자의 전체 내용이 "HP 0/10"이어야 한다. "처치됨"이 같은 문단에 붙으면 실제 API e2e가 깨진다. → Task 2
2. 공감 요청이 가는 동안 버튼이 회색으로 깜빡이면 안 되고, 두 번 눌러도 요청은 한 번이어야 한다. → Task 3
3. 내 글에는 공감 버튼이 없고 "공감 N" 글자만 보여야 한다. 몬스터가 아직 없으면 무대가 회색이고 "분석 중"이 보여야 한다. → Task 4
4. 좁은 화면(320px, 360px)에서 "HP n/m"과 "처치됨"이 글자 단위로 꺾이면 안 된다. 자리가 모자라면 공감과 댓글 수가 다음 줄로 내려간다. → Task 1
5. 위기 안내가 감정 색 무대 위에 있어도 그 색을 물려받으면 안 된다. → Task 6

---

### Task 1: 앞선 PR의 검토 지적을 고친다

**Files:** `entities/monster/ui/monster-summary.tsx`(+test), `entities/post/ui/post-card.tsx`(+test), `widgets/post-detail/ui/similar-posts.tsx`, `widgets/my-activity/ui/activity-list.tsx`, `e2e/community-feed.spec.ts`

- [ ] 브랜치 `design/post-detail`을 `origin/design/system-v2`에서 만들고 이 계획을 커밋한다.
- [ ] 실패하는 테스트: `MonsterSummary`의 "HP n/m"과 "처치됨"이 각각 `whitespace-nowrap`이고 글자 칸이 `shrink-0`이다. `PostCard`의 몬스터 칸이 `flex-auto`, 공감과 댓글 칸이 `ml-auto`다.
- [ ] 고친다: `MonsterSummary`의 HP 글자를 `<span className="whitespace-nowrap">`으로 감싸고 문단을 `flex-wrap`, 글자 칸을 `w-28 shrink-0`으로 둔다. `PostCard`의 몬스터 칸을 `min-w-0 flex-auto`로, 수 칸에 `ml-auto`를 준다.
- [ ] 구분선: `similar-posts.tsx`의 `ul`을 `rounded-surface border-line divide-line flex flex-col divide-y overflow-hidden border`로 바꾸고 죽은 선택자를 지운다. `activity-list.tsx`의 `ul`을 `border-line divide-line flex flex-col divide-y border-y`로 바꾼다(글 목록과 댓글 목록 모두).
- [ ] e2e: 가짜 피드에 처치된 몬스터와 긴 닉네임, 두 자리 수의 글을 하나 더하고, 320px에서 "처치됨"과 그 글의 "HP 0/10"의 높이가 한 줄(24px 미만)인지 확인한다. 몬스터 그림의 폭 하한을 44로 올린다. 기존 `progressbar` 단언은 글 하나 안으로 좁힌다.
- [ ] 커밋: `fix(web): 좁은 화면에서 HP 글자가 꺾이지 않게 하고 목록 구분선을 되살린다`

### Task 2: 큰 몬스터

**Files:** `entities/monster/ui/monster-view.tsx`(다시 쓰기, `card` 변형 제거), `monster-view.test.tsx`, `monster-stage.tsx`(+test: `emotion` 선택)

**Interfaces — Produces:**
- `MonsterDisplay({ monster, variant: "detail" | "boss", className? })`. 뿌리에 `data-emotion`. 그림(상세 176px, 640px 이상 224px. 보스 248px, 288px) → `EmotionTag`와 단계 이름(처치됐으면 "처치됨" 알약) → 큰 `HpBar`와 `<p>HP {hp}/{maxHp}</p>`. 맞는 반응(`useHitReaction`)과 대기 움직임은 그대로다.
- `MonsterStage({ emotion?: EmotionType, ... })`. 감정이 없으면 `data-emotion`을 붙이지 않아 회색 무대가 된다.

- [ ] 실패하는 테스트: `card` 경우를 걷어 내고 더한다. "DS-US3-AC2 큰 HP 막대(22px)와 감정 색 범위를 쓴다", "처치된 몬스터의 HP 글자는 처치됨과 다른 요소다"(`getByText(/^HP \d+\/\d+$/)`의 내용이 "HP 0/10"), "DS-US3-AC2 감정이 없는 무대는 감정 색 범위를 열지 않는다".
- [ ] 구현하고 `pnpm --filter web exec vitest run src/entities/monster`와 레이드 위젯 테스트(`src/widgets/raid-arena`)를 돌린다. 레이드는 다음 PR에서 다시 그리지만 `boss` 변형이 깨지면 안 된다.
- [ ] 커밋: `feat(web): 큰 몬스터를 감정 태그와 큰 HP 막대로 다시 그린다`

### Task 3: 공감 버튼

**Files:** `shared/ui/game-button.tsx`(+test), `features/like/ui/post-like-button.tsx`, `like-buttons.test.tsx`

**Interfaces — Produces:** `GameButton`이 `aria-pressed="true"`면 눌린 모양(내려가 있고 그림자 없음)이고, `disabled`와 `aria-busy`가 함께면 색을 유지한다. `PostLikeButton`은 `GameButton`이고 이름은 "공감 N" 그대로다.

- [ ] 실패하는 테스트: `GameButton`에 `aria-pressed:translate-y-1`, `aria-pressed:shadow-none`, `disabled:aria-busy:bg-game`. `PostLikeButton`의 버튼이 `bg-game`이고 눌린 상태에서 `aria-pressed="true"`.
- [ ] 구현: 하트는 Phosphor `Heart`(누르면 `weight="fill"`), 오류 문구는 `text-label text-danger`.
- [ ] 커밋: `feat(web): 글 공감을 게임 버튼으로 바꾼다`

### Task 4: 글 상세의 짜임

**Files:** `widgets/post-detail/ui/post-detail.tsx`, `post-detail.test.tsx`

읽는 순서를 무대 → 작성자와 본문 → 댓글로 바꾼다. #50은 본문을 몬스터 앞에 뒀지만, 이번 설계는 글 상세에서 몬스터를 주인공으로 정했다.

- 안전 안내(맨 위, 그대로) → 무대(`MonsterStage`, `rounded-surface p-5`): `aria-label="몬스터"` 자리(큰 몬스터 또는 "분석 중") + `aria-label="공감과 댓글 수"` 묶음(공감 버튼 또는 "공감 N" 글자, "댓글 N").
- 작성자(굵게)와 직군, 경력, 수정과 삭제 또는 신고 → 본문 `text-body-lg`.
- 댓글 구역: 위쪽 구분선, 제목 `text-title`, 댓글 말투는 회색 알약.
- 뒤로 가기는 Phosphor `CaretLeft`. 불러오는 중과 볼 수 없는 글 안내는 `Card` 그대로에 글자 토큰만 바꾼다.

- [ ] 실패하는 테스트: "DS-US3-AC2 무대가 글의 감정 색이고 몬스터와 공감 버튼을 담는다", "DS-US3-AC2 무대가 본문보다 먼저 온다", "내 글의 무대에는 공감 버튼이 없고 공감 수만 있다", "몬스터가 아직 없으면 무대는 감정 색 범위를 열지 않는다".
- [ ] 구현하고 `src/widgets/post-detail` 테스트를 돌린다.
- [ ] 커밋: `feat(web): 글 상세를 감정 색 무대 중심으로 다시 짠다`

### Task 5: 댓글

**Files:** `entities/comment/ui/comment-item.tsx`, `widgets/post-detail/ui/post-comments.tsx`, `features/like/ui/comment-like-button.tsx`, `features/comment/ui/write-comment-form.tsx`, `features/comment/ui/comment-manage-menu.tsx`

대응표(이 Task와 Task 6에 쓴다):

| 옛 | 새 |
| --- | --- |
| `text-neutral-900`, `-800` | `text-fg` |
| `text-neutral-700` | `text-fg-muted` |
| `text-neutral-600`, `-500` | `text-fg-subtle` |
| `bg-white` | `bg-surface` |
| `bg-neutral-50`, `-100`, `bg-brand-50`, `-100` | `bg-surface-sunken` |
| `bg-neutral-200` | `bg-line` |
| `border-neutral-200`, `divide-neutral-100`, `-200` | `border-line`, `divide-line` |
| `border-neutral-300` | `border-line-strong` |
| `text-brand-700`, `border-brand-600`, `bg-brand-600` | `text-fg`, `border-action`, `bg-action` |
| `text-red-600`, `-700`, `border-red-600`, `bg-red-50`, `bg-red-600` | `text-danger`, `border-danger`, `bg-danger-tint`, `bg-danger` |
| `bg-amber-50`, `text-amber-700`~`-900`, `border-amber-200`, `-300` | `bg-warning-tint`, `text-warning`, `border-warning` |
| `text-white` | `text-on-action`(빨간 바탕이면 `text-on-danger`) |
| `focus-visible:ring-brand-600 focus-visible:ring-2 focus-visible:outline-none`(과 `ring-offset`) | 지운다(공통 초점 규칙을 쓴다) |
| `text-base`, `text-sm`, `text-xs` | `text-body`, `text-body-sm`, `text-caption` |
| `font-semibold` | `font-bold` |
| `shadow-*` | 지우고 `border-line-strong border`로 경계를 준다 |
| Lucide 아이콘 | 같은 뜻의 Phosphor 아이콘 |

직접 만든 `<button>`은 `Button`(`ghost`, `sm`)이나 `IconButton`으로 바꾼다. 댓글 공감은 누르면 `bg-surface-sunken text-fg`와 채운 하트다. 메뉴 항목처럼 모양이 다른 버튼은 토큰만 바꾼다.

- [ ] 실패하는 테스트: "DS-US3-AC4 숨겨진 내 댓글 표시는 주의 색이다"(`comment-item.test.tsx`), "DS-US2-AC1 답글 달기는 공용 버튼이다"(누르는 영역 `min-h-11`).
- [ ] 옮기고 `grep -nE "neutral-|brand-|red-[0-9]|amber-|bg-white|text-white|lucide|font-semibold"`로 남은 것이 없는지 본다.
- [ ] 커밋: `feat(web): 댓글을 새 토큰과 공용 버튼으로 옮긴다`

### Task 6: 안전 안내와 신고, 추천

**Files:** `widgets/post-detail/ui/safety-notice.tsx`(+test), `entities/support-resource/ui/support-resource-list.tsx`, `features/report-content/ui/report-button.tsx`, `features/request-review/ui/request-review-button.tsx`, `features/manage-post/ui/delete-post-button.tsx`, `widgets/post-detail/ui/similar-posts.tsx`

- [ ] 실패하는 테스트: "DS-US3-AC4 위기 안내는 감정 색과 게임 요소를 쓰지 않고, 감정 색 범위 안에 있어도 회색 기본값으로 되돌린다". 안내의 뿌리에 `data-emotion="none"`을 붙여 무대의 색을 끊는다. `globals.css`에 `[data-emotion="none"]`이 `:root`의 기본값(`--ink-600`, `--ink-100`, `--ink-700`)을 다시 선언하고, `tokens.test.ts`가 이를 확인한다.
- [ ] 대응표로 옮기고 남은 옛 이름을 확인한다.
- [ ] 커밋: `feat(web): 안전 안내와 신고를 주의와 오류 색으로 옮긴다`

### Task 7: 브라우저 검증과 PR

- [ ] `pnpm --filter web test:e2e`를 돌린다. 상세를 여는 가짜 e2e(`community-feed`, `install-interactions`)의 캡처를 390px로 열어 무대, 본문, 댓글의 순서와 색을 눈으로 확인한다.
- [ ] 확인 명령 전체와 `pnpm --filter web build`를 돌린다.
- [ ] `design/system-v2`로 PR을 연다. 제목: `feat(web): 글 상세와 댓글을 새 디자인 시스템으로 옮긴다`.
- [ ] 별도 검토자가 브랜치 전체를 본다. 특히 `apps/web/e2e-full/*.spec.ts`가 기대는 선택자를 새 마크업과 맞춰 보게 한다.
