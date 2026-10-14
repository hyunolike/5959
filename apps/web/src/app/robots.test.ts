import { describe, expect, it, vi } from "vitest";

vi.mock("@/shared/config", () => ({
  env: { APP_ORIGIN: "https://ogu.example" },
}));

import robots, { DISALLOWED_PATHS } from "./robots";

describe("robots.txt", () => {
  it("SEO-AC4 로그인해야 열리는 경로와 BFF는 긁지 않게 하고 sitemap 주소를 알린다", () => {
    const value = robots();
    expect(value.sitemap).toBe("https://ogu.example/sitemap.xml");
    expect(value.rules).toEqual({
      userAgent: "*",
      allow: "/",
      disallow: DISALLOWED_PATHS,
    });
    for (const path of ["/api/", "/write", "/post/", "/my", "/debug/"]) {
      expect(DISALLOWED_PATHS).toContain(path);
    }
  });

  it("SEO-AC4 noindex로 다루는 화면(홈, 로그인, 가입)은 막지 않는다", () => {
    for (const path of ["/home", "/login", "/signup"]) {
      expect(DISALLOWED_PATHS).not.toContain(path);
    }
  });
});
