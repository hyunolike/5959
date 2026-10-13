import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

const SRC = path.resolve(__dirname, "../..");

const COLOR_UTILITY = String.raw`(?:bg|text|border(?:-[trblxyse])?|ring(?:-offset)?|inset-ring|outline|fill|stroke|divide|placeholder|decoration|accent|caret|from|via|to|shadow|inset-shadow)`;
const PALETTE = String.raw`(?:brand|neutral|leaf|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|slate|gray|zinc|stone|mauve|olive|mist|taupe|white|black)`;

/**
 * 옛 토큰 이름, Tailwind 기본 색, 색을 직접 적은 임의 값, 원색 층을 직접 가리키는 값,
 * 쓰지 않기로 한 굵기와 그림자.
 */
const LEGACY = new RegExp(
  [
    String.raw`\b${COLOR_UTILITY}-${PALETTE}(?:-\d{2,3})?\b`,
    String.raw`\b${COLOR_UTILITY}-\[(?:#[0-9a-fA-F]{3,8}|(?:rgb|hsl|oklch|oklab|color)a?\(|var\(--(?:ink-\d+|white|game-yellow|danger-\d+|warning-\d+)\))`,
    String.raw`\b${COLOR_UTILITY}-\(--(?:ink-\d+|white|game-yellow|danger-\d+|warning-\d+)\)`,
    String.raw`\bfont-(?:thin|extralight|light|semibold|black)\b`,
    String.raw`\bfont-\[\d+\]`,
    String.raw`\bshadow-(?:2xs|xs|sm|md|lg|xl|2xl|inner|surface)\b`,
    String.raw`\bshadow-\[`,
  ].join("|"),
  "g",
);

function countLegacy(source: string): number {
  return source.match(LEGACY)?.length ?? 0;
}

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

const sources = sourceFiles(SRC).map((file) => ({
  path: path.relative(SRC, file).split(path.sep).join("/"),
  text: readFileSync(file, "utf8"),
}));

describe("옛 색 이름을 찾는 규칙", () => {
  it.each([
    "bg-neutral-100",
    "hover:text-brand-700",
    "border-b-neutral-200",
    "border-l-brand-600",
    "ring-offset-white",
    "shadow-brand-600",
    "text-violet-600",
    "bg-purple-50",
    "bg-indigo-500",
    "text-sky-700",
    "bg-[#7252d6]",
    "text-(--ink-900)",
    "font-semibold",
    "font-[600]",
    "shadow-lg",
    "shadow-xs",
    "shadow-[0_2px_4px_#000]",
    "bg-mauve-500",
    "text-olive-700",
    "bg-mist-100",
    "border-taupe-300",
    "bg-[rgb(118,85,180)]",
    "text-[var(--ink-900)]",
  ])("DS-US1-AC1 %s를 옛 이름으로 잡는다", (className) => {
    expect(countLegacy(`className="flex ${className} gap-2"`)).toBe(1);
  });

  it.each([
    "bg-surface",
    "text-fg-muted",
    "border-line-strong",
    "bg-emotion-tint",
    "shadow-game",
    "shadow-none",
    "font-bold",
    "text-body",
    "border-2",
    "rounded-control",
  ])("DS-US1-AC1 %s는 잡지 않는다", (className) => {
    expect(countLegacy(`className="flex ${className} gap-2"`)).toBe(0);
  });
});

describe("옛 색 이름", () => {
  it("DS-US1-AC1 소스에 옛 색 이름과 Tailwind 기본 색이 하나도 없다", () => {
    expect(
      sources
        .map(({ path: file, text }) => [file, countLegacy(text)] as const)
        .filter(([, count]) => count > 0),
    ).toEqual([]);
  });

  it("DS-US1-AC1 Lucide 아이콘을 가져오는 파일이 없다", () => {
    expect(
      sources
        .filter(({ text }) => text.includes("lucide-react"))
        .map(({ path: file }) => file),
    ).toEqual([]);
  });
});

/** 게임 버튼을 쓸 수 있는 곳: 글 상세의 공감, 레이드의 공격, 그리고 카탈로그. */
const GAME_BUTTON_USERS = [
  "app/debug/design/page.tsx",
  "features/like/ui/post-like-button.tsx",
  "features/raid-attack/ui/attack-button.tsx",
];

describe("게임 요소를 쓰는 곳", () => {
  it("DS-US3-AC2 게임 버튼은 정해진 파일에서만 쓴다", () => {
    expect(
      sources
        .filter(
          ({ path: file, text }) =>
            !file.startsWith("shared/ui/") && /\bGameButton\b/.test(text),
        )
        .map(({ path: file }) => file)
        .sort(),
    ).toEqual(GAME_BUTTON_USERS);
  });

  it("DS-US3-AC4 위기 안내와 신고, 재검토 요청은 게임 요소와 감정 색을 쓰지 않는다", () => {
    const guarded = sources.filter(({ path: file }) =>
      /safety-notice|support-resource|report-content|request-review/.test(file),
    );
    expect(guarded.length).toBeGreaterThan(3);
    expect(
      guarded
        .filter(({ text }) =>
          /GameButton|bg-game|shadow-game|emotion|MonsterStage/.test(text),
        )
        .map(({ path: file }) => file),
    ).toEqual([]);
  });
});
