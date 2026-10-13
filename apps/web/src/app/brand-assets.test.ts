import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import manifest from "./manifest";

const WEB = join(__dirname, "..", "..");
const SRC = join(WEB, "src");
const PUBLIC = join(WEB, "public");

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return sources(path);
    }
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

const code = sources(SRC).map((path) => ({
  path,
  text: readFileSync(path, "utf8"),
}));

describe("브랜드 그림과 아이콘", () => {
  it("DS-US5-AC1 화면이 가리키는 브랜드 그림은 모두 public에 있다", () => {
    const referenced = new Set(
      code.flatMap(({ text }) => text.match(/\/(?:brand|pwa)\/[\w.-]+/g) ?? []),
    );
    expect(referenced.size).toBeGreaterThan(0);
    for (const path of referenced) {
      expect(existsSync(join(PUBLIC, path)), path).toBe(true);
    }
  });

  it("DS-US5-AC1 옛 캐릭터 그림과 보라색 아이콘을 쓰지 않는다", () => {
    for (const { path, text } of code) {
      expect(text, path).not.toMatch(/ogu-companion|icon-(?:192|512)\.svg/);
    }
    expect(existsSync(join(PUBLIC, "brand", "ogu-companion.png"))).toBe(false);
    expect(existsSync(join(SRC, "app", "icon.svg"))).toBe(false);
  });

  it("DS-US5-AC1 앱 아이콘은 PNG이고 가장자리가 잘려도 되는 것을 따로 둔다", () => {
    const icons = manifest().icons ?? [];
    expect(icons.map((icon) => icon.type)).toEqual([
      "image/png",
      "image/png",
      "image/png",
    ]);
    expect(icons.map((icon) => icon.purpose)).toEqual([
      "any",
      "any",
      "maskable",
    ]);
    for (const icon of icons) {
      expect(existsSync(join(PUBLIC, icon.src)), icon.src).toBe(true);
    }
  });

  it("DS-US5-AC1 파비콘은 PNG로 둔다", () => {
    expect(existsSync(join(SRC, "app", "icon.png"))).toBe(true);
  });
});
