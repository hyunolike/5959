# 앱 틀과 피드 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앱 틀(헤더, 메뉴)과 피드(정렬과 필터, 글 항목)를 새 토큰과 부품으로 옮겨, 피드에서 감정 태그와 48px 몬스터, 상대 시각이 보이게 한다.

**Architecture:** 글 항목(`PostCard`)은 `entities/post`에 있어 `entities/monster`를 가져올 수 없다. 그래서 감정 태그와 몬스터 요약을 위젯이 넘기는 두 개의 그리기 함수로 받는다. 몬스터 요약(썸네일, HP 숫자, HP 막대)은 `entities/monster`에 새 부품 `MonsterSummary`로 둔다. 메뉴 아이콘은 Phosphor로 바꾸고 선택된 항목은 채운 모양으로 그린다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, `@phosphor-icons/react` 2.1.10, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 3번)

## Global Constraints

- PR은 `design/system-v2`로 보낸다. **`main`으로 보내지 않는다.**
- `apps/api`, `contracts/`, `webbb-be/`, `webbb-fe/`는 건드리지 않는다. 새 의존성은 없다.
- 기능과 라우트는 그대로다. 기존 테스트의 동작 기대값(주소, `aria-current`, `aria-pressed`, `datetime`, "공감 N", "댓글 N", "HP n/m", "분석 중")은 바꾸지 않는다.
- 글꼴 굵기는 400, 500, 700, 800만 쓴다. 누르는 영역은 44px 이상이다.
- 이 계획이 고치는 파일에는 옛 색 이름(`neutral-*`, `brand-*`, `red-*`, `white` 등)을 남기지 않는다. 파일을 옮긴 뒤 `UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`로 목록을 갱신한다.
- 게임 요소(`GameButton`, 큰 HP 막대)는 이 계획의 화면에 쓰지 않는다.
- 커밋 메시지는 Conventional Commits. 테스트 이름에 인수 조건 ID를 넣는다.
- 모든 명령은 저장소 루트에서 실행한다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 감정 분석이 아직 안 끝난 글(`monster: null`)에는 감정 태그와 몬스터가 없어야 하고 "분석 중"이 보여야 한다. → Task 2
2. 처치된 몬스터는 피드에서 쓰러진 그림과 "처치됨"으로 보여야 한다. 최대 HP가 0으로 와도 깨지지 않아야 한다. → Task 1
3. 작성 시각이 깨진 값이면 "Invalid Date"가 아니라 빈 칸이어야 하고, `datetime` 속성은 원래 값을 그대로 둔다. → Task 2
4. 긴 닉네임과 감정 태그, 직군과 경력, 시각이 한 줄에 다 안 들어가는 320px에서 줄바꿈되어야 하고 가로로 넘치지 않아야 한다. → Task 5
5. 비회원의 잠긴 메뉴는 아이콘이 바뀐 뒤에도 로그인 안내를 열어야 하고, 이동 대기 표시(`data-navigation-pending`)가 그대로 동작해야 한다. → Task 4

---

### Task 1: 몬스터 요약 부품

**Files:**
- Create: `apps/web/src/entities/monster/ui/monster-summary.tsx`
- Create: `apps/web/src/entities/monster/ui/monster-summary.test.tsx`
- Modify: `apps/web/src/entities/monster/index.ts`

**Interfaces:**
- Consumes: `MonsterThumb({ emotion, stage, className? })`, `HpBar({ emotion, hp, maxHp, size?, label?, className? })`, `hpStageOfRatio(ratio): HpStage`, `MonsterView`.
- Produces: `MonsterSummary({ monster: Pick<MonsterView, "emotion" | "status"> & { hp: number; maxHp: number }; className?: string })`

- [ ] **Step 1: 브랜치를 만든다**

```bash
git fetch origin
git switch -c design/feed-shell origin/design/system-v2
pnpm install
git add docs/superpowers/plans/2026-10-13-design-system-feed-shell.md docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md
git commit -m "docs: 앱 틀과 피드 개편 구현 계획"
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/web/src/entities/monster/ui/monster-summary.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MonsterSummary } from "./monster-summary";

describe("MonsterSummary", () => {
  it("DS-US3-AC1 48px 몬스터와 HP 숫자, HP 막대를 보인다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "ANXIETY", hp: 5, maxHp: 10, status: "ALIVE" }}
      />,
    );
    const image = screen.getByRole("img", { name: "불안 몬스터, 상처 입음" });
    expect(image.parentElement).toHaveClass("size-12");
    expect(screen.getByText("HP 5/10")).toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "5");
  });

  it("DS-US3-AC1 처치된 몬스터는 쓰러진 그림과 처치됨으로 보인다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "LETHARGY", hp: 0, maxHp: 10, status: "DEFEATED" }}
      />,
    );
    expect(
      screen.getByRole("img", { name: "무기력 몬스터, 쓰러짐" }),
    ).toBeInTheDocument();
    expect(screen.getByText("처치됨")).toBeInTheDocument();
    expect(screen.getByText("HP 0/10")).toBeInTheDocument();
  });

  it("최대 HP가 0으로 와도 그린다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "IRRITATION", hp: 0, maxHp: 0, status: "ALIVE" }}
      />,
    );
    expect(screen.getByRole("progressbar").firstElementChild).toHaveStyle({
      width: "0%",
    });
  });
});
```

