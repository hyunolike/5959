import { describe, expect, it } from "vitest";

import { resolveMaintenanceAction } from "./maintenance";

describe("resolveMaintenanceAction", () => {
  it("첫 화면은 그대로 연다. 이사 안내는 첫 화면이 그린다", () => {
    expect(resolveMaintenanceAction("/")).toBeNull();
  });

  it("그 밖의 화면은 첫 화면으로 보낸다. API가 꺼져 있어 열어도 오류만 난다", () => {
    for (const path of [
      "/home",
      "/login",
      "/signup",
      "/raid",
      "/my",
      "/onboarding",
      "/write",
    ]) {
      expect(resolveMaintenanceAction(path)).toEqual({
        type: "redirect",
        to: "/",
      });
    }
  });

  it("공유받은 글 링크로 왔으면 그 사실을 첫 화면에 넘긴다", () => {
    expect(resolveMaintenanceAction("/post/123")).toEqual({
      type: "redirect",
      to: "/?from=post",
    });
    expect(resolveMaintenanceAction("/post/123/edit")).toEqual({
      type: "redirect",
      to: "/",
    });
  });

  it("public의 파일(그림, 서비스 워커, 로딩 화면, robots)은 그대로 내준다", () => {
    for (const path of [
      "/monsters/anxiety-full.webp",
      "/brand/ogu-hug.webp",
      "/sw.js",
      "/cold-start.html",
      "/robots.txt",
      "/manifest.webmanifest",
    ]) {
      expect(resolveMaintenanceAction(path)).toBeNull();
    }
  });
});
