import { expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import manifest from "./manifest";

it("개인 정보 없는 설치 manifest의 시작 경로와 공개 아이콘 크기가 정합하다", () => {
  const value = manifest();
  expect(value).toMatchObject({
    id: "/",
    start_url: "/home",
    scope: "/",
    display: "standalone",
    prefer_related_applications: false,
  });
  expect(value.icons?.map((icon) => icon.sizes)).toEqual([
    "192x192",
    "512x512",
  ]);
  for (const icon of value.icons ?? []) {
    const file = path.join(process.cwd(), "public", icon.src);
    expect(existsSync(file)).toBe(true);
    const size = icon.sizes?.split("x")[0];
    expect(readFileSync(file, "utf8")).toContain(
      `width="${size}" height="${size}"`,
    );
  }
});
