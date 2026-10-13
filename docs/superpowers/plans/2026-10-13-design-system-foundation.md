# 디자인 시스템 기반(토큰과 공용 컴포넌트) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 역할 토큰, Pretendard 글꼴, 글자 단계, 공용 컴포넌트, 몬스터 컴포넌트, 카탈로그 페이지를 만들어 화면 개편이 올라설 기반을 놓는다.

**Architecture:** `globals.css`에 원색 층(`:root` 변수)과 역할 층(`@theme inline`)을 두고, 옛 토큰 이름은 새 값에 이어 화면이 깨지지 않게 한다. 감정 색은 `data-emotion` 속성으로 바뀌는 CSS 변수다. 아직 안 옮긴 파일은 목록으로 관리하고, 목록이 줄기만 하도록 테스트가 지킨다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, class-variance-authority, tailwind-merge 3, Vitest 5, Playwright, `pretendard` 1.3.9, `@phosphor-icons/react` 2.1.10

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md`

이 계획은 스펙 "진행 순서"의 1번과 2번이다. 3~8번(화면)은 이 기반이 통합 브랜치에 들어간 뒤 화면 묶음마다 따로 계획을 쓴다.

## Global Constraints

- 모든 PR은 `design/system-v2`로 보낸다. **`main`으로 보내지 않는다.** main에 병합하면 운영에 바로 배포된다.
- `apps/api`, `contracts/`, `webbb-be/`, `webbb-fe/`는 건드리지 않는다.
- 새 의존성은 `pretendard`와 `@phosphor-icons/react` 둘뿐이다.
- 글꼴 굵기는 400(`font-normal`), 500(`font-medium`), 700(`font-bold`), 800(`font-extrabold`)만 쓴다.
- 누를 수 있는 요소의 영역은 44px 이상이다.
- 테마는 라이트 한 가지다.
- 커밋 메시지와 PR 제목은 Conventional Commits. 테스트 이름에 인수 조건 ID(`DS-US1-AC2`)를 넣는다.
- Next.js 16은 학습 자료와 다를 수 있다. 글꼴과 CSS를 고치기 전에 `apps/web/node_modules/next/dist/docs/`에서 해당 안내를 읽는다(`apps/web/AGENTS.md`).
- 모든 명령은 저장소 루트에서 실행한다.
- 작업 전후 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web test`

## Review Focus

1. `cn("text-body", "text-fg")`처럼 글자 크기와 글자 색을 함께 넘기면 tailwind-merge가 하나를 지울 수 있다. 둘 다 남아야 한다. → Task 2
2. HP가 최대치보다 크거나 음수이거나 최대치가 0이면 HP 막대의 너비가 0~100%를 벗어나거나 `NaN`이 된다. 0~100%로 잘려야 한다. → Task 7
3. 서버와 기기의 시계가 달라 작성 시각이 미래이거나 날짜 문자열이 깨져 있으면 "‑3분 전"이나 "Invalid Date"가 보인다. 미래는 "방금", 깨진 값은 빈 문자열이어야 한다. → Task 6
4. `data-emotion`이 없거나 계약에 없는 값이면 감정 색이 비어 글자가 안 보일 수 있다. 회색 기본값으로 읽혀야 한다. → Task 1
5. 320px 화면에서 긴 한국어 버튼 문구가 줄바꿈되지 않으면 화면이 가로로 넘친다. 넘치지 않아야 한다. → Task 8

---

### Task 1: 통합 브랜치와 색 토큰

**Files:**
- Modify: `apps/web/src/core/styles/globals.css`
- Create: `apps/web/src/core/styles/tokens.test.ts`
- Modify: `apps/web/e2e/design-system.spec.ts:38`

**Interfaces:**
- Produces: Tailwind 색 유틸리티 이름 `page`, `surface`, `surface-sunken`, `line`, `line-strong`, `fg`, `fg-muted`, `fg-subtle`, `action`, `action-hover`, `on-action`, `focus`, `danger`, `danger-tint`, `on-danger`, `warning`, `warning-tint`, `emotion`, `emotion-tint`, `emotion-fg`, `game`, `game-line`, `scrim`. 그림자 `shadow-game`. 모서리 `rounded-inline`(8px), `rounded-control`(14px), `rounded-surface`(20px). 감정 범위는 `data-emotion="ANXIETY|LETHARGY|LONELINESS|SELF_DEPRECATION|IRRITATION"`.

- [ ] **Step 1: 브랜치를 만든다**

지금 작업 트리에는 커밋하지 않은 스펙과 이 계획 파일이 있다. 그대로 들고 간다.

```bash
git fetch origin
git switch -c design/system-v2 origin/main
git push -u origin design/system-v2
git switch -c design/tokens
pnpm install
pnpm --filter web exec playwright install chromium
git add docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md docs/superpowers/plans/2026-10-13-design-system-foundation.md
git commit -m "docs: 디자인 시스템 개편 설계와 기반 구현 계획"
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/web/src/core/styles/tokens.test.ts`

```ts
import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

const css = readFileSync(path.resolve(__dirname, "globals.css"), "utf8");

const EMOTIONS = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
] as const;

function primitive(name: string): string {
  const match = css.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`));
  if (!match) throw new Error(`원색 토큰 --${name}의 값을 찾지 못했다`);
  return match[1];
}

function role(name: string): string {
  const match = css.match(
    new RegExp(`--color-${name}:\\s*var\\(--([a-z0-9-]+)\\)\\s*;`),
  );
  if (!match) throw new Error(`역할 토큰 --color-${name}을 찾지 못했다`);
  return primitive(match[1]);
}

