import { describe, expect, it } from "vitest";

import { DEFAULT_NEXT_PATH, sanitizeNextPath, withNextPath } from "./next-path";

describe("sanitizeNextPath", () => {
  it.each([
    ["/home", "/home"],
    ["/my", "/my"],
    ["/my/posts?tab=1", "/my/posts?tab=1"],
    ["/write#draft", "/write#draft"],
    ["/search?q=%EC%98%A4%EA%B5%AC", "/search?q=%EC%98%A4%EA%B5%AC"],
  ])("같은 출처 경로 %j는 그대로 쓴다", (next, expected) => {
    expect(sanitizeNextPath(next)).toBe(expected);
  });

  it.each([
    [null],
    [undefined],
    [""],
    ["home"],
    ["//evil.example"],
    ["/\\evil.example"],
    ["https://evil.example/home"],
    ["javascript:alert(1)"],
    ["/\t/evil.example"],
    ["/\n/evil.example"],
    [" /home"],
    [`/${"a".repeat(1024)}`],
    // 한 번 디코딩하면 `//`, `/\`가 되는 값. 다른 단계(프록시, 리다이렉트 정규화)가
    // 디코딩하면 프로토콜 상대 주소가 될 수 있어 막는다.
    ["/%2F%2Fevil.example"],
    ["/%2fevil.example"],
    ["/%5Cevil.example"],
    ["/%5cevil.example"],
    ["/%09/evil.example"],
    ["/%E0%A4%A"],
  ])("안전하지 않은 값 %j는 /home으로 바꾼다", (next) => {
    expect(sanitizeNextPath(next)).toBe(DEFAULT_NEXT_PATH);
  });

  it("기본값은 /home이다", () => {
    expect(DEFAULT_NEXT_PATH).toBe("/home");
  });
});

describe("withNextPath", () => {
  it("검증한 next를 쿼리로 붙인다", () => {
    expect(withNextPath("/onboarding", "/my?tab=1")).toBe(
      `/onboarding?next=${encodeURIComponent("/my?tab=1")}`,
    );
    expect(withNextPath("/login?error=oauth_failed", "/my")).toBe(
      "/login?error=oauth_failed&next=%2Fmy",
    );
  });

  it.each([null, undefined, "/home", "//evil.example", "/%5Cevil"])(
    "next가 %j면 붙이지 않는다",
    (next) => {
      expect(withNextPath("/onboarding", next)).toBe("/onboarding");
    },
  );
});