Run: `pnpm --filter web exec vitest run src/entities/monster/ui/monster-summary.test.tsx`
Expected: FAIL. `./monster-summary`를 찾지 못한다.

- [ ] **Step 3: 부품을 쓴다**

`apps/web/src/entities/monster/ui/monster-summary.tsx`

```tsx
import { cn } from "@/shared/lib";

import { hpStageOfRatio, type HpStage } from "../model/hp-stage";
import type { MonsterView } from "../model/types";
import { HpBar } from "./hp-bar";
import { MonsterThumb } from "./monster-thumb";

/** 목록에 쓰는 몬스터 요약: 48px 그림, HP 숫자, 작은 HP 막대. 처치됐으면 "처치됨"을 붙인다. */
export function MonsterSummary({
  monster,
  className,
}: {
  monster: Pick<MonsterView, "emotion" | "status"> & {
    hp: number;
    maxHp: number;
  };
  className?: string;
}) {
  const defeated = monster.status === "DEFEATED";
  const stage: HpStage = defeated
    ? "defeated"
    : hpStageOfRatio(monster.hp / monster.maxHp);

  return (
    <div className={cn("flex items-center gap-3", className)}>
      <MonsterThumb emotion={monster.emotion} stage={stage} />
      <div
        data-emotion={monster.emotion}
        className="flex w-28 min-w-0 flex-col gap-1.5"
      >
        <p className="text-caption text-emotion-fg flex items-center gap-1.5 font-bold tabular-nums">
          HP {monster.hp}/{monster.maxHp}
          {defeated ? (
            <span className="bg-action text-on-action rounded-full px-1.5 font-medium">
              처치됨
            </span>
          ) : null}
        </p>
        <HpBar
          emotion={monster.emotion}
          hp={monster.hp}
          maxHp={monster.maxHp}
        />
      </div>
    </div>
  );
}
```

`apps/web/src/entities/monster/index.ts`의 끝에 추가한다.

```ts
export { MonsterSummary } from "./ui/monster-summary";
```

- [ ] **Step 4: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web exec vitest run src/entities/monster && pnpm --filter web lint:fsd`
Expected: PASS.

```bash
git add apps/web/src/entities/monster
git commit -m "feat(web): 목록용 몬스터 요약 부품을 더한다"
```

---

### Task 2: 글 항목

**Files:**
- Modify: `apps/web/src/entities/post/ui/post-card.tsx` (전체 다시 쓰기)
- Modify: `apps/web/src/entities/post/ui/post-card.test.tsx`
- Modify: `apps/web/src/widgets/feed-list/ui/feed-list.tsx` (`PostCard` 호출부)
- Modify: `apps/web/src/widgets/my-activity/ui/my-activity.tsx` (`renderPost`)
- Modify: `apps/web/src/widgets/post-detail/ui/similar-posts.tsx` (`PostCard` 호출부)

**Interfaces:**
- Consumes: `formatDate`, `formatRelativeTime`(`@/shared/lib`), `EmotionTag`, `MonsterSummary`(`@/entities/monster`, 위젯에서만).
- Produces: `PostCard({ item: FeedItem; authorMeta: string; renderEmotion: (monster: MonsterView) => ReactNode; renderMonster: (monster: MonsterView) => ReactNode; showCreatedAt?: boolean })`. `renderEmotion`이 새로 필수다. `HIDDEN_FROM_OTHERS_LABEL`은 그대로 내보낸다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/entities/post/ui/post-card.test.tsx`의 맨 위 import를 아래로 바꾼다.

```tsx
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
```

`describe("PostCard", () => {` 바로 아래에 넣는다.

```tsx
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T00:12:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });
```

기존 두 테스트의 `<PostCard ... />`에 `renderEmotion` 속성을 더한다. 첫 테스트에는 `renderEmotion={(monster) => <p>감정 {monster.emotion}</p>}`, 둘째 테스트에는 `renderEmotion={() => <p>감정</p>}`. 첫 테스트의 기대값에 아래 한 줄을, 둘째 테스트의 기대값에 그 아래 한 줄을 더한다.

```tsx
    expect(screen.getByText("감정 ANXIETY")).toBeInTheDocument();
```

```tsx
    expect(screen.queryByText("감정")).not.toBeInTheDocument();
```

`describe`의 끝에 새 테스트를 더한다.

