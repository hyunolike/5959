import { describe, expect, it } from "vitest";

import { loginDestination } from "./login-destination";

describe("loginDestination", () => {
  it("US4-AC5 온보딩을 마친 회원은 검증한 next로 돌아간다", () => {
    expect(loginDestination(true, "/my")).toBe("/my");
  });

  it("next가 없으면 /home이다", () => {
    expect(loginDestination(true, undefined)).toBe("/home");
  });

  it.each(["//evil.example", "https://evil.example", "/%2F%2Fevil.example"])(
    "외부 주소 next(%j)는 /home으로 바꾼다",
    (next) => {
      expect(loginDestination(true, next)).toBe("/home");
    },
  );

  it("온보딩 전이면 next를 들고 /onboarding으로 간다", () => {
    expect(loginDestination(false, "/my")).toBe("/onboarding?next=%2Fmy");
    expect(loginDestination(false, undefined)).toBe("/onboarding");
  });
});
