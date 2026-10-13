import { createHash } from "node:crypto";
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

  it("LOGO-AC3 옛 로고와 옛 아이콘 파일은 남기지 않는다", () => {
    for (const path of [
      "brand/ogu-wordmark.svg",
      "brand/ogu-symbol.webp",
      "brand/apple-touch-icon.png",
      "brand/ogu-lettering-v1-apple-touch-180.png",
      "pwa/icon-192.png",
      "pwa/icon-512.png",
      "pwa/icon-maskable-512.png",
      "pwa/ogu-lettering-v1-192.png",
      "pwa/ogu-lettering-v1-512.png",
      "pwa/ogu-lettering-v1-maskable-512.png",
    ]) {
      expect(existsSync(join(PUBLIC, path)), path).toBe(false);
    }
    for (const { path, text } of code) {
      expect(text, path).not.toMatch(/ogu-wordmark|ogu-symbol|ogu-lettering/);
    }
  });

  it("LOGO-AC4 로고는 글꼴과 그림 파일에 기대지 않는 SVG다", () => {
    for (const name of ["ogu-logo.svg", "ogu-logo-symbol.svg"]) {
      const svg = readFileSync(join(PUBLIC, "brand", name), "utf8");
      expect(svg, name).not.toMatch(/<text|font-family|<image|<style|href=/);
      // 화면에 브랜드 색을 두지 않는다. 잉크와 잎의 초록뿐이고 보라색(불안의 색)은 없다.
      const colors = new Set(svg.match(/#[0-9a-fA-F]{6}/g));
      expect([...colors].sort(), name).toEqual(["#17171c", "#3fa96b"]);
    }
  });

  it("LOGO-AC5 공유 이미지는 1200×630 PNG다", () => {
    const header = readFileSync(join(SRC, "app", "opengraph-image.png"));
    expect(header.subarray(1, 4).toString("latin1")).toBe("PNG");
    expect(`${header.readUInt32BE(16)}x${header.readUInt32BE(20)}`).toBe(
      "1200x630",
    );
  });

  it("LOGO-AC2 파비콘은 96px, 애플 아이콘은 180px이다", () => {
    for (const [file, size] of [
      [join(SRC, "app", "icon.png"), "96x96"],
      [join(PUBLIC, "brand", "ogu-logo-v2-apple-touch-180.png"), "180x180"],
    ] as const) {
      const header = readFileSync(file);
      expect(`${header.readUInt32BE(16)}x${header.readUInt32BE(20)}`).toBe(
        size,
      );
    }
  });

  it("LOGO-AC2 원본 SVG를 고쳤으면 PNG를 다시 만들어야 한다", () => {
    // 만드는 스크립트가 그때의 원본 해시를 적어 둔다. 원본만 고치고 다시 만들지 않으면 여기서 걸린다.
    const recorded = JSON.parse(
      readFileSync(join(WEB, "scripts", "brand", "sources.json"), "utf8"),
    ) as Record<string, string>;
    const names = [
      "app-icon-maskable.svg",
      "app-icon.svg",
      "icon.svg",
      "og-image.svg",
    ];
    expect(Object.keys(recorded).sort()).toEqual(names);
    for (const name of names) {
      const hash = createHash("sha256")
        .update(readFileSync(join(WEB, "scripts", "brand", name)))
        .digest("hex");
      expect(recorded[name], name).toBe(hash);
    }
  });

  it("LOGO-AC2 아이콘의 원본 SVG는 scripts/brand에 있고 만드는 스크립트가 읽는다", () => {
    const script = readFileSync(
      join(WEB, "scripts", "generate-brand-icons.mjs"),
      "utf8",
    );
    for (const name of [
      "icon.svg",
      "app-icon.svg",
      "app-icon-maskable.svg",
      "og-image.svg",
    ]) {
      expect(existsSync(join(WEB, "scripts", "brand", name)), name).toBe(true);
      expect(script, name).toContain(name);
    }
  });
});