```tsx
  it("DS-US3-AC1 작성 시각을 상대 시각으로 쓰고 원래 시각은 datetime에 둔다", () => {
    render(
      <PostCard
        item={item()}
        authorMeta="개발 · 1년차"
        showCreatedAt
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    const time = screen.getByText("12분 전");
    expect(time.tagName).toBe("TIME");
    expect(time).toHaveAttribute("datetime", "2026-10-03T00:00:00Z");
  });

  it("작성 시각이 깨진 값이면 빈 칸으로 두고 datetime은 그대로 둔다", () => {
    const { container } = render(
      <PostCard
        item={item({ createdAt: "not-a-date" })}
        authorMeta="개발 · 1년차"
        showCreatedAt
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    const time = container.querySelector("time");
    expect(time).toHaveTextContent("");
    expect(time).toHaveAttribute("datetime", "not-a-date");
    expect(container).not.toHaveTextContent("Invalid");
  });

  it("DS-US3-AC4 숨겨진 글 표시는 감정 색이 아니라 주의 색을 쓴다", () => {
    render(
      <PostCard
        item={item({ hidden: true })}
        authorMeta="개발 · 1년차"
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    expect(screen.getByText("다른 회원에게 보이지 않아요")).toHaveClass(
      "bg-warning-tint",
      "text-warning",
    );
  });
```

Run: `pnpm --filter web exec vitest run src/entities/post/ui/post-card.test.tsx`
Expected: FAIL. 새 테스트 세 개가 실패한다("12분 전"이 없고, 깨진 값에서 "Invalid Date"가 보이고, `bg-warning-tint`가 없다).

- [ ] **Step 2: 글 항목을 다시 쓴다**

`apps/web/src/entities/post/ui/post-card.tsx` 전체

```tsx
import { ChatCircle, Heart } from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";
import type { ReactNode } from "react";

import { formatDate, formatRelativeTime } from "@/shared/lib";

import type { FeedItem } from "../model/types";

type MonsterView = NonNullable<FeedItem["monster"]>;

/** 숨겨진 내 글과 댓글에 붙는 표시(005 US1-AC3). 내 글 목록과 글 상세가 같은 말을 쓴다. */
export const HIDDEN_FROM_OTHERS_LABEL = "다른 회원에게 보이지 않아요";

/**
 * 피드의 글 하나(US2-AC1). 감정 태그와 몬스터 그림은 다른 엔티티(monster)의 것이라 위젯이 넘긴다.
 * 몬스터가 아직 없으면(`monster == null`) 분석 중이고 감정 태그도 없다.
 * 읽는 순서는 감정과 누가 → 무슨 고민 → 몬스터와 HP → 공감과 댓글 수다.
 * 작성 시각(`showCreatedAt`, 004 US3-AC1)은 "12분 전"처럼 쓰고, 정확한 시각은 `datetime`과 `title`에 둔다.
 */
export function PostCard({
  item,
  authorMeta,
  renderEmotion,
  renderMonster,
  showCreatedAt = false,
}: {
  item: FeedItem;
  authorMeta: string;
  renderEmotion: (monster: MonsterView) => ReactNode;
  renderMonster: (monster: MonsterView) => ReactNode;
  showCreatedAt?: boolean;
}) {
  const relativeTime = formatRelativeTime(item.createdAt);

  return (
    <Link
      href={`/post/${item.postId}`}
      className="hover:bg-surface-sunken active:bg-line block px-5 py-[18px] transition-colors focus-visible:-outline-offset-2"
    >
      <div className="text-label text-fg-subtle flex flex-wrap items-center gap-x-2 gap-y-1">
        {item.monster ? renderEmotion(item.monster) : null}
        <span className="text-fg font-bold break-words">
          {item.author.nickname}
        </span>
        <span>{authorMeta}</span>
        {showCreatedAt ? (
          <time
            dateTime={item.createdAt}
            title={
              relativeTime
                ? formatDate(item.createdAt, "YYYY.MM.DD HH:mm")
                : undefined
            }
            className="ml-auto tabular-nums"
          >
            {relativeTime}
          </time>
        ) : null}
      </div>
      <p className="text-body text-fg mt-2.5 line-clamp-4 break-words whitespace-pre-line">
        {item.contentPreview}
      </p>
      <div className="mt-3.5 flex flex-wrap items-center gap-x-3 gap-y-2">
        <div aria-label="몬스터" className="min-w-0 flex-1">
          {item.monster ? (
            renderMonster(item.monster)
          ) : (
            <p className="text-label text-fg-subtle">분석 중</p>
          )}
        </div>
        <div className="text-label text-fg-muted flex shrink-0 items-center gap-3.5 font-medium tabular-nums">
          <span className="inline-flex items-center gap-1">
            <Heart
              size={18}
              weight={item.likedByMe ? "fill" : "regular"}
              aria-hidden
            />
            공감 {item.likeCount}
          </span>
          {item.likedByMe ? <span className="sr-only">내가 공감함</span> : null}
          <span className="inline-flex items-center gap-1">
            <ChatCircle size={18} aria-hidden />
            댓글 {item.commentCount}
          </span>
        </div>
      </div>
      {item.hidden ? (
        <p className="bg-warning-tint text-caption text-warning mt-3 inline-flex rounded-full px-2 py-0.5 font-medium">
          {HIDDEN_FROM_OTHERS_LABEL}
        </p>
      ) : null}
    </Link>
  );
}
```

