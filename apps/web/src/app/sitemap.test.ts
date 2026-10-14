import { describe, expect, it, vi } from "vitest";

vi.mock("@/shared/config", () => ({
  env: { APP_ORIGIN: "https://ogu.example" },
}));

import sitemap from "./sitemap";

describe("sitemap.xml", () => {
  it("SEO-AC5 첫 화면을 서비스 주소의 절대 경로로 싣고 회원의 글이 보이는 화면은 싣지 않는다", () => {
    const urls = sitemap().map((entry) => entry.url);
    expect(urls[0]).toBe("https://ogu.example/");
    expect(urls).toContain("https://ogu.example/ai-friends");
    for (const path of ["/home", "/login", "/post", "/write"]) {
      expect(urls.some((url) => url.endsWith(path))).toBe(false);
    }
  });
});
