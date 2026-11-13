import { describe, expect, it } from "vitest";

import { nightAmount } from "./runner-sky";

describe("산책의 낮과 밤", () => {
  it.each([
    [0, 0],
    [19, 0],
    [20, 0],
    [22, 0.5],
    [24, 1],
    [43, 1],
    [44, 1],
    [46, 0.5],
    [48, 0],
    [70, 0.5],
  ])("%s초의 하늘이 주기를 따라 바뀐다", (elapsed, expected) => {
    expect(nightAmount(elapsed)).toBeCloseTo(expected, 8);
  });

  it("색이 갑자기 튀지 않고 전환 구간에서 연속적으로 바뀐다", () => {
    expect(nightAmount(20.01)).toBeLessThan(0.001);
    expect(nightAmount(23.99)).toBeGreaterThan(0.999);
    expect(nightAmount(47.99)).toBeLessThan(0.001);
  });

  it("움직임 줄이기에서는 중간 색 없이 전환하고 새 판은 낮이다", () => {
    expect(nightAmount(22, true)).toBe(0);
    expect(nightAmount(24, true)).toBe(1);
    expect(nightAmount(46, true)).toBe(1);
    expect(nightAmount(48, true)).toBe(0);
    expect(nightAmount(0, true)).toBe(0);
  });
});