Run: `pnpm --filter web exec vitest run src/entities/post/ui/post-card.test.tsx`
Expected: PASS (5개).

- [ ] **Step 3: 세 호출부가 감정 태그와 몬스터 요약을 넘기게 한다**

세 파일 모두 `@/entities/monster` import에서 `MonsterDisplay`를 `EmotionTag, MonsterSummary`로 바꾼다(그 파일이 `MonsterDisplay`를 다른 곳에서도 쓰면 남긴다. `grep -n MonsterDisplay <파일>`로 확인한다). 그리고 `PostCard`에 넘기는 `renderMonster`를 아래 두 속성으로 바꾼다.

```tsx
              renderEmotion={(monster) => (
                <EmotionTag emotion={monster.emotion} />
              )}
              renderMonster={(monster) => <MonsterSummary monster={monster} />}
```

대상:
- `apps/web/src/widgets/feed-list/ui/feed-list.tsx`
- `apps/web/src/widgets/my-activity/ui/my-activity.tsx`의 `renderPost`
- `apps/web/src/widgets/post-detail/ui/similar-posts.tsx`

`MonsterDisplay`의 `card` 변형은 이제 쓰는 곳이 없지만 지우지 않는다. 글 상세 계획에서 `MonsterDisplay`를 다시 그릴 때 정리한다.

- [ ] **Step 4: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web typecheck && pnpm --filter web test`
Expected: 옛 색 이름 테스트 하나("옛 색 이름을 줄인 파일은 목록의 수도 줄인다")만 실패한다. `post-card.tsx`의 옛 이름이 0이 됐기 때문이다. 다른 테스트가 실패하면 그 테스트가 피드 항목의 옛 모양(예: 감정 이름의 위치)을 단정한 것이니, 동작 기대값은 그대로 두고 찾는 방법만 고친다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
grep -c "entities/post/ui/post-card" apps/web/src/core/styles/legacy-style-files.json
```

Expected: `0`.

```bash
git add apps/web/src
git commit -m "feat(web): 글 항목에 감정 태그, 48px 몬스터, 상대 시각을 넣는다"
```

---

### Task 3: 피드 정렬과 필터, 목록, 홈 머리말

**Files:**
- Modify: `apps/web/src/widgets/feed-list/ui/feed-controls.tsx` (전체 다시 쓰기)
- Modify: `apps/web/src/widgets/feed-list/ui/feed-list.tsx`
- Modify: `apps/web/src/widgets/feed-list/ui/feed-list.test.tsx`
- Modify: `apps/web/src/app/home/page.tsx`

**Interfaces:**
- Consumes: `Chip({ pressed, ...button })`, `Button`(`@/shared/ui`).
- Produces: `FeedControls({ filter, onChange })`의 속성은 그대로다. 정렬은 `aria-pressed`가 붙은 버튼 두 개, 필터는 `details`와 `summary` 그대로다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/widgets/feed-list/ui/feed-list.test.tsx`의 `describe` 끝에 더한다(`renderFeed`, `ok`, `feedItem`은 파일에 이미 있다. `userEvent`가 import되어 있지 않으면 `import userEvent from "@testing-library/user-event";`를 더한다).

```tsx
  it("DS-US2-AC1 정렬과 필터는 공용 칩이고 고른 것만 눌린 상태다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ items: [feedItem(1)], nextCursor: null })),
    );
    renderFeed();
    await screen.findByText("고민 1");

    const latest = screen.getByRole("button", { name: "최신순" });
    expect(latest).toHaveAttribute("aria-pressed", "true");
    expect(latest).toHaveClass("h-9", "rounded-full");
    expect(screen.getByRole("button", { name: "인기순" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("DS-US3-AC1 목록은 카드 테두리 없이 구분선으로 나눈다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          ok({ items: [feedItem(2), feedItem(1)], nextCursor: null }),
        ),
    );
    renderFeed();
    await screen.findByText("고민 2");

    const list = screen.getByRole("list");
    expect(list).not.toHaveClass("rounded-surface");
    for (const row of within(list).getAllByRole("listitem")) {
      expect(row).toHaveClass("border-b", "border-line");
    }
  });
```

`within`이 import되어 있지 않으면 `@testing-library/react` import에 더한다.

Run: `pnpm --filter web exec vitest run src/widgets/feed-list`
Expected: FAIL. 정렬 버튼에 `h-9`가 없고, 목록에 `rounded-surface`가 있다.

- [ ] **Step 2: 정렬과 필터를 다시 쓴다**

`apps/web/src/widgets/feed-list/ui/feed-controls.tsx` 전체

```tsx
"use client";

import { SlidersHorizontal } from "@phosphor-icons/react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import type { FeedFilter, FeedOrder } from "@/entities/post";
import { Button, Chip } from "@/shared/ui";

import {
  CAREER_YEARS,
  DEFAULT_FEED_FILTER,
  JOB_ROLES,
  toggleValue,
} from "../model/filter-params";

