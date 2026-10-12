import { describe, expect, it } from "vitest";
import { classifyInstallEnvironment, promotionAllowed } from "./platform";

const browser = {
  userAgent: "Chrome/140",
  platform: "Linux",
  maxTouchPoints: 0,
  secure: true,
  standalone: false,
};

describe("install environment", () => {
  it("iPhone과 데스크톱 모드 iPad의 수동 설치 안내를 구분한다", () => {
    expect(
      classifyInstallEnvironment({
        ...browser,
        userAgent: "iPhone OS 26_0 Safari",
      }).platform,
    ).toBe("ios");
    expect(
      classifyInstallEnvironment({
        ...browser,
        platform: "MacIntel",
        maxTouchPoints: 5,
      }).platform,
    ).toBe("ios");
  });
  it("인앱과 비보안, standalone에서는 설치 홍보를 하지 않는다", () => {
    for (const input of [
      { ...browser, userAgent: "Android Instagram" },
      { ...browser, secure: false },
      { ...browser, standalone: true },
    ]) {
      expect(
        promotionAllowed(classifyInstallEnvironment(input), true, 0, 100),
      ).toBe(false);
    }
  });
  it("브라우저 프롬프트가 없는 일반 환경은 직접 여는 안내만 제공한다", () => {
    expect(
      promotionAllowed(classifyInstallEnvironment(browser), false, 0, 100),
    ).toBe(false);
    expect(
      promotionAllowed(classifyInstallEnvironment(browser), true, 0, 100),
    ).toBe(true);
  });
  it("닫은 홍보는 만료 전 숨기고 만료 후 다시 제공할 수 있다", () => {
    const ios = classifyInstallEnvironment({
      ...browser,
      userAgent: "iPhone Safari",
    });
    expect(promotionAllowed(ios, false, 200, 100)).toBe(false);
    expect(promotionAllowed(ios, false, 200, 201)).toBe(true);
  });
});
