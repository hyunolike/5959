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