const ORDER_LABELS: Record<FeedOrder, string> = {
  LATEST: "최신순",
  POPULAR: "인기순",
};

/** 정렬 토글과 직군, 경력 다중 선택(FR-011). 같은 종류 안에서는 하나라도 맞으면 통과한다. */
export function FeedControls({
  filter,
  onChange,
}: {
  filter: FeedFilter;
  onChange: (next: FeedFilter) => void;
}) {
  const filtered = filter.jobRoles.length > 0 || filter.careerYears.length > 0;

  return (
    <div className="flex flex-col gap-2">
      <div role="group" aria-label="정렬" className="flex gap-2">
        {(Object.keys(ORDER_LABELS) as FeedOrder[]).map((order) => (
          <Chip
            key={order}
            pressed={filter.order === order}
            onClick={() => onChange({ ...filter, order })}
          >
            {ORDER_LABELS[order]}
          </Chip>
        ))}
      </div>
      <details open={filtered} className="pb-2">
        <summary className="rounded-inline text-label text-fg-muted flex min-h-11 cursor-pointer list-none items-center gap-2 font-medium">
          <SlidersHorizontal size={18} aria-hidden />
          필터
          {filtered
            ? ` (${filter.jobRoles.length + filter.careerYears.length})`
            : ""}
        </summary>
        <div className="mt-2 flex flex-col gap-3">
          <ChipGroup label="직군">
            {JOB_ROLES.map((jobRole) => (
              <Chip
                key={jobRole}
                pressed={filter.jobRoles.includes(jobRole)}
                onClick={() =>
                  onChange({
                    ...filter,
                    jobRoles: toggleValue(filter.jobRoles, jobRole),
                  })
                }
              >
                {JOB_ROLE_LABELS[jobRole]}
              </Chip>
            ))}
          </ChipGroup>
          <ChipGroup label="경력">
            {CAREER_YEARS.map((careerYear) => (
              <Chip
                key={careerYear}
                pressed={filter.careerYears.includes(careerYear)}
                onClick={() =>
                  onChange({
                    ...filter,
                    careerYears: toggleValue(filter.careerYears, careerYear),
                  })
                }
              >
                {CAREER_YEAR_LABELS[careerYear]}
              </Chip>
            ))}
          </ChipGroup>
          {filtered ? (
            <Button
              variant="ghost"
              size="sm"
              className="self-start underline underline-offset-4"
              onClick={() =>
                onChange({ ...DEFAULT_FEED_FILTER, order: filter.order })
              }
            >
              필터 지우기
            </Button>
          ) : null}
        </div>
      </details>
    </div>
  );
}

function ChipGroup({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <span className="text-caption text-fg-subtle">{label}</span>
      {/* 칩의 누르는 영역이 위아래로 5px씩 넓어서, 줄 사이를 12px로 둬 겹치지 않게 한다. */}
      <div className="flex flex-wrap gap-x-2 gap-y-3">{children}</div>
    </div>
  );
}
```

- [ ] **Step 3: 목록의 면을 구분선으로 바꾼다**

`apps/web/src/widgets/feed-list/ui/feed-list.tsx`에서 아래를 바꾼다.

`SURFACE_CLASS_NAME` 상수와 그 위 주석을 지운다.

불러오는 중:

```tsx
  if (isPending) {
    return <ListSkeleton label="피드" />;
  }
