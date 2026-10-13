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
    "512x512",
  ]);
  for (const icon of value.icons ?? []) {
    const file = path.join(process.cwd(), "public", icon.src);
    expect(existsSync(file)).toBe(true);
    // PNG의 IHDR에 적힌 실제 크기가 manifest의 크기와 같다.
    const header = readFileSync(file);
    expect(header.subarray(1, 4).toString("latin1")).toBe("PNG");
    expect(`${header.readUInt32BE(16)}x${header.readUInt32BE(20)}`).toBe(
      icon.sizes,
    );
  }
});
