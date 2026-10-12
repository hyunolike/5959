import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

const SRC = path.resolve(__dirname, "../..");
const BASELINE = path.resolve(__dirname, "legacy-style-files.json");

const COLOR_UTILITY = String.raw`(?:bg|text|border(?:-[trblxyse])?|ring(?:-offset)?|inset-ring|outline|fill|stroke|divide|placeholder|decoration|accent|caret|from|via|to|shadow|inset-shadow)`;
const PALETTE = String.raw`(?:brand|neutral|leaf|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|slate|gray|zinc|stone|white|black)`;

/**
 * 옛 토큰 이름, Tailwind 기본 색, 색을 직접 적은 임의 값, 원색 층을 직접 가리키는 값,
 * 쓰지 않기로 한 굵기와 그림자.
 */
const LEGACY = new RegExp(
  [
    String.raw`\b${COLOR_UTILITY}-${PALETTE}(?:-\d{2,3})?\b`,
    String.raw`\b${COLOR_UTILITY}-\[#[0-9a-fA-F]{3,8}\]`,
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

/** 옛 이름을 쓰는 파일과 그 수. */
const offenders: Record<string, number> = Object.fromEntries(
  sourceFiles(SRC)
    .map(
      (file) =>
        [
          path.relative(SRC, file).split(path.sep).join("/"),
          countLegacy(readFileSync(file, "utf8")),
        ] as const,
    )
    .filter(([, count]) => count > 0)
    .sort(([a], [b]) => a.localeCompare(b)),
);

if (process.env.UPDATE_LEGACY_BASELINE) {
  writeFileSync(BASELINE, `${JSON.stringify(offenders, null, 2)}\n`);
}

const baseline: Record<string, number> = JSON.parse(
  readFileSync(BASELINE, "utf8"),
);

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
  it("DS-US1-AC1 어떤 파일도 옛 색 이름을 목록보다 많이 쓰지 않는다", () => {
    expect(
      Object.entries(offenders).filter(
        ([file, count]) => count > (baseline[file] ?? 0),
      ),
    ).toEqual([]);
  });

  it("DS-US1-AC1 옛 색 이름을 줄인 파일은 목록의 수도 줄인다", () => {
    expect(
      Object.entries(baseline).filter(
        ([file, count]) => (offenders[file] ?? 0) < count,
      ),
    ).toEqual([]);
  });
});
