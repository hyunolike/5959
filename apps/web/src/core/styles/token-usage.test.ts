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