function emotion(type: string) {
  const block = css.match(
    new RegExp(`\\[data-emotion="${type}"\\]\\s*\\{([^}]*)\\}`),
  );
  if (!block) throw new Error(`감정 ${type}의 색 묶음을 찾지 못했다`);
  const read = (name: string) => {
    const match = block[1].match(
      new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\s*;`),
    );
    if (!match) throw new Error(`감정 ${type}에 --${name}이 없다`);
    return match[1];
  };
  return {
    solid: read("emotion"),
    tint: read("emotion-tint"),
    fg: read("emotion-fg"),
  };
}

function luminance(hex: string): number {
  const channel = (offset: number) => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

function contrast(a: string, b: string): number {
  const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (high + 0.05) / (low + 0.05);
}

const TEXT_PAIRS: [fg: string, bg: string][] = [
  ["fg", "page"],
  ["fg", "surface"],
  ["fg", "surface-sunken"],
  ["fg-muted", "surface"],
  ["fg-muted", "surface-sunken"],
  ["fg-subtle", "surface"],
  ["fg-subtle", "surface-sunken"],
  ["on-action", "action"],
  ["on-action", "action-hover"],
  ["danger", "surface"],
  ["danger", "danger-tint"],
  ["on-danger", "danger"],
  ["warning", "warning-tint"],
  ["fg", "game"],
];

describe("색 토큰", () => {
  it.each(TEXT_PAIRS)(
    "DS-US1-AC2 %s 글자는 %s 바탕에서 4.5:1 이상이다",
    (fg, bg) => {
      expect(contrast(role(fg), role(bg))).toBeGreaterThanOrEqual(4.5);
    },
  );

  it("DS-US1-AC2 입력 테두리는 바탕과 3:1 이상이다", () => {
    expect(
      contrast(role("line-strong"), role("surface")),
    ).toBeGreaterThanOrEqual(3);
  });

  it.each(EMOTIONS)(
    "DS-US1-AC2 %s의 글자 색은 옅은 색 바탕에서 4.5:1 이상이다",
    (type) => {
      const { fg, tint } = emotion(type);
      expect(contrast(fg, tint)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it("DS-US1-AC3 감정이 없는 곳에서는 회색 기본값을 쓴다", () => {
    expect(css).toMatch(/--emotion:\s*var\(--ink-600\)/);
    expect(css).toMatch(/--emotion-tint:\s*var\(--ink-100\)/);
    expect(css).toMatch(/--emotion-fg:\s*var\(--ink-700\)/);
    expect(
      contrast(primitive("ink-700"), primitive("ink-100")),
    ).toBeGreaterThanOrEqual(4.5);
  });

  it("DS-US1-AC1 원색 층은 Tailwind 유틸리티로 드러나지 않는다", () => {
    expect(css).not.toMatch(/--color-ink-\d+/);
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `pnpm --filter web exec vitest run src/core/styles/tokens.test.ts`
Expected: FAIL. `역할 토큰 --color-fg을 찾지 못했다` 같은 오류가 난다.

- [ ] **Step 4: 토큰을 쓴다**

`apps/web/src/core/styles/globals.css`의 첫 줄(`@import "tailwindcss";`)부터 `::selection { ... }` 블록 끝까지를 아래로 바꾼다. 그 아래의 `.ogu-pressable`, 움직임 줄이기, `monster-idle-*`, `@keyframes`는 그대로 둔다.

```css
@import "tailwindcss";

/* 원색 층. 화면에서 직접 쓰지 않는다. 유틸리티가 생기지 않게 @theme 밖에 둔다. */
:root {
  --ink-900: #17171c;
  --ink-800: #2a2a32;
  --ink-700: #45454f;
  --ink-600: #6b6b76;
  --ink-500: #8a8a95;
  --ink-300: #dcdce2;
  --ink-200: #ececf0;
  --ink-100: #f5f5f7;
  --white: #ffffff;
  --game-yellow: #ffd60a;
  --danger-700: #b42318;
  --danger-50: #fef3f2;
  --warning-700: #b54708;
  --warning-50: #fffaeb;

  /* 감정이 정해지지 않은 곳의 기본값. */
  --emotion: var(--ink-600);
  --emotion-tint: var(--ink-100);
  --emotion-fg: var(--ink-700);

  color-scheme: light;
}

/* 감정 색. 몬스터 그림에서 뽑았다. 이 속성이 붙은 요소 안에서 emotion 유틸리티가 이 값이 된다. */
[data-emotion="ANXIETY"] {
  --emotion: #8b5cf6;
  --emotion-tint: #f1ebff;
  --emotion-fg: #5334b8;
}
[data-emotion="LETHARGY"] {
  --emotion: #7187e0;
  --emotion-tint: #e9edfc;
  --emotion-fg: #3346a3;
}
[data-emotion="LONELINESS"] {
  --emotion: #35b6d3;
  --emotion-tint: #e0f5fa;
  --emotion-fg: #0d6379;
}
[data-emotion="SELF_DEPRECATION"] {
  --emotion: #8a9a3c;
  --emotion-tint: #f0f3de;
  --emotion-fg: #56621d;
}
[data-emotion="IRRITATION"] {
  --emotion: #e5484d;
  --emotion-tint: #fdeaea;
  --emotion-fg: #a81f26;
}

/* 역할 층. 화면과 컴포넌트는 이 이름만 쓴다. */
@theme inline {
  --color-page: var(--white);
  --color-surface: var(--white);
  --color-surface-sunken: var(--ink-100);
  --color-line: var(--ink-200);
  --color-line-strong: var(--ink-500);
  --color-fg: var(--ink-900);
  --color-fg-muted: var(--ink-700);
  --color-fg-subtle: var(--ink-600);
  --color-action: var(--ink-900);
  --color-action-hover: var(--ink-800);
  --color-on-action: var(--white);
  --color-focus: var(--ink-900);
  --color-danger: var(--danger-700);
  --color-danger-tint: var(--danger-50);
  --color-on-danger: var(--white);
  --color-warning: var(--warning-700);
  --color-warning-tint: var(--warning-50);
  --color-emotion: var(--emotion);
  --color-emotion-tint: var(--emotion-tint);
  --color-emotion-fg: var(--emotion-fg);
  --color-game: var(--game-yellow);
  --color-game-line: var(--ink-900);
  --color-scrim: rgb(23 23 28 / 0.4);

  --radius-inline: 8px;
  --radius-control: 14px;
  --radius-surface: 20px;
  --shadow-game: 0 4px 0 var(--ink-900);

  /*
   * 옛 이름. 화면을 옮기는 동안만 새 값에 이어 둔다. 화면 개편의 마지막 PR에서 지운다.
   * 새 코드에서 쓰면 token-usage.test.ts가 막는다.
   */
  --color-background: var(--white);
  --color-foreground: var(--ink-900);
  --color-brand-50: var(--ink-100);
  --color-brand-100: var(--ink-100);
  --color-brand-200: var(--ink-300);
  --color-brand-500: var(--ink-700);
  --color-brand-600: var(--ink-900);
  --color-brand-650: var(--ink-800);
  --color-brand-700: var(--ink-900);
  --color-leaf-50: #edf5e9;
  --color-leaf-500: #78a86a;
  --color-leaf-700: #426436;
  --color-neutral-50: var(--ink-100);
  --color-neutral-100: var(--ink-100);
  --color-neutral-200: var(--ink-200);
  --color-neutral-300: var(--ink-500);
  --color-neutral-400: var(--ink-500);
  --color-neutral-500: var(--ink-600);
  --color-neutral-600: var(--ink-600);
  --color-neutral-700: var(--ink-700);
  --color-neutral-800: var(--ink-800);
  --color-neutral-900: var(--ink-900);
  --color-neutral-950: var(--ink-900);
  --shadow-surface: 0 0 #0000;
}

@theme {
  --font-sans: var(--font-ogu-sans);
}

body {
  background: var(--white);
  color: var(--ink-900);
  font-family: var(--font-ogu-sans), sans-serif;
  line-height: 1.65;
  letter-spacing: -0.02em;
}

::selection {
  background: var(--ink-200);
  color: var(--ink-900);
}
```

같은 파일에서 초점 규칙을 아래로 바꾼다(`a:focus-visible, button:focus-visible, select:focus-visible { ... }` 블록).

```css
/* Every interactive control shares a visible keyboard focus treatment. */
:where(a, button, select, input, textarea, [tabindex]):focus-visible {
  outline: 2px solid var(--ink-900);
  outline-offset: 2px;
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `pnpm --filter web exec vitest run src/core/styles/tokens.test.ts`
Expected: PASS (22개).

- [ ] **Step 6: e2e 기대값을 고친다**

`apps/web/e2e/design-system.spec.ts`에서 아래 한 줄을 바꾼다.

```ts
    expect(layout.background).toBe("rgb(255, 255, 255)");
```

- [ ] **Step 7: 전체 확인 후 커밋한다**

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web test && pnpm --filter web test:e2e`
Expected: 모두 통과. 실패하면 색 값을 단정한 테스트가 더 있는 것이니 새 값으로 고친다.

```bash
git add apps/web/src/core/styles apps/web/e2e/design-system.spec.ts
git commit -m "feat(web): 역할 토큰과 감정 색을 정의한다"
```

---

### Task 2: Pretendard와 글자 단계

**Files:**
- Modify: `apps/web/package.json` (의존성)
- Modify: `apps/web/src/app/layout.tsx`
- Modify: `apps/web/src/core/styles/globals.css`
- Modify: `apps/web/src/shared/lib/cn.ts`
- Modify: `apps/web/src/shared/lib/cn.test.ts`
- Modify: `apps/web/e2e/design-system.spec.ts:39`
- Delete: `apps/web/src/shared/ui/fonts/` (세 파일)

**Interfaces:**
- Produces: 글자 크기 유틸리티 `text-caption`(12/16), `text-label`(13/18), `text-body-sm`(15/24), `text-body`(16/26), `text-body-lg`(17/29), `text-title`(20/28), `text-heading`(24/32), `text-display`(32/40), `text-numeric`(36/40). `cn()`은 이 이름들을 글자 크기로 안다.

- [ ] **Step 1: Next.js 안내를 읽는다**

Run: `ls apps/web/node_modules/next/dist/docs/` 를 보고 글꼴과 전역 CSS 안내 문서를 읽는다. 루트 레이아웃에서 `node_modules`의 CSS를 가져오는 방식이 아래와 다르면 문서를 따른다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/web/src/shared/lib/cn.test.ts`의 `describe("cn", ...)` 안에 추가한다.

```ts
  it("DS-US1-AC4 글자 크기와 글자 색을 함께 주면 둘 다 남는다", () => {
    expect(cn("text-body", "text-fg")).toBe("text-body text-fg");
    expect(cn("text-fg", "text-caption")).toBe("text-fg text-caption");
  });

  it("DS-US1-AC4 글자 크기끼리는 뒤의 것이 이긴다", () => {
    expect(cn("text-body", "text-title")).toBe("text-title");
  });

  it("모서리 이름끼리는 뒤의 것이 이긴다", () => {
    expect(cn("rounded-control", "rounded-surface")).toBe("rounded-surface");
  });
```

- [ ] **Step 3: 실패를 확인한다**

Run: `pnpm --filter web exec vitest run src/shared/lib/cn.test.ts`
Expected: FAIL. 새 테스트 가운데 하나 이상이 실패한다.

- [ ] **Step 4: `cn`이 새 이름을 알게 한다**

`apps/web/src/shared/lib/cn.ts` 전체를 아래로 바꾼다.

```ts
import { type ClassValue, clsx } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/** globals.css의 글자 단계와 모서리 이름. tailwind-merge가 글자 색이나 다른 유틸리티와 헷갈리지 않게 알려 준다. */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      "font-size": [
        {
          text: [
            "caption",
            "label",
            "body-sm",
            "body",
            "body-lg",
            "title",
            "heading",
            "display",
            "numeric",
          ],
        },
      ],
      rounded: [{ rounded: ["inline", "control", "surface"] }],
    },
  },
});

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

Run: `pnpm --filter web exec vitest run src/shared/lib/cn.test.ts`
Expected: PASS.

- [ ] **Step 5: 글꼴을 바꾼다**

```bash
pnpm --filter web add pretendard@1.3.9
git rm -r apps/web/src/shared/ui/fonts
```

`apps/web/src/app/layout.tsx`에서 `import localFont from "next/font/local";`과 `const oguSans = localFont({ ... });` 블록 전체를 지우고, `import "@/core/styles/globals.css";` 바로 위에 아래 줄을 넣는다.

```ts
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
```

같은 파일의 `<html>` 줄을 아래로 바꾼다.

```tsx
    <html lang="ko" className="h-full antialiased">
```

`apps/web/src/core/styles/globals.css`에서 `@theme { --font-sans: var(--font-ogu-sans); }` 블록을 아래로 바꾼다.

```css
@theme {
  --font-sans:
    "Pretendard Variable", Pretendard, -apple-system, BlinkMacSystemFont,
    system-ui, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif;

  --text-caption: 0.75rem;
  --text-caption--line-height: 1rem;
  --text-label: 0.8125rem;
  --text-label--line-height: 1.125rem;
  --text-body-sm: 0.9375rem;
  --text-body-sm--line-height: 1.5rem;
  --text-body: 1rem;
  --text-body--line-height: 1.625rem;
  --text-body-lg: 1.0625rem;
  --text-body-lg--line-height: 1.8125rem;
  --text-title: 1.25rem;
  --text-title--line-height: 1.75rem;
  --text-heading: 1.5rem;
  --text-heading--line-height: 2rem;
  --text-display: 2rem;
  --text-display--line-height: 2.5rem;
  --text-numeric: 2.25rem;
  --text-numeric--line-height: 2.5rem;
}
```

같은 파일의 `body` 블록을 아래로 바꾼다.

```css
body {
  background: var(--white);
  color: var(--ink-900);
  font-family: var(--font-sans);
  line-height: 1.65;
  letter-spacing: -0.01em;
}
```

- [ ] **Step 6: 남은 참조를 찾는다**

Run: `grep -rn "oguSans\|font-ogu-sans\|SpoqaHanSans" apps/web/src apps/web/e2e apps/web/e2e-full apps/web/docs`
Expected: `apps/web/e2e/design-system.spec.ts`의 한 줄과 `apps/web/docs/DESIGN_SYSTEM.md`만 나온다. 문서는 Task 9에서 다시 쓴다. e2e는 아래로 고친다.

```ts
    expect(layout.font).toContain("Pretendard");
```

- [ ] **Step 7: 전체 확인 후 커밋한다**

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web test && pnpm --filter web build && pnpm --filter web test:e2e`
Expected: 모두 통과. 빌드 출력에 `PretendardVariable.subset.*.woff2`가 정적 자산으로 들어간다.

```bash
git add apps/web/package.json pnpm-lock.yaml apps/web/src apps/web/e2e/design-system.spec.ts
git commit -m "feat(web): Pretendard와 글자 단계를 들인다"
```

---

### Task 3: 옛 색 이름이 다시 늘지 않게 막는다

**Files:**
- Create: `apps/web/src/core/styles/token-usage.test.ts`
- Create: `apps/web/src/core/styles/legacy-style-files.json`

**Interfaces:**
- Produces: `UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`로 목록을 다시 만든다. 파일을 새 토큰으로 옮긴 작업은 이 명령으로 목록에서 그 파일을 빼야 테스트가 통과한다.

- [ ] **Step 1: 테스트를 쓴다**

`apps/web/src/core/styles/token-usage.test.ts`

```ts
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

const SRC = path.resolve(__dirname, "../..");
const BASELINE = path.resolve(__dirname, "legacy-style-files.json");

/** 옛 토큰 이름, Tailwind 기본 색, 쓰지 않기로 한 굵기와 그림자. */
const LEGACY = new RegExp(
  [
    String.raw`\b(?:bg|text|border|ring|outline|fill|stroke|divide|placeholder|decoration|accent|caret|from|via|to)-(?:brand|neutral|leaf|red|amber|emerald|blue|yellow|orange|rose|pink|green|gray|slate|zinc|white|black)(?:-\d{2,3})?\b`,
    String.raw`\bfont-(?:thin|extralight|light|semibold|black)\b`,
    String.raw`\bshadow-(?:sm|md|lg|xl|2xl|surface)\b`,
  ].join("|"),
);

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    if (!/\.tsx?$/.test(entry.name)) return [];
    if (/\.test\.tsx?$/.test(entry.name)) return [];
    if (full.endsWith(path.join("shared", "api", "generated.ts"))) return [];
    return [full];
  });
}

const offenders = sourceFiles(SRC)
  .filter((file) => LEGACY.test(readFileSync(file, "utf8")))
  .map((file) => path.relative(SRC, file).split(path.sep).join("/"))
  .sort();

if (process.env.UPDATE_LEGACY_BASELINE) {
  writeFileSync(BASELINE, `${JSON.stringify(offenders, null, 2)}\n`);
}

const baseline: string[] = JSON.parse(readFileSync(BASELINE, "utf8"));

describe("옛 색 이름", () => {
  it("DS-US1-AC1 목록에 없는 파일은 옛 색 이름을 쓰지 않는다", () => {
    expect(offenders.filter((file) => !baseline.includes(file))).toEqual([]);
  });

  it("DS-US1-AC1 새 토큰으로 옮긴 파일은 목록에서 뺀다", () => {
    expect(baseline.filter((file) => !offenders.includes(file))).toEqual([]);
  });
});
```

- [ ] **Step 2: 실패를 확인한다**

Run: `pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`
Expected: FAIL. `legacy-style-files.json`이 없어 `ENOENT`가 난다.

- [ ] **Step 3: 목록을 만든다**

Run: `UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`
Expected: PASS. `legacy-style-files.json`에 파일 60개 안팎이 적힌다. `git diff --stat`으로 목록 파일만 새로 생겼는지 본다.

- [ ] **Step 4: 막는지 확인한다**

`apps/web/src/shared/ui/spinner.tsx`에 임시로 `text-red-600`을 넣고 테스트를 돌린다.

Run: `pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts`
Expected: FAIL. 첫 번째 테스트가 `shared/ui/spinner.tsx`를 보여 준다. 확인한 뒤 `git checkout apps/web/src/shared/ui/spinner.tsx`로 되돌린다.

- [ ] **Step 5: 커밋하고 첫 PR을 연다**

```bash
git add apps/web/src/core/styles
git commit -m "test(web): 옛 색 이름이 새 파일에 들어오지 않게 막는다"
git push -u origin design/tokens
gh pr create --base design/system-v2 --title "feat(web): 디자인 토큰과 Pretendard" --body "스펙 docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md의 1번 PR. 역할 토큰과 감정 색, Pretendard, 글자 단계를 들이고 옛 색 이름이 늘지 않게 막는다. 화면은 옛 이름을 새 값에 이어 두어 그대로 동작한다. 로그인, 피드 화면을 390px과 1440px로 캡처해 붙인다."
```

PR의 CI(`ci-ok`)가 통과하면 사용자에게 병합을 요청하고, 병합된 뒤 다음 Task로 간다.

---

### Task 4: Button, GameButton, IconButton

**Files:**
- Modify: `apps/web/src/shared/ui/button.tsx`
- Create: `apps/web/src/shared/ui/button.test.tsx`
- Create: `apps/web/src/shared/ui/game-button.tsx`
- Create: `apps/web/src/shared/ui/game-button.test.tsx`
- Create: `apps/web/src/shared/ui/icon-button.tsx`
- Create: `apps/web/src/shared/ui/icon-button.test.tsx`
- Modify: `apps/web/src/shared/ui/confirm-dialog.tsx` (`confirmVariant` 이름)
- Modify: `apps/web/src/shared/ui/index.ts`
- Modify: `Button`을 쓰는 화면 파일(이름 바꾸기만)

**Interfaces:**
- Produces:
  - `Button` — `variant?: "primary" | "secondary" | "subtle" | "ghost" | "danger"`, `size?: "sm" | "md" | "lg"`, `asChild?: boolean`, 그 밖은 `<button>` 속성.
  - `GameButton` — `<button>` 속성 그대로. 기본 `type="button"`.
  - `IconButton` — `<button>` 속성에 `"aria-label": string`이 필수.
  - `ConfirmDialog`의 `confirmVariant?: "primary" | "danger"` (기본 `"danger"`).

- [ ] **Step 1: 작업 브랜치를 만든다**

```bash
git fetch origin
git switch -c design/components origin/design/system-v2
pnpm install
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`apps/web/src/shared/ui/button.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Button } from "./button";

describe("Button", () => {
  it("DS-US2-AC1 기본은 먹색 주요 버튼이다", () => {
    render(<Button>올리기</Button>);
    expect(screen.getByRole("button", { name: "올리기" })).toHaveClass(
      "bg-action",
      "text-on-action",
      "min-h-11",
    );
  });

  it.each([
    ["secondary", "border-line-strong"],
    ["subtle", "bg-surface-sunken"],
    ["ghost", "text-fg-muted"],
    ["danger", "bg-danger"],
  ] as const)("DS-US2-AC1 %s 변형은 %s를 쓴다", (variant, className) => {
    render(<Button variant={variant}>확인</Button>);
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(className);
  });

  it("DS-US2-AC2 큰 버튼은 52px다", () => {
    render(<Button size="lg">가입하기</Button>);
    expect(screen.getByRole("button", { name: "가입하기" })).toHaveClass(
      "min-h-13",
    );
  });

  it("DS-US2-AC1 asChild면 링크가 버튼 모양을 입는다", () => {
    render(
      <Button asChild>
        <a href="/write">고민 쓰기</a>
      </Button>,
    );
    expect(screen.getByRole("link", { name: "고민 쓰기" })).toHaveClass(
      "bg-action",
    );
  });

  it("긴 문구가 줄바꿈될 수 있다", () => {
    render(<Button>로그인하고 이야기 보기</Button>);
    expect(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    ).not.toHaveClass("whitespace-nowrap");
  });
});
```

`apps/web/src/shared/ui/game-button.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { GameButton } from "./game-button";

describe("GameButton", () => {
  it("DS-US3-AC2 노란 바탕에 외곽선과 단색 그림자가 있다", () => {
    render(<GameButton>공격하기</GameButton>);
    const button = screen.getByRole("button", { name: "공격하기" });
    expect(button).toHaveClass(
      "bg-game",
      "border-game-line",
      "shadow-game",
      "min-h-14",
    );
    expect(button).toHaveAttribute("type", "button");
  });

  it("DS-US4-AC3 움직임 줄이기에서는 눌러도 내려가지 않는다", () => {
    render(<GameButton>공격하기</GameButton>);
    expect(screen.getByRole("button", { name: "공격하기" })).toHaveClass(
      "motion-reduce:active:translate-y-0",
    );
  });

  it("잠기면 그림자가 사라진다", () => {
    render(<GameButton disabled>공격하기</GameButton>);
    const button = screen.getByRole("button", { name: "공격하기" });
    expect(button).toBeDisabled();
    expect(button).toHaveClass("disabled:shadow-none");
  });
});
```

`apps/web/src/shared/ui/icon-button.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { IconButton } from "./icon-button";

describe("IconButton", () => {
  it("DS-US2-AC2 이름이 있고 44px 영역이다", () => {
    render(
      <IconButton aria-label="알림">
        <svg aria-hidden />
      </IconButton>,
    );
    const button = screen.getByRole("button", { name: "알림" });
    expect(button).toHaveClass("size-11");
    expect(button).toHaveAttribute("type", "button");
  });
});
```

- [ ] **Step 3: 실패를 확인한다**

Run: `pnpm --filter web exec vitest run src/shared/ui/button.test.tsx src/shared/ui/game-button.test.tsx src/shared/ui/icon-button.test.tsx`
Expected: FAIL. `game-button`과 `icon-button`을 찾지 못하고, Button 테스트는 `bg-action`이 없어 실패한다.

- [ ] **Step 4: 컴포넌트를 쓴다**

`apps/web/src/shared/ui/button.tsx` 전체

```tsx
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

const buttonVariants = cva(
  "ogu-pressable rounded-control text-body-sm disabled:bg-surface-sunken disabled:text-fg-subtle inline-flex items-center justify-center gap-2 text-center font-bold disabled:cursor-not-allowed disabled:border-transparent aria-disabled:cursor-not-allowed aria-disabled:opacity-60",
  {
    variants: {
      variant: {
        primary: "bg-action text-on-action hover:bg-action-hover",
        secondary:
          "border-line-strong bg-surface text-fg hover:bg-surface-sunken border",
        subtle: "bg-surface-sunken text-fg hover:bg-line",
        ghost: "text-fg-muted hover:bg-surface-sunken",
        danger: "bg-danger text-on-danger hover:opacity-90",
      },
      size: {
        sm: "min-h-11 px-3",
        md: "min-h-11 px-4",
        lg: "text-body min-h-13 px-6",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "md",
    },
  },
);

interface ButtonProps
  extends ComponentProps<"button">, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({
  className,
  variant,
  size,
  asChild,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot : "button";
  return (
    <Comp
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  );
}
```

`apps/web/src/shared/ui/game-button.tsx`

```tsx
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 글 상세의 "공감 보내기"와 레이드의 "공격하기"에만 쓴다(스펙 "게임 요소를 쓰는 곳").
 * 누르면 그림자만큼 내려간다. 움직임 줄이기가 켜져 있으면 내려가지 않는다.
 */
export function GameButton({
  className,
  type = "button",
  ...props
}: ComponentProps<"button">) {
  return (
    <button
      type={type}
      className={cn(
        "rounded-surface border-game-line bg-game text-title text-fg shadow-game disabled:border-line-strong disabled:bg-surface-sunken disabled:text-fg-subtle inline-flex min-h-14 w-full items-center justify-center gap-2 border-2 px-6 text-center font-extrabold transition-[transform,box-shadow] duration-[120ms] active:translate-y-1 active:shadow-none disabled:cursor-not-allowed disabled:shadow-none disabled:active:translate-y-0 motion-reduce:transition-none motion-reduce:active:translate-y-0",
        className,
      )}
      {...props}
    />
  );
}
```

`apps/web/src/shared/ui/icon-button.tsx`

```tsx
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

type IconButtonProps = Omit<ComponentProps<"button">, "aria-label"> & {
  /** 아이콘만 있는 버튼은 화면 읽기 도구가 읽을 이름이 꼭 있어야 한다. */
  "aria-label": string;
};

export function IconButton({
  className,
  type = "button",
  ...props
}: IconButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        "ogu-pressable rounded-control text-fg hover:bg-surface-sunken disabled:text-fg-subtle inline-flex size-11 shrink-0 items-center justify-center disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
```

`apps/web/src/shared/ui/index.ts`의 `export { Button } from "./button";` 아래에 추가한다.

```ts
export { GameButton } from "./game-button";
export { IconButton } from "./icon-button";
```

- [ ] **Step 5: 옛 변형 이름을 바꾼다**

순서가 중요하다. `secondary`를 먼저 `subtle`로 옮긴 뒤 `outline`을 `secondary`로 옮긴다.

```bash
cd apps/web
find src -name '*.tsx' -exec perl -pi -e 's/variant="secondary"/variant="subtle"/g' {} +
find src -name '*.tsx' -exec perl -pi -e 's/variant="outline"/variant="secondary"/g' {} +
find src -name '*.tsx' -exec perl -pi -e 's/"destructive"/"danger"/g' {} +
cd ../..
```

Run: `pnpm --filter web typecheck`
Expected: 통과. `Button`이 아닌 컴포넌트의 `variant`가 잘못 바뀌었다면 여기서 오류가 난다. 그 줄만 원래 값으로 되돌린다.

- [ ] **Step 6: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web test && pnpm --filter web lint`
Expected: 모두 통과.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
git add apps/web/src
git commit -m "feat(web): Button을 역할 토큰으로 옮기고 GameButton과 IconButton을 더한다"
```

---

### Task 5: Chip, TextField, Textarea와 나머지 공용 컴포넌트

**Files:**
- Create: `apps/web/src/shared/ui/chip.tsx`, `chip.test.tsx`
- Create: `apps/web/src/shared/ui/text-field.tsx`, `text-field.test.tsx`
- Create: `apps/web/src/shared/ui/textarea.tsx`
- Modify: `apps/web/src/shared/ui/input.tsx`, `label.tsx`, `card.tsx`, `list-skeleton.tsx`, `selection-indicator.tsx`, `brand-mark.tsx`, `toast.tsx`, `confirm-dialog.tsx`
- Modify: `apps/web/src/shared/ui/index.ts`

**Interfaces:**
- Consumes: Task 1의 역할 토큰, Task 2의 글자 단계.
- Produces:
  - `Chip` — `<button>` 속성에 `pressed: boolean` 필수. `aria-pressed`로 상태를 전한다.
  - `TextField` — `<input>` 속성에 `label: string` 필수, `hint?: string`, `error?: string | null`.
  - `Textarea` — `<textarea>` 속성 그대로.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/shared/ui/chip.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Chip } from "./chip";

describe("Chip", () => {
  it("DS-US2-AC1 선택 상태를 aria-pressed로 전한다", () => {
    render(
      <>
        <Chip pressed>전체</Chip>
        <Chip pressed={false}>개발</Chip>
      </>,
    );
    expect(screen.getByRole("button", { name: "전체" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "개발" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("DS-US2-AC2 보이는 높이는 36px이고 누르는 영역은 위아래로 넓다", () => {
    render(<Chip pressed={false}>디자인</Chip>);
    expect(screen.getByRole("button", { name: "디자인" })).toHaveClass(
      "h-9",
      "before:-inset-y-1",
    );
  });
});
```

`apps/web/src/shared/ui/text-field.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { TextField } from "./text-field";

describe("TextField", () => {
  it("DS-US2-AC1 이름표가 입력에 이어진다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).toHaveClass("h-13");
  });

  it("도움말과 오류를 입력의 설명으로 잇는다", () => {
    render(
      <TextField
        label="닉네임"
        hint="2~10자"
        error="이미 쓰는 닉네임이에요"
      />,
    );
    const input = screen.getByLabelText("닉네임");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("2~10자 이미 쓰는 닉네임이에요");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "이미 쓰는 닉네임이에요",
    );
  });

  it("오류가 없으면 aria-invalid를 붙이지 않는다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).not.toHaveAttribute("aria-invalid");
  });
});
```

Run: `pnpm --filter web exec vitest run src/shared/ui/chip.test.tsx src/shared/ui/text-field.test.tsx`
Expected: FAIL. 두 모듈을 찾지 못한다.

- [ ] **Step 2: 새 컴포넌트를 쓴다**

`apps/web/src/shared/ui/chip.tsx`

```tsx
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 필터와 선택에 쓰는 알약 모양 버튼. 보이는 높이는 36px이고,
 * `::before`로 위아래 4px씩 넓혀 누르는 영역을 44px로 맞춘다.
 */
export function Chip({
  pressed,
  className,
  type = "button",
  ...props
}: ComponentProps<"button"> & { pressed: boolean }) {
  return (
    <button
      type={type}
      aria-pressed={pressed}
      className={cn(
        "ogu-pressable border-line-strong bg-surface text-body-sm text-fg-muted aria-pressed:border-action aria-pressed:bg-action aria-pressed:text-on-action relative inline-flex h-9 shrink-0 items-center rounded-full border px-3.5 font-medium before:absolute before:inset-x-0 before:-inset-y-1 before:content-[''] aria-pressed:font-bold",
        className,
      )}
      {...props}
    />
  );
}
```

`apps/web/src/shared/ui/textarea.tsx`

```tsx
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      className={cn(
        "rounded-control border-line-strong bg-surface text-body text-fg placeholder:text-fg-subtle hover:border-fg-muted disabled:bg-surface-sunken disabled:text-fg-subtle aria-invalid:border-danger aria-invalid:bg-danger-tint min-h-32 w-full border px-4 py-3 disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
```

`apps/web/src/shared/ui/text-field.tsx`

```tsx
import { useId, type ComponentProps } from "react";

import { Input } from "./input";
import { Label } from "./label";

/** 이름표, 입력, 도움말, 오류를 한 묶음으로 잇는다. 오류는 `role="alert"`로 바로 읽힌다. */
export function TextField({
  label,
  hint,
  error,
  id,
  ...props
}: Omit<ComponentProps<"input">, "aria-invalid" | "aria-describedby"> & {
  label: string;
  hint?: string;
  error?: string | null;
}) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  const hintId = `${fieldId}-hint`;
  const errorId = `${fieldId}-error`;
  const describedBy =
    [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") ||
    undefined;

  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={fieldId}>{label}</Label>
      <Input
        id={fieldId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...props}
      />
      {hint ? (
        <p id={hintId} className="text-label text-fg-subtle">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-label text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
```

`apps/web/src/shared/ui/index.ts`에 추가한다.

```ts
export { Chip } from "./chip";
export { TextField } from "./text-field";
export { Textarea } from "./textarea";
```

- [ ] **Step 3: Input, Label, Card를 새 토큰으로 바꾼다**

`apps/web/src/shared/ui/input.tsx`의 `className` 문자열을 아래로 바꾼다.

```tsx
        "rounded-control border-line-strong bg-surface text-body text-fg placeholder:text-fg-subtle hover:border-fg-muted disabled:bg-surface-sunken disabled:text-fg-subtle aria-invalid:border-danger aria-invalid:bg-danger-tint h-13 w-full border px-4 disabled:cursor-not-allowed",
```

`apps/web/src/shared/ui/label.tsx`의 `cn(...)` 첫 인자를 아래로 바꾼다.

```tsx
      className={cn("text-label text-fg-muted font-medium", className)}
```

`apps/web/src/shared/ui/card.tsx`의 `cn(...)` 첫 인자를 아래로 바꾼다.

```tsx
        "rounded-surface border-line bg-surface border p-5 sm:p-6",
```

- [ ] **Step 4: 나머지 공용 컴포넌트의 옛 이름을 한꺼번에 옮긴다**

```bash
cd apps/web
perl -pi \
  -e 's/bg-neutral-950\/40/bg-scrim/g;' \
  -e 's/ring-neutral-950/ring-focus/g;' \
  -e 's/bg-neutral-900/bg-action/g;' \
  -e 's/bg-neutral-200/bg-line/g;' \
  -e 's/bg-neutral-(?:50|100)/bg-surface-sunken/g;' \
  -e 's/(border|divide)-neutral-200/$1-line/g;' \
  -e 's/border-neutral-300/border-line-strong/g;' \
  -e 's/text-neutral-(?:900|800)/text-fg/g;' \
  -e 's/text-neutral-700/text-fg-muted/g;' \
  -e 's/text-neutral-(?:600|500)/text-fg-subtle/g;' \
  -e 's/bg-brand-600/bg-action/g;' \
  -e 's/text-red-600/text-danger/g;' \
  -e 's/bg-white/bg-surface/g;' \
  -e 's/text-white/text-on-action/g;' \
  -e 's/font-semibold/font-bold/g;' \
  -e 's/ ?shadow-lg//g;' \
  src/shared/ui/list-skeleton.tsx src/shared/ui/selection-indicator.tsx \
  src/shared/ui/brand-mark.tsx src/shared/ui/toast.tsx src/shared/ui/confirm-dialog.tsx
pnpm exec prettier --write src/shared/ui
cd ../..
```

Run: `grep -nE "neutral-|brand-|red-[0-9]|bg-white|text-white|font-semibold|shadow-(lg|surface)" apps/web/src/shared/ui/*.tsx | grep -v test`
Expected: 아무것도 나오지 않는다. 남은 것이 있으면 위 대응표에 맞춰 손으로 고친다(예: `hover:text-neutral-900` → `hover:text-fg`).

- [ ] **Step 5: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web test`
Expected: 두 번째 옛 색 이름 테스트만 실패한다(`shared/ui`의 파일들이 더는 걸리지 않으므로). 목록을 다시 만든다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
grep -c "shared/ui" apps/web/src/core/styles/legacy-style-files.json
```

Expected: `0`. `shared/ui`의 파일이 목록에 하나도 없다.

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web test`
Expected: 모두 통과. `confirm-dialog.test.tsx`와 `toast.test.tsx`가 그대로 통과한다(DS-US2-AC3).

```bash
git add apps/web/src
git commit -m "feat(web): 공용 컴포넌트를 역할 토큰으로 옮기고 Chip과 TextField를 더한다"
```

---

### Task 6: 상대 시각

**Files:**
- Create: `apps/web/src/shared/lib/format-relative-time.ts`
- Create: `apps/web/src/shared/lib/format-relative-time.test.ts`
- Modify: `apps/web/src/shared/lib/index.ts`

**Interfaces:**
- Produces: `formatRelativeTime(date: string | number | Date, now?: string | number | Date): string`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/shared/lib/format-relative-time.test.ts`

```ts
import { describe, expect, it } from "vitest";

import { formatRelativeTime } from "./format-relative-time";

const NOW = "2026-10-13T12:00:00+09:00";

describe("formatRelativeTime", () => {
  it.each([
    ["2026-10-13T11:59:30+09:00", "방금"],
    ["2026-10-13T11:59:00+09:00", "1분 전"],
    ["2026-10-13T11:01:00+09:00", "59분 전"],
    ["2026-10-13T11:00:00+09:00", "1시간 전"],
    ["2026-10-12T12:01:00+09:00", "23시간 전"],
    ["2026-10-12T12:00:00+09:00", "1일 전"],
    ["2026-10-06T12:01:00+09:00", "6일 전"],
  ])("DS-US3-AC1 %s는 %s로 쓴다", (date, expected) => {
    expect(formatRelativeTime(date, NOW)).toBe(expected);
  });

  it("DS-US3-AC1 일주일이 지나면 날짜로 쓴다", () => {
    expect(formatRelativeTime("2026-10-06T03:00:00Z", NOW)).toMatch(
      /^2026\.10\.06$/,
    );
  });

  it("시계가 어긋나 미래인 시각은 방금으로 쓴다", () => {
    expect(formatRelativeTime("2026-10-13T12:03:00+09:00", NOW)).toBe("방금");
  });

  it("읽을 수 없는 값은 빈 문자열이다", () => {
    expect(formatRelativeTime("not-a-date", NOW)).toBe("");
  });
});
```

Run: `pnpm --filter web exec vitest run src/shared/lib/format-relative-time.test.ts`
Expected: FAIL. 모듈을 찾지 못한다.

- [ ] **Step 2: 함수를 쓴다**

`apps/web/src/shared/lib/format-relative-time.ts`

```ts
import dayjs from "dayjs";

/**
 * 작성 시각을 "12분 전"처럼 쓴다. 일주일이 지나면 날짜(`2026.10.06`)로 쓴다.
 * 기기 시계가 어긋나 시각이 미래로 보이면 "방금"으로, 읽을 수 없는 값은 빈 문자열로 돌려준다.
 */
export function formatRelativeTime(
  date: string | number | Date,
  now: string | number | Date = new Date(),
): string {
  const then = dayjs(date);
  if (!then.isValid()) return "";
  const minutes = dayjs(now).diff(then, "minute");
  if (minutes < 1) return "방금";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}일 전`;
  return then.format("YYYY.MM.DD");
}
```

`apps/web/src/shared/lib/index.ts`의 `export { formatDate } from "./format-date";` 아래에 추가한다.

```ts
export { formatRelativeTime } from "./format-relative-time";
```

- [ ] **Step 3: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web exec vitest run src/shared/lib/format-relative-time.test.ts`
Expected: PASS (10개). "일주일이 지나면" 테스트가 시간대 때문에 실패하면 CI(UTC)와 로컬(KST) 모두에서 날짜가 `10.06`인 입력인지 다시 본다. `2026-10-06T03:00:00Z`는 두 시간대 모두 10월 6일이다.

```bash
git add apps/web/src/shared/lib
git commit -m "feat(web): 작성 시각을 상대 시각으로 쓰는 함수를 더한다"
```

---

### Task 7: 몬스터 컴포넌트

**Files:**
- Create: `apps/web/src/entities/monster/ui/emotion-tag.tsx`
- Create: `apps/web/src/entities/monster/ui/hp-bar.tsx`
- Create: `apps/web/src/entities/monster/ui/monster-thumb.tsx`
- Create: `apps/web/src/entities/monster/ui/monster-stage.tsx`
- Create: `apps/web/src/entities/monster/ui/monster-parts.test.tsx`
- Modify: `apps/web/src/entities/monster/model/types.ts` (`EMOTION_COLORS`)
- Modify: `apps/web/src/entities/monster/model/types.test.ts`
- Modify: `apps/web/src/entities/monster/index.ts`

**Interfaces:**
- Consumes: `data-emotion` 범위와 `bg-emotion`, `bg-emotion-tint`, `text-emotion-fg`(Task 1). 기존 `MonsterSprite({ emotion, stage, sizes, boss?, className? })`, `EmotionType`, `HpStage`, `EMOTION_LABELS`.
- Produces:
  - `EmotionTag({ emotion: EmotionType; className?: string })`
  - `hpPercent(hp: number, maxHp: number): number` — 0~100의 정수.
  - `HpBar({ emotion: EmotionType; hp: number; maxHp: number; size?: "sm" | "lg"; label?: string; className?: string })`
  - `MonsterThumb({ emotion: EmotionType; stage: HpStage; className?: string })`
  - `MonsterStage({ emotion: EmotionType; className?: string; children: ReactNode })`
  - `EMOTION_COLORS`의 값이 `globals.css`의 감정 진한 색과 같다.

기존 `MonsterDisplay`는 이 Task에서 건드리지 않는다. 화면 계획에서 새 부품으로 바꾼다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/entities/monster/ui/monster-parts.test.tsx`

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EmotionTag } from "./emotion-tag";
import { HpBar, hpPercent } from "./hp-bar";
import { MonsterStage } from "./monster-stage";
import { MonsterThumb } from "./monster-thumb";

describe("EmotionTag", () => {
  it("DS-US3-AC1 감정 이름을 보이고 그 감정의 색 범위를 연다", () => {
    render(<EmotionTag emotion="LONELINESS" />);
    const tag = screen.getByText("외로움");
    expect(tag).toHaveAttribute("data-emotion", "LONELINESS");
    expect(tag).toHaveClass("bg-emotion-tint", "text-emotion-fg");
  });
});

describe("hpPercent", () => {
  it.each([
    [62, 100, 62],
    [10, 30, 33],
    [0, 100, 0],
    [100, 100, 100],
  ])("%d / %d는 %d%%다", (hp, maxHp, expected) => {
    expect(hpPercent(hp, maxHp)).toBe(expected);
  });

  it.each([
    [150, 100, 100],
    [-5, 100, 0],
    [10, 0, 0],
    [10, -20, 0],
    [Number.NaN, 100, 0],
    [10, Number.POSITIVE_INFINITY, 0],
  ])("범위를 벗어난 %d / %d는 %d%%로 자른다", (hp, maxHp, expected) => {
    expect(hpPercent(hp, maxHp)).toBe(expected);
  });
});

describe("HpBar", () => {
  it("DS-US3-AC1 작은 막대는 6px이고 진행 막대로 읽힌다", () => {
    render(<HpBar emotion="ANXIETY" hp={62} maxHp={100} />);
    const bar = screen.getByRole("progressbar", { name: "몬스터 HP" });
    expect(bar).toHaveAttribute("aria-valuenow", "62");
    expect(bar).toHaveAttribute("aria-valuemax", "100");
    expect(bar).toHaveAttribute("data-emotion", "ANXIETY");
    expect(bar).toHaveClass("h-1.5");
    expect(bar.firstElementChild).toHaveStyle({ width: "62%" });
  });

  it("DS-US3-AC2 큰 막대는 22px에 외곽선이 있다", () => {
    render(<HpBar emotion="IRRITATION" hp={4120} maxHp={10000} size="lg" />);
    expect(screen.getByRole("progressbar")).toHaveClass(
      "h-[22px]",
      "border-2",
      "border-game-line",
    );
  });

  it("최대치를 넘는 HP도 100%를 넘지 않는다", () => {
    render(<HpBar emotion="ANXIETY" hp={150} maxHp={100} />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuenow", "100");
    expect(bar.firstElementChild).toHaveStyle({ width: "100%" });
  });
});

describe("MonsterThumb", () => {
  it("DS-US3-AC1 48px 자리에 감정의 옅은 색을 깔고 그림을 올린다", () => {
    render(<MonsterThumb emotion="LETHARGY" stage="hurt" />);
    const image = screen.getByRole("img", { name: "무기력 몬스터, 상처 입음" });
    expect(image.parentElement).toHaveAttribute("data-emotion", "LETHARGY");
    expect(image.parentElement).toHaveClass("size-12", "bg-emotion-tint");
  });
});

describe("MonsterStage", () => {
  it("DS-US3-AC2 무대는 그 감정의 옅은 색이다", () => {
    render(
      <MonsterStage emotion="IRRITATION">
        <p>보스</p>
      </MonsterStage>,
    );
    const stage = screen.getByText("보스").parentElement;
    expect(stage).toHaveAttribute("data-emotion", "IRRITATION");
    expect(stage).toHaveClass("bg-emotion-tint");
  });
});
```

`apps/web/src/entities/monster/model/types.test.ts`에 추가한다. `import` 세 줄은 파일 맨 위의 기존 import 옆에 두고(이미 있는 이름은 겹치지 않게), `describe` 블록은 파일 끝에 둔다.

```ts
import { readFileSync } from "node:fs";
import path from "node:path";

import { EMOTION_COLORS } from "./types";

describe("EMOTION_COLORS", () => {
  it("DS-US1-AC3 차트의 감정 색은 globals.css의 감정 색과 같다", () => {
    const css = readFileSync(
      path.resolve(__dirname, "../../../core/styles/globals.css"),
      "utf8",
    );
    for (const [emotion, color] of Object.entries(EMOTION_COLORS)) {
      const block = css.match(
        new RegExp(`\\[data-emotion="${emotion}"\\]\\s*\\{([^}]*)\\}`),
      );
      expect(block, `${emotion}의 색 묶음`).not.toBeNull();
      expect(block![1]).toContain(`--emotion: ${color};`);
    }
  });
});
```

Run: `pnpm --filter web exec vitest run src/entities/monster`
Expected: FAIL. 새 모듈을 찾지 못하고, `EMOTION_COLORS` 테스트는 값이 달라 실패한다.

- [ ] **Step 2: 컴포넌트를 쓴다**

`apps/web/src/entities/monster/ui/emotion-tag.tsx`

```tsx
import { cn } from "@/shared/lib";

import { EMOTION_LABELS, type EmotionType } from "../model/types";

/** 점과 감정 이름. 색은 `data-emotion`이 여는 감정 색 범위에서 온다. */
export function EmotionTag({
  emotion,
  className,
}: {
  emotion: EmotionType;
  className?: string;
}) {
  return (
    <span
      data-emotion={emotion}
      className={cn(
        "bg-emotion-tint text-caption text-emotion-fg inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full px-2.5 font-bold",
        className,
      )}
    >
      <span aria-hidden className="bg-emotion size-1.5 rounded-full" />
      {EMOTION_LABELS[emotion]}
    </span>
  );
}
```

`apps/web/src/entities/monster/ui/hp-bar.tsx`

```tsx
import { cn } from "@/shared/lib";

import type { EmotionType } from "../model/types";

/** HP를 0~100의 정수 퍼센트로 바꾼다. 범위를 벗어나거나 읽을 수 없는 값은 0~100으로 자른다. */
export function hpPercent(hp: number, maxHp: number): number {
  if (!Number.isFinite(hp) || !Number.isFinite(maxHp) || maxHp <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round((hp / maxHp) * 100)));
}

/**
 * HP 막대. `sm`은 피드의 6px 막대, `lg`는 글 상세와 레이드의 22px 외곽선 막대다.
 * 색만으로 전하지 않게, 쓰는 곳에서 HP 숫자를 글자로 함께 보인다.
 */
export function HpBar({
  emotion,
  hp,
  maxHp,
  size = "sm",
  label = "몬스터 HP",
  className,
}: {
  emotion: EmotionType;
  hp: number;
  maxHp: number;
  size?: "sm" | "lg";
  label?: string;
  className?: string;
}) {
  const percent = hpPercent(hp, maxHp);
  const max = Number.isFinite(maxHp) && maxHp > 0 ? maxHp : 0;
  const now = Number.isFinite(hp) ? Math.min(Math.max(0, hp), max) : 0;

  return (
    <div
      data-emotion={emotion}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={now}
      className={cn(
        "w-full overflow-hidden rounded-full",
        size === "lg"
          ? "border-game-line bg-surface h-[22px] border-2"
          : "bg-line h-1.5",
        className,
      )}
    >
      <div
        className={cn(
          "bg-emotion h-full transition-[width] duration-200 motion-reduce:transition-none",
          size === "lg" &&
            percent > 0 &&
            percent < 100 &&
            "border-game-line border-r-2",
        )}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
```

`apps/web/src/entities/monster/ui/monster-thumb.tsx`

```tsx
import { cn } from "@/shared/lib";

import type { HpStage } from "../model/hp-stage";
import type { EmotionType } from "../model/types";
import { MonsterSprite } from "./monster-sprite";

/** 피드에 쓰는 48px 몬스터. 감정의 옅은 색을 바탕에 깐다. */
export function MonsterThumb({
  emotion,
  stage,
  className,
}: {
  emotion: EmotionType;
  stage: HpStage;
  className?: string;
}) {
  return (
    <span
      data-emotion={emotion}
      className={cn(
        "rounded-control bg-emotion-tint inline-flex size-12 shrink-0 items-center justify-center p-0.5",
        className,
      )}
    >
      <MonsterSprite emotion={emotion} stage={stage} sizes="48px" />
    </span>
  );
}
```

`apps/web/src/entities/monster/ui/monster-stage.tsx`

```tsx
import type { ReactNode } from "react";

import { cn } from "@/shared/lib";

import type { EmotionType } from "../model/types";

/** 글 상세와 레이드의 무대. 감정의 옅은 색으로 채우고, 안의 감정 색 유틸리티가 이 감정을 따른다. */
export function MonsterStage({
  emotion,
  className,
  children,
}: {
  emotion: EmotionType;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div data-emotion={emotion} className={cn("bg-emotion-tint", className)}>
      {children}
    </div>
  );
}
```

`apps/web/src/entities/monster/model/types.ts`에서 `EMOTION_COLORS`와 그 위의 주석을 아래로 바꾼다.

```ts
/**
 * 차트에서 감정을 가르는 색. 몬스터 그림에서 뽑았고 `globals.css`의 `[data-emotion]` 값과 같다
 * (types.test.ts가 지킨다). 불안(보라)과 무기력(남색)은 색이 가까우니, 차트는 언제나 이름과 숫자를
 * 글자로 함께 보인다.
 */
export const EMOTION_COLORS: Record<EmotionType, string> = {
  ANXIETY: "#8b5cf6",
  LETHARGY: "#7187e0",
  LONELINESS: "#35b6d3",
  SELF_DEPRECATION: "#8a9a3c",
  IRRITATION: "#e5484d",
};
```

`apps/web/src/entities/monster/index.ts`의 끝에 추가한다.

```ts
export { EmotionTag } from "./ui/emotion-tag";
export { HpBar, hpPercent } from "./ui/hp-bar";
export { MonsterStage } from "./ui/monster-stage";
export { MonsterThumb } from "./ui/monster-thumb";
```

- [ ] **Step 3: 통과를 확인하고 커밋한다**

Run: `pnpm --filter web exec vitest run src/entities/monster && pnpm --filter web lint:fsd`
Expected: PASS. 기존 `monster-view.test.tsx`도 그대로 통과한다.

Run: `pnpm --filter web test`
Expected: 모두 통과. 차트 색을 값으로 단정한 테스트가 있어 실패하면 새 값으로 고친다.

```bash
git add apps/web/src/entities/monster
git commit -m "feat(web): 감정 태그, HP 막대, 몬스터 썸네일과 무대를 더한다"
```

---

### Task 8: 카탈로그 페이지

**Files:**
- Modify: `apps/web/package.json` (의존성)
- Modify: `apps/web/src/shared/config/env.ts`
- Modify: `apps/web/playwright.config.ts`
- Create: `apps/web/src/app/debug/design/page.tsx`
- Create: `apps/web/e2e/design-catalog.spec.ts`

**Interfaces:**
- Consumes: Task 4, 5의 `Button`, `GameButton`, `IconButton`, `Chip`, `TextField`, `Textarea`. Task 6의 `formatRelativeTime`. Task 7의 `EmotionTag`, `HpBar`, `MonsterThumb`, `MonsterStage`. 기존 `MonsterSprite`, `EMOTION_LABELS`.
- Produces: `/debug/design` 경로. `ENABLE_DESIGN_CATALOG=1`일 때만 있고 그 밖에는 404다.

- [ ] **Step 1: 실패하는 e2e를 쓴다**

`apps/web/e2e/design-catalog.spec.ts`

```ts
import { expect, test } from "@playwright/test";

for (const width of [320, 390, 1440]) {
  test(`DS-US2-AC4, DS-US3-AC6 카탈로그가 ${width}px에서 넘치지 않는다`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/debug/design");
    await expect(
      page.getByRole("heading", { level: 1, name: "디자인 카탈로그" }),
    ).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      background: getComputedStyle(document.body).backgroundColor,
      font: getComputedStyle(document.body).fontFamily,
    }));
    expect(layout.overflow).toBe(false);
    expect(layout.background).toBe("rgb(255, 255, 255)");
    expect(layout.font).toContain("Pretendard");
  });
}

test("DS-US2-AC2 버튼의 누르는 영역이 44px 이상이다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/debug/design");
  for (const name of ["주요 동작", "테두리", "가라앉은 면", "글자만", "삭제"]) {
    const box = await page
      .getByRole("button", { name, exact: true })
      .boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
  const attack = await page
    .getByRole("button", { name: "공격하기", exact: true })
    .first()
    .boundingBox();
  expect(attack!.height).toBeGreaterThanOrEqual(56);
  const hitArea = await page
    .getByRole("button", { name: "전체", exact: true })
    .evaluate((chip) => parseFloat(getComputedStyle(chip, "::before").height));
  expect(hitArea).toBeGreaterThanOrEqual(44);
});

test("DS-US1-AC3 감정마다 태그의 점과 HP 막대의 색이 같다", async ({ page }) => {
  await page.goto("/debug/design");
  for (const emotion of [
    "ANXIETY",
    "LETHARGY",
    "LONELINESS",
    "SELF_DEPRECATION",
    "IRRITATION",
  ]) {
    const row = page.getByTestId(`emotion-${emotion}`);
    const colors = await row.evaluate((element) => {
      const dot = element.querySelector("[data-emotion] > [aria-hidden]")!;
      const fill = element.querySelector('[role="progressbar"] > div')!;
      return [
        getComputedStyle(dot).backgroundColor,
        getComputedStyle(fill).backgroundColor,
      ];
    });
    expect(colors[0]).toBe(colors[1]);
    expect(colors[0]).not.toBe("rgba(0, 0, 0, 0)");
  }
});

test("DS-US3-AC2 감정이 정해지지 않은 HP 막대도 색이 있다", async ({ page }) => {
  await page.goto("/debug/design");
  const fill = await page
    .getByTestId("emotion-fallback")
    .evaluate(
      (element) => getComputedStyle(element.firstElementChild!).backgroundColor,
    );
  expect(fill).toBe("rgb(107, 107, 118)");
});
```

- [ ] **Step 2: 실패를 확인한다**

`apps/web/playwright.config.ts`의 `webServer.env`를 아래로 바꾼다.

```ts
    env: {
      API_ORIGIN: process.env.API_ORIGIN ?? "http://127.0.0.1:9",
      ENABLE_DESIGN_CATALOG: "1",
    },
```

Run: `pnpm --filter web exec playwright test e2e/design-catalog.spec.ts`
Expected: FAIL. `/debug/design`이 404라 제목을 찾지 못한다.

- [ ] **Step 3: 환경 변수와 아이콘 의존성을 더한다**

```bash
pnpm --filter web add @phosphor-icons/react@2.1.10
```

`apps/web/src/shared/config/env.ts`의 `ENABLE_ERROR_PROBE` 줄 아래에 추가한다.

```ts
    // 디자인 카탈로그(/debug/design)를 켤지 여부. 운영에서는 켜지 않는다.
    ENABLE_DESIGN_CATALOG: z.stringbool().optional(),
```

- [ ] **Step 4: 카탈로그 페이지를 쓴다**

`apps/web/src/app/debug/design/page.tsx`

```tsx
import {
  Bell,
  ChatCircle,
  Heart,
  House,
  PencilSimpleLine,
  Sword,
  User,
} from "@phosphor-icons/react/dist/ssr";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";

import {
  EMOTION_LABELS,
  EmotionTag,
  HpBar,
  MonsterSprite,
  MonsterStage,
  MonsterThumb,
  type EmotionType,
} from "@/entities/monster";
import { env } from "@/shared/config";
import { formatRelativeTime } from "@/shared/lib";
import {
  Button,
  Chip,
  GameButton,
  IconButton,
  TextField,
  Textarea,
} from "@/shared/ui";

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

const ROLE_COLORS: [name: string, className: string][] = [
  ["page", "bg-page"],
  ["surface", "bg-surface"],
  ["surface-sunken", "bg-surface-sunken"],
  ["line", "bg-line"],
  ["line-strong", "bg-line-strong"],
  ["fg-subtle", "bg-fg-subtle"],
  ["fg-muted", "bg-fg-muted"],
  ["fg / action", "bg-fg"],
  ["danger", "bg-danger"],
  ["danger-tint", "bg-danger-tint"],
  ["warning", "bg-warning"],
  ["warning-tint", "bg-warning-tint"],
  ["game", "bg-game"],
];

const TYPE_SCALE: [name: string, className: string, sample: string][] = [
  ["numeric 36/40", "text-numeric font-extrabold tabular-nums", "4,120"],
  ["display 32/40", "text-display font-extrabold", "짜증"],
  ["heading 24/32", "text-heading font-bold", "마음 이야기"],
  ["title 20/28", "text-title font-bold", "댓글 7"],
  ["body-lg 17/29", "text-body-lg", "내일 배포인데 테스트를 다 못 돌렸다."],
  ["body 16/26", "text-body", "내일 배포인데 테스트를 다 못 돌렸다."],
  ["body-sm 15/24", "text-body-sm", "저도 배포 전날엔 늘 그래요."],
  ["label 13/18", "text-label text-fg-subtle", "개발 3년차"],
  ["caption 12/16", "text-caption font-bold", "HP 62"],
];

const NOW = "2026-10-13T12:00:00+09:00";
const TIMES = [
  "2026-10-13T11:59:40+09:00",
  "2026-10-13T11:48:00+09:00",
  "2026-10-13T09:00:00+09:00",
  "2026-10-10T12:00:00+09:00",
  "2026-09-30T12:00:00+09:00",
];

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-line flex flex-col gap-4 border-t py-6">
      <h2 className="text-title font-bold">{title}</h2>
      {children}
    </section>
  );
}

/**
 * 디자인 토큰과 공용 컴포넌트를 한 화면에서 보는 디버그 전용 페이지(DS-US2-AC4).
 * `ENABLE_DESIGN_CATALOG=1`일 때만 있고 그 밖에는 404다. 보호 경로가 아니라 로그인 없이 열린다.
 */
export default function DesignCatalogPage() {
  if (env.ENABLE_DESIGN_CATALOG !== true) {
    notFound();
  }

  return (
    <div className="text-fg flex w-full max-w-[720px] flex-col px-5 pb-16">
      <h1 className="text-heading py-6 font-bold">디자인 카탈로그</h1>

      <Section title="역할 색">
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {ROLE_COLORS.map(([name, className]) => (
            <li key={name} className="flex flex-col gap-1">
              <span
                className={`rounded-control border-line h-12 border ${className}`}
              />
              <span className="text-caption text-fg-muted">{name}</span>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="글자 단계">
        <ul className="flex flex-col gap-4">
          {TYPE_SCALE.map(([name, className, sample]) => (
            <li key={name} className="flex flex-col gap-1">
              <span className="text-caption text-fg-subtle">{name}</span>
              <span className={className}>{sample}</span>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="버튼">
        <div className="flex flex-wrap gap-2">
          <Button>주요 동작</Button>
          <Button variant="secondary">테두리</Button>
          <Button variant="subtle">가라앉은 면</Button>
          <Button variant="ghost">글자만</Button>
          <Button variant="danger">삭제</Button>
          <Button disabled>잠김</Button>
        </div>
        <Button size="lg">
          로그인하고 다른 회원들의 마음 이야기를 처음부터 끝까지 읽어 보기
        </Button>
        <div className="flex gap-1">
          <IconButton aria-label="알림">
            <Bell size={24} aria-hidden />
          </IconButton>
          <IconButton aria-label="공감">
            <Heart size={24} weight="fill" aria-hidden />
          </IconButton>
          <IconButton aria-label="댓글">
            <ChatCircle size={24} aria-hidden />
          </IconButton>
        </div>
      </Section>

      <Section title="게임 버튼">
        <GameButton>공격하기</GameButton>
        <GameButton>
          <Heart size={24} weight="fill" aria-hidden />
          공감 보내기
        </GameButton>
        <GameButton disabled>공격하기</GameButton>
      </Section>

      <Section title="칩">
        <div className="flex flex-wrap gap-2">
          <Chip pressed>전체</Chip>
          <Chip pressed={false}>개발</Chip>
          <Chip pressed={false}>디자인</Chip>
          <Chip pressed={false}>1~3년차</Chip>
        </div>
      </Section>

      <Section title="입력">
        <TextField label="닉네임" hint="2~10자" placeholder="조용한수달" />
        <TextField
          label="이메일"
          defaultValue="ogu@"
          error="이메일 형식이 아니에요"
        />
        <label className="flex flex-col gap-2">
          <span className="text-label text-fg-muted font-medium">고민</span>
          <Textarea placeholder="요즘 마음을 무겁게 하는 일을 적어 주세요" />
        </label>
      </Section>

      <Section title="아이콘">
        <ul className="text-caption text-fg-subtle flex flex-wrap gap-6">
          <li className="text-fg flex flex-col items-center gap-1 font-bold">
            <House size={24} weight="fill" aria-hidden />
            이야기
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <Sword size={24} aria-hidden />
            함께
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <PencilSimpleLine size={24} aria-hidden />
            쓰기
          </li>
          <li className="flex flex-col items-center gap-1 font-bold">
            <User size={24} aria-hidden />
            기록
          </li>
        </ul>
      </Section>

      <Section title="상대 시각">
        <ul className="text-label text-fg-subtle flex flex-wrap gap-4">
          {TIMES.map((time) => (
            <li key={time}>{formatRelativeTime(time, NOW)}</li>
          ))}
        </ul>
      </Section>

      <Section title="감정">
        <ul className="flex flex-col gap-4">
          {EMOTIONS.map((emotion) => (
            <li
              key={emotion}
              data-testid={`emotion-${emotion}`}
              className="flex items-center gap-3"
            >
              <MonsterThumb emotion={emotion} stage="hurt" />
              <div className="flex min-w-0 flex-1 flex-col gap-2">
                <EmotionTag emotion={emotion} className="self-start" />
                <HpBar emotion={emotion} hp={62} maxHp={100} />
              </div>
            </li>
          ))}
        </ul>
        <div data-testid="emotion-fallback" className="bg-line h-1.5 w-full">
          <div className="bg-emotion h-full w-1/2" />
        </div>
      </Section>

      <Section title="무대">
        <MonsterStage
          emotion="ANXIETY"
          className="rounded-surface flex flex-col items-center gap-3 p-5"
        >
          <div className="size-44">
            <MonsterSprite emotion="ANXIETY" stage="hurt" sizes="176px" />
          </div>
          <p className="text-label text-emotion-fg font-bold">
            불안 몬스터 · 상처 입음
          </p>
          <HpBar emotion="ANXIETY" hp={62} maxHp={100} size="lg" />
        </MonsterStage>
      </Section>
    </div>
  );
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd`
Expected: 통과. `@phosphor-icons/react/dist/ssr`를 찾지 못하면 `apps/web/node_modules/@phosphor-icons/react/package.json`의 `exports`에서 서버 컴포넌트용 경로를 확인해 그 경로로 바꾼다.

Run: `pnpm --filter web exec playwright test e2e/design-catalog.spec.ts`
Expected: PASS (6개). 로컬에 떠 있는 옛 서버를 다시 쓰면 404가 나니, 3000번 포트의 서버를 끄고 돌린다.

320px에서 넘침 테스트가 실패하면 넘치는 요소를 찾아 그 컴포넌트를 고친다. 긴 문구의 `Button`이 줄바꿈되지 않는 경우가 가장 흔하다.

Run: `ENABLE_DESIGN_CATALOG= pnpm --filter web build` 뒤 `grep -rn "ENABLE_DESIGN_CATALOG" infra .github`
Expected: 빌드 통과. `infra`와 `.github`에는 이 변수가 없다(운영에서 켜지지 않는다).

- [ ] **Step 6: 커밋한다**

```bash
git add apps/web/package.json pnpm-lock.yaml apps/web/src apps/web/e2e/design-catalog.spec.ts apps/web/playwright.config.ts
git commit -m "feat(web): 토큰과 컴포넌트를 한 화면에서 보는 카탈로그를 더한다"
```

---

### Task 9: 문서를 코드와 맞춘다

**Files:**
- Modify: `apps/web/docs/DESIGN_SYSTEM.md` (전체 다시 쓰기)
- Modify: `apps/web/src/core/styles/tokens.test.ts`

**Interfaces:**
- Consumes: Task 1의 `globals.css`.
- Produces: 문서에 적힌 색 값이 `globals.css`에 없으면 실패하는 테스트.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/web/src/core/styles/tokens.test.ts`의 끝에 추가한다.

```ts
describe("DESIGN_SYSTEM.md", () => {
  it("DS-US4-AC4 문서에 적힌 색 값은 모두 globals.css에 있다", () => {
    const doc = readFileSync(
      path.resolve(__dirname, "../../../docs/DESIGN_SYSTEM.md"),
      "utf8",
    );
    const documented = [...new Set(doc.match(/#[0-9a-fA-F]{6}\b/g) ?? [])];
    expect(documented.length).toBeGreaterThan(20);
    const known = css.toLowerCase();
    expect(
      documented.filter((hex) => !known.includes(hex.toLowerCase())),
    ).toEqual([]);
  });
});
```

Run: `pnpm --filter web exec vitest run src/core/styles/tokens.test.ts`
Expected: FAIL. 옛 문서의 `#7655b4` 같은 값이 `globals.css`에 없다.

- [ ] **Step 2: 문서를 다시 쓴다**

`apps/web/docs/DESIGN_SYSTEM.md` 전체를 아래로 바꾼다.

````markdown
# 오구오구 디자인 시스템

직장인의 고민을 나누고 공감으로 감정 몬스터를 함께 물리치는 커뮤니티다. 피드는 글을 읽기 좋게, 글 상세와 레이드는 몬스터가 주인공이 되게 한다. 브랜드 색은 두지 않는다. 화면의 색은 다섯 감정에서만 나온다.

설계와 결정의 까닭은 `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md`에 있다. 토큰과 컴포넌트는 `ENABLE_DESIGN_CATALOG=1`로 띄운 `/debug/design`에서 한 화면으로 본다.

## 토큰

정본은 `src/core/styles/globals.css`다. 화면과 컴포넌트는 역할 이름만 쓴다.

| 역할 | 유틸리티 예 | 값 |
| --- | --- | --- |
| 바탕, 면 | `bg-page`, `bg-surface` | #ffffff |
| 가라앉은 면 | `bg-surface-sunken` | #f5f5f7 |
| 구분선 | `border-line` | #ececf0 |
| 입력 테두리 | `border-line-strong` | #8a8a95 |
| 본문 | `text-fg` | #17171c |
| 보조 글자 | `text-fg-muted` | #45454f |
| 부가 정보 | `text-fg-subtle` | #6b6b76 |
| 주요 동작 | `bg-action`, `text-on-action` | #17171c, #ffffff |
| 주요 동작 hover | `bg-action-hover` | #2a2a32 |
| 오류와 삭제 | `text-danger`, `bg-danger-tint` | #b42318, #fef3f2 |
| 주의와 숨김 표시 | `text-warning`, `bg-warning-tint` | #b54708, #fffaeb |
| 게임 버튼 | `bg-game`, `border-game-line` | #ffd60a, #17171c |

원색(`--ink-900` 등)은 `:root`의 CSS 변수로만 있고 Tailwind 유틸리티로는 생기지 않는다.

### 감정 색

몬스터 그림에서 뽑았다. `data-emotion` 속성이 붙은 요소 안에서 `bg-emotion`, `bg-emotion-tint`, `text-emotion-fg`가 그 감정의 값이 된다. 속성이 없으면 회색이다.

| 감정 | 진한 색 | 옅은 색 | 글자 색 |
| --- | --- | --- | --- |
| 불안 `ANXIETY` | #8b5cf6 | #f1ebff | #5334b8 |
| 무기력 `LETHARGY` | #7187e0 | #e9edfc | #3346a3 |
| 외로움 `LONELINESS` | #35b6d3 | #e0f5fa | #0d6379 |
| 자기비하 `SELF_DEPRECATION` | #8a9a3c | #f0f3de | #56621d |
| 짜증 `IRRITATION` | #e5484d | #fdeaea | #a81f26 |

감정 색은 감정을 나타낼 때만 쓴다. 오류와 삭제에는 `danger`를 쓴다. 불안과 무기력은 색이 가까우니 차트에서는 이름과 숫자를 함께 쓴다.

## 글자

Pretendard 가변 글꼴이다. 굵기는 400, 500, 700, 800만 쓴다.

| 유틸리티 | 크기 / 줄 높이 | 쓰임 |
| --- | --- | --- |
| `text-caption` | 12 / 16 | 감정 태그, HP 숫자 |
| `text-label` | 13 / 18 | 작성자 정보, 시각 |
| `text-body-sm` | 15 / 24 | 댓글, 버튼 |
| `text-body` | 16 / 26 | 피드 본문, 입력 |
| `text-body-lg` | 17 / 29 | 글 상세 본문 |
| `text-title` | 20 / 28 | 구역 제목 |
| `text-heading` | 24 / 32 | 화면 제목 |
| `text-display` | 32 / 40 | 레이드 보스 이름 |
| `text-numeric` | 36 / 40 | 레이드 HP |

숫자는 `tabular-nums`로 쓴다. 작성 시각은 `formatRelativeTime`으로 "12분 전"처럼 쓰고, 일주일이 지나면 날짜로 쓴다.

## 모양과 움직임

- 모서리: `rounded-inline` 8px, `rounded-control` 14px(버튼, 입력, 썸네일), `rounded-surface` 20px(시트, 큰 면), `rounded-full`(칩, 태그).
- 높이: 칩 36px, 버튼 44px, 입력과 큰 버튼 52px, 게임 버튼 56px. 누르는 영역은 44px 이상이다.
- 기본은 1px 테두리이고 그림자는 쓰지 않는다. 게임 요소만 2px 외곽선과 `shadow-game`을 쓴다.
- 몬스터의 대기 움직임은 `monster-idle-*`다(ADR-0006). `prefers-reduced-motion`이면 움직이지 않는다.
- 아이콘은 Phosphor다. 기본은 선, 선택된 메뉴와 누른 공감은 `weight="fill"`이다. 글자와 함께 있는 아이콘은 `aria-hidden`으로 둔다.

## 컴포넌트

| 컴포넌트 | 위치 | 쓰임 |
| --- | --- | --- |
| `Button` | `shared/ui` | `primary`, `secondary`, `subtle`, `ghost`, `danger` |
| `GameButton` | `shared/ui` | 글 상세의 공감 보내기, 레이드의 공격하기에만 |
| `IconButton` | `shared/ui` | 아이콘만 있는 버튼. `aria-label` 필수 |
| `Chip` | `shared/ui` | 필터와 선택. `pressed`로 상태를 전한다 |
| `TextField`, `Textarea`, `Input`, `Label` | `shared/ui` | 입력 |
| `ConfirmDialog`, `Toaster` | `shared/ui` | 대화상자와 알림 토스트 |
| `EmotionTag` | `entities/monster` | 점과 감정 이름 |
| `HpBar` | `entities/monster` | `sm`(피드), `lg`(글 상세와 레이드) |
| `MonsterThumb` | `entities/monster` | 피드의 48px 몬스터 |
| `MonsterStage` | `entities/monster` | 감정 색으로 채운 무대 |

## 게임 요소를 쓰는 곳

`GameButton`과 큰 `HpBar`는 글 상세의 몬스터 무대와 레이드에만 쓴다. 위기 안내, 숨김 표시, 신고, 재검토 요청에는 게임 요소와 감정 색을 쓰지 않는다.

## 옮기는 중

화면은 아직 옛 이름(`neutral-*`, `brand-*`)을 쓰는 곳이 있다. `globals.css`가 옛 이름을 새 값에 이어 두었고, 아직 안 옮긴 파일은 `src/core/styles/legacy-style-files.json`에 있다. 파일을 옮기면 아래 명령으로 목록에서 뺀다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
```

## 에셋과 출처

| 에셋 | 위치 | 출처 / 라이선스 |
| --- | --- | --- |
| 감정 몬스터 | `public/monsters` | 이 서비스를 위해 만든 그림(ADR-0006) |
| 오구 캐릭터 | `public/brand/ogu-companion.png` | 이 서비스를 위해 만든 그림 |
| 한글 글꼴 | npm `pretendard` | [Pretendard](https://github.com/orioncactus/pretendard), SIL Open Font License 1.1 |
| 아이콘 | npm `@phosphor-icons/react` | [Phosphor](https://phosphoricons.com), MIT |
| 구글 로그인 G | `public/brand/google.png` | [Google 로그인 브랜딩](https://developers.google.com/identity/branding-guidelines). 원본 색과 비율 유지 |
````

- [ ] **Step 3: 통과를 확인한다**

Run: `pnpm --filter web exec vitest run src/core/styles/tokens.test.ts`
Expected: PASS.

- [ ] **Step 4: 전체 확인 후 커밋하고 PR을 연다**

Run: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test && pnpm --filter web build && pnpm --filter web test:e2e`
Expected: 모두 통과. `format:check`가 실패하면 `pnpm --filter web format`을 돌리고 다시 확인한다.

```bash
git add apps/web/docs/DESIGN_SYSTEM.md apps/web/src/core/styles/tokens.test.ts
git commit -m "docs(web): 디자인 시스템 문서를 새 토큰과 컴포넌트에 맞춘다"
git push -u origin design/components
gh pr create --base design/system-v2 --title "feat(web): 공용 컴포넌트와 디자인 카탈로그" --body "스펙 docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md의 2번 PR. Button을 역할 토큰으로 옮기고 GameButton, IconButton, Chip, TextField, 감정 태그, HP 막대, 몬스터 썸네일과 무대, 상대 시각 함수, /debug/design 카탈로그를 더한다. 카탈로그를 390px과 1440px로 캡처해 붙인다."
```

PR의 CI가 통과하면 사용자에게 병합을 요청한다. 병합된 뒤 화면 개편(스펙 "진행 순서" 3번부터)의 계획을 쓴다.