```

오류와 빈 목록의 바깥 `<div className={`${SURFACE_CLASS_NAME} px-5 py-10 text-center`}>` 두 곳을 `<div className="py-10 text-center">`로 바꾼다.

오류 문구 `className="text-sm text-red-600"` 두 곳을 `className="text-body-sm text-danger"`로, 빈 목록 제목 `className="font-bold text-neutral-900"`을 `className="text-body text-fg font-bold"`로, 빈 목록 설명 `className="mt-1 text-sm text-neutral-600"`을 `className="text-body-sm text-fg-subtle mt-1"`로 바꾼다.

목록:

```tsx
      <ul className="border-line -mx-5 flex flex-col border-t">
        {items.map((item) => (
          <li key={item.postId} className="border-line border-b">
```

`-mx-5`는 글 항목의 hover 면을 본문 열의 좌우 여백(모바일 20px)까지 넓히기 위한 것이다. 본문 열의 여백이 20px 이상이라 넘치지 않는다.

- [ ] **Step 4: 홈 머리말을 새 토큰으로 바꾼다**

`apps/web/src/app/home/page.tsx`에서:

- `import { PencilLine } from "lucide-react";`를 `import { PencilSimpleLine } from "@phosphor-icons/react";`로 바꾸고, `<PencilLine size={16} aria-hidden />`을 `<PencilSimpleLine size={18} aria-hidden />`으로 바꾼다.
- `className="text-2xl font-bold text-neutral-900"` → `className="text-heading text-fg font-bold"`
- `className="mt-1 text-sm text-neutral-600"` → `className="text-body-sm text-fg-subtle mt-1"`
- `className="text-sm text-red-600"` → `className="text-body-sm text-danger"`

- [ ] **Step 5: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web typecheck && pnpm --filter web exec vitest run src/widgets/feed-list src/app/home`
Expected: PASS. 기존 US2-AC3, US2-AC4(정렬과 필터가 주소를 바꾼다)도 그대로 통과한다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
grep -cE "widgets/feed-list|app/home/page" apps/web/src/core/styles/legacy-style-files.json
```

Expected: `0`.

```bash
git add apps/web/src
git commit -m "feat(web): 피드의 정렬과 필터를 공용 칩으로, 목록을 구분선으로 바꾼다"
```

---

### Task 4: 앱 틀과 메뉴, 알림 종

**Files:**
- Modify: `apps/web/src/widgets/app-shell/ui/navigation-item.tsx` (전체 다시 쓰기)
- Modify: `apps/web/src/widgets/app-shell/ui/app-shell.tsx`
- Modify: `apps/web/src/widgets/app-shell/ui/app-shell.test.tsx`
- Modify: `apps/web/src/widgets/notification-bell/ui/notification-bell.tsx`

**Interfaces:**
- Produces: `NavigationItem`의 속성은 `icon`의 타입만 Phosphor의 `Icon`으로 바뀐다. `aria-current`, `aria-describedby`, `data-navigation-pending`, "이동 중" 안내는 그대로다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/widgets/app-shell/ui/app-shell.test.tsx`의 `describe("AppShell", ...)` 끝에 더한다.

```tsx
  it("DS-US3-AC7 선택된 메뉴는 채운 아이콘과 굵은 글씨, 나머지는 선 아이콘이다", () => {
    route.pathname = "/raid";
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const selected = nav.getByRole("link", { name: "함께 이겨내기" });
    const other = nav.getByRole("link", { name: "마음 이야기" });
    expect(selected.querySelector("svg")).toHaveAttribute(
      "data-icon-weight",
      "fill",
    );
    expect(other.querySelector("svg")).toHaveAttribute(
      "data-icon-weight",
      "regular",
    );
    expect(selected).toHaveClass("text-fg");
    expect(other).toHaveClass("text-fg-subtle");
  });

  it("GUEST-US1-AC2 잠긴 메뉴는 아이콘이 바뀐 뒤에도 설명으로 잠김을 알린다", () => {
    render(<AppShell authenticated={false}>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    expect(
      nav.getByRole("link", { name: "고민 쓰기" }),
    ).toHaveAccessibleDescription("로그인 후 이용할 수 있어요");
    expect(
      nav.getByRole("link", { name: "마음 이야기" }),
    ).not.toHaveAccessibleDescription();
  });

  it("이동 대기 중에는 아이콘 자리에 돌아가는 표시와 이동 중 안내가 나온다", () => {
    route.pending = true;
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    expect(
      nav.querySelectorAll('[data-navigation-pending="true"]').length,
    ).toBeGreaterThan(0);
    expect(within(nav).getAllByText("이동 중").length).toBeGreaterThan(0);
  });
```

Run: `pnpm --filter web exec vitest run src/widgets/app-shell`
Expected: FAIL. 첫 테스트가 `data-icon-weight`가 없어 실패한다. 나머지 둘은 지금 동작을 붙잡는 테스트라 통과한다.

- [ ] **Step 2: 메뉴 항목을 다시 쓴다**

`apps/web/src/widgets/app-shell/ui/navigation-item.tsx` 전체

```tsx
"use client";

import { CircleNotch, LockSimple, type Icon } from "@phosphor-icons/react";
import Link, { useLinkStatus } from "next/link";
import { useId, type MouseEventHandler } from "react";

import { cn } from "@/shared/lib";

export function NavigationItem({
  href,
  label,
  shortLabel,
  icon,
  current,
  mobile = false,
  locked = false,
  onClick,
  prefetch,
}: {
  href: string;
  label: string;
  shortLabel: string;
  icon: Icon;
  current?: "page" | "location";
  mobile?: boolean;
  /** 익명에게 로그인 안내를 먼저 여는 메뉴. 이름은 그대로 두고 설명으로만 알린다. */
  locked?: boolean;
  onClick?: MouseEventHandler<HTMLAnchorElement>;
  prefetch?: boolean;
}) {
  const lockedId = useId();
  return (
    <Link
      href={href}
      onClick={onClick}
      prefetch={prefetch}
      aria-label={label}
      aria-current={current}
      aria-describedby={locked ? lockedId : undefined}
      className={cn(
        "ogu-pressable group rounded-control relative",
        mobile ? "min-h-16 min-w-0 flex-1" : "min-h-12",
        current
          ? "text-fg"
          : "text-fg-subtle hover:bg-surface-sunken active:bg-line",
      )}
    >
      <NavigationContents
        label={label}
        shortLabel={shortLabel}
        icon={icon}
        selected={!!current}
        mobile={mobile}
        locked={locked}
      />
      {locked ? (
        <span id={lockedId} className="sr-only">
          로그인 후 이용할 수 있어요
        </span>
      ) : null}
    </Link>
  );
}

/** Next의 실제 이동 대기만 보여준다. prefetched/동일 경로는 가짜 로딩을 만들지 않는다. */
function NavigationContents({
  label,
  shortLabel,
  icon: NavIcon,
  selected,
  mobile,
  locked,
}: {
  label: string;
  shortLabel: string;
  icon: Icon;
  selected: boolean;
  mobile: boolean;
  locked: boolean;
}) {
  const { pending } = useLinkStatus();
  const weight = selected ? "fill" : "regular";
  return (
    <span
      data-navigation-pending={pending}
      className={cn(
        "relative flex h-full w-full",
        mobile
          ? "flex-col items-center justify-center py-1"
          : "items-center gap-3 px-3 py-2",
        selected && !mobile && "rounded-control bg-surface-sunken",
      )}
    >
      <span
        aria-busy={pending}
        className={cn(
          "flex items-center",
          mobile ? "flex-col gap-0.5" : "gap-3",
        )}
      >
        <span className="relative flex size-8 shrink-0 items-center justify-center">
          {/* 선택은 채운 아이콘으로, 이동 대기는 돌아가는 표시로 구분한다. */}
          <NavIcon
            aria-hidden
            size={24}
            weight={weight}
            data-icon-weight={weight}
            className={cn(pending && "opacity-0")}
          />
          {pending ? (
            <CircleNotch
              size={20}
              aria-hidden
              className="absolute motion-safe:animate-spin"
            />
          ) : null}
          {locked && !pending ? (
            <span
              aria-hidden
              className="bg-surface text-fg-subtle absolute -top-0.5 -right-1 flex size-4 items-center justify-center rounded-full"
            >
              <LockSimple size={11} weight="fill" />
            </span>
          ) : null}
        </span>
        <span
          className={cn(
            "relative whitespace-nowrap",
            mobile ? "text-caption" : "text-body-sm",
            selected ? "font-bold" : "font-medium",
          )}
        >
          {mobile ? shortLabel : label}
          {!mobile && pending ? (
            <span
              aria-hidden
              className="text-fg-subtle absolute top-full left-0 text-[10px] leading-3"
            >
              이동 중
            </span>
          ) : null}
        </span>
      </span>
      {mobile ? (
        <span aria-hidden className="text-fg-subtle h-3 text-[10px] leading-3">
          {pending ? "이동 중" : null}
        </span>
      ) : null}
      {/* busy 영역 밖에 두어 이동 시작 안내가 busy로 인해 읽히지 않는 일을 피한다. */}
      <span aria-live="polite" className="sr-only">
        {pending ? `${label} 이동 중` : ""}
      </span>
    </span>
  );
}
```

- [ ] **Step 3: 앱 틀을 새 아이콘과 토큰으로 바꾼다**

`apps/web/src/widgets/app-shell/ui/app-shell.tsx`에서:

import 한 줄을 바꾼다.

```tsx
import {
  House,
  PencilSimpleLine,
  Sword,
  User,
} from "@phosphor-icons/react";
```

`NAVIGATION`의 `icon` 값을 `Swords` → `Sword`, `PencilLine` → `PencilSimpleLine`, `UserRound` → `User`로 바꾼다(`House`는 그대로).

클래스를 아래처럼 바꾼다.

| 자리 | 옛 클래스 | 새 클래스 |
| --- | --- | --- |
| 본문 건너뛰기 | `text-brand-700 sr-only z-50 rounded-xl bg-white px-5 py-3 ...` | `text-fg rounded-control bg-surface sr-only z-50 px-5 py-3 ...` (뒤의 `focus:` 클래스는 그대로) |
| 헤더 | `border-b border-neutral-200/70` | `border-line border-b` |
| 헤더 배경 층 | `bg-white/95` | `bg-surface/95` |
| 로그인 링크 | `text-brand-700 inline-flex min-h-11 items-center text-sm font-bold` | `text-body-sm text-fg inline-flex min-h-11 items-center font-bold` |
| 소개 문구 | `hidden text-sm text-neutral-500 sm:block` | `text-body-sm text-fg-subtle hidden sm:block` |
| 모바일 메뉴 | `border-t border-neutral-200 bg-white ... shadow-[0_-4px_24px_rgba(39,35,51,0.05)] md:hidden` | `border-line bg-surface border-t ... md:hidden` (그림자를 지운다) |
| 푸터 | `pb-6 text-center text-xs text-neutral-500` | `text-caption text-fg-subtle pb-6 text-center` |

- [ ] **Step 4: 알림 종을 바꾼다**

`apps/web/src/widgets/notification-bell/ui/notification-bell.tsx`에서:

- `lucide-react`의 `Bell` import를 `import { Bell } from "@phosphor-icons/react";`로 바꾸고, `<Bell aria-hidden size={22} strokeWidth={1.8} />`을 `<Bell aria-hidden size={24} />`으로 바꾼다.
- 링크의 클래스를 `"text-fg hover:bg-surface-sunken relative inline-flex size-11 items-center justify-center rounded-full"`로 바꾼다(40px이던 영역이 44px가 되고, 초점 표시는 공통 규칙을 쓴다).
- 배지의 클래스를 `"bg-danger text-caption text-on-danger absolute -top-0.5 -right-0.5 min-w-5 rounded-full px-1 text-center leading-5 font-bold"`로 바꾼다.

- [ ] **Step 5: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web typecheck && pnpm --filter web exec vitest run src/widgets/app-shell src/widgets/notification-bell`
Expected: PASS. 기존 GUEST-US1-AC2, GUEST-US1-AC3, "이동 대기는 실제 현재 위치와 분리하고…"도 그대로 통과한다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
grep -cE "widgets/app-shell|widgets/notification-bell" apps/web/src/core/styles/legacy-style-files.json
```

Expected: `0`.

```bash
git add apps/web/src
git commit -m "feat(web): 메뉴 아이콘을 Phosphor로 바꾸고 선택된 항목을 채운 모양으로 그린다"
```

---

### Task 5: 브라우저 검증과 PR

**Files:**
- Modify: `apps/web/e2e/community-feed.spec.ts`

**Interfaces:**
- Consumes: Task 1~4의 화면.

- [ ] **Step 1: 실패하는 e2e를 쓴다**

`apps/web/e2e/community-feed.spec.ts`에서 화면 폭 목록(`{ width: 1440, height: 1000 }, { width: 390, height: 844 }`)에 `{ width: 320, height: 844 }`를 더한다.

같은 파일에서 `await expect(post.getByText("댓글 2", { exact: true })).toBeVisible();` 아래에 더한다.

```ts
    // DS-US3-AC1: 작성 시각은 상대 시각이거나 날짜다. 피드의 몬스터는 48px 자리다.
    await expect(post.locator("time")).toHaveText(
      /^(방금|\d+분 전|\d+시간 전|\d+일 전|\d{4}\.\d{2}\.\d{2})$/,
    );
    const thumb = await page
      .getByRole("region", { name: "피드", exact: true })
      .getByRole("img", { name: /몬스터, / })
      .first()
      .boundingBox();
    expect(thumb!.width).toBeGreaterThanOrEqual(40);
    expect(thumb!.width).toBeLessThanOrEqual(48);
```

이 파일의 가짜 피드 응답에 몬스터가 있는 글이 없으면(위쪽 `items`를 읽어 확인한다) `monster: { emotion: "ANXIETY", hp: 7, maxHp: 10, status: "ALIVE" }`인 글이 이미 있는지 본다. 기존 테스트가 `progressbar`의 `aria-valuenow`가 `7`인지 확인하므로 있어야 한다.

- [ ] **Step 2: 전체 e2e를 돌린다**

Run: `pnpm --filter web test:e2e`
Expected: 모두 통과. 3000번 포트에 옛 서버가 떠 있으면 끄고 돌린다.

실패하면 원인을 가른다.
- 옛 모양을 단정한 선택자(예: Lucide 아이콘의 클래스, `data-current-mark`)가 실패하면 그 선택자만 새 모양에 맞게 고친다. 동작 기대값은 바꾸지 않는다.
- 320px에서 넘치면 넘치는 요소를 찾아 화면을 고친다. 글 항목의 첫 줄은 `flex-wrap`이라 줄바꿈되어야 한다.

- [ ] **Step 3: 화면을 캡처해 눈으로 확인한다**

`community-feed.spec.ts`가 `testInfo.outputPath(...)`에 남기는 캡처(`apps/web/test-results/` 아래)를 390px과 1440px로 열어 본다. 캡처가 없으면 그 테스트의 넘침 검사 바로 뒤에 아래를 더하고 다시 돌린다.

```ts
    await page.screenshot({
      path: testInfo.outputPath(`feed-${viewport.width}.png`),
      fullPage: true,
    });
```

확인할 것: 글 항목의 감정 태그 색이 몬스터 색과 같은가, 하단 메뉴의 선택된 항목이 채운 아이콘인가, 흰 바탕에 묻히는 글자나 경계가 없는가.

- [ ] **Step 4: 전체 확인 후 PR을 연다**

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test && pnpm --filter web build`
Expected: 모두 통과.

```bash
git add apps/web/e2e
git commit -m "test(web): 피드의 상대 시각, 48px 몬스터, 320px 넘침을 확인한다"
git push -u origin design/feed-shell
gh pr create --base design/system-v2 --title "feat(web): 앱 틀과 피드를 새 디자인 시스템으로 옮긴다" --body "스펙 docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md의 3번 PR. 글 항목에 감정 태그, 48px 몬스터, 상대 시각을 넣고, 정렬과 필터를 공용 칩으로, 메뉴 아이콘을 Phosphor로 바꾼다. 기능과 라우트는 그대로다."
```

실제 API를 띄운 e2e(`e2e-full`)는 PR의 CI에서 돈다. 실패하면 로그에서 실패한 선택자를 찾아 같은 원칙(동작 기대값은 그대로, 찾는 방법만)으로 고친다.
