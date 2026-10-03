import { describe, expect, it } from "vitest";

import { hpStage } from "./hp-stage";

/**
 * research.md R11: HP 단계는 `full`(66% 초과), `hurt`(33% 초과), `weak`(0 초과),
 * `defeated`(0)의 4단계다. 경계값은 maxHp 10/20/30(강도 낮음/보통/높음)에서
 * 각각 확인한다.
 */
describe("hpStage", () => {
  it("maxHp가 무엇이든 hp가 0이면 defeated다", () => {
    expect(hpStage(0, 10)).toBe("defeated");
    expect(hpStage(0, 20)).toBe("defeated");
    expect(hpStage(0, 30)).toBe("defeated");
  });

  describe("maxHp 10 (강도 낮음)", () => {
    it.each([
      [10, "full"],
      [7, "full"],
      [6, "hurt"],
      [4, "hurt"],
      [3, "weak"],
      [1, "weak"],
      [0, "defeated"],
    ])("hp=%i -> %s", (hp, expected) => {
      expect(hpStage(hp, 10)).toBe(expected);
    });
  });

  describe("maxHp 20 (강도 보통)", () => {
    it.each([
      [20, "full"],
      [14, "full"],
      [13, "hurt"],
      [7, "hurt"],
      [6, "weak"],
      [1, "weak"],
      [0, "defeated"],
    ])("hp=%i -> %s", (hp, expected) => {
      expect(hpStage(hp, 20)).toBe(expected);
    });
  });

  describe("maxHp 30 (강도 높음)", () => {
    it.each([
      [30, "full"],
      [21, "full"],
      [20, "hurt"],
      [11, "hurt"],
      [10, "weak"],
      [1, "weak"],
      [0, "defeated"],
    ])("hp=%i -> %s", (hp, expected) => {
      expect(hpStage(hp, 30)).toBe(expected);
    });
  });
});
