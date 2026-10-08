import { describe, expect, it } from "vitest";

import { badgeLabel } from "./badge";

describe("badgeLabel", () => {
  it("0이면 배지를 숨긴다", () => {
    expect(badgeLabel(0)).toBeNull();
  });

  it("1부터 99까지는 숫자 그대로 보인다", () => {
    expect(badgeLabel(1)).toBe("1");
    expect(badgeLabel(42)).toBe("42");
    expect(badgeLabel(99)).toBe("99");
  });

  it("100 이상은 99+로 보인다", () => {
    expect(badgeLabel(100)).toBe("99+");
    expect(badgeLabel(1234)).toBe("99+");
  });
});
