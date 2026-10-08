import { describe, expect, it } from "vitest";

import { backoffDelayMs } from "./backoff";

/** 흔들기가 0이 되는 난수(가운데 값). */
const noJitter = () => 0.5;

describe("backoffDelayMs", () => {
  it("1초, 2초, 4초로 두 배씩 늘어난다", () => {
    expect(backoffDelayMs(0, noJitter)).toBe(1000);
    expect(backoffDelayMs(1, noJitter)).toBe(2000);
    expect(backoffDelayMs(2, noJitter)).toBe(4000);
    expect(backoffDelayMs(3, noJitter)).toBe(8000);
  });

  it("최대 30초에서 멈춘다", () => {
    expect(backoffDelayMs(4, noJitter)).toBe(16000);
    expect(backoffDelayMs(5, noJitter)).toBe(30000);
    expect(backoffDelayMs(50, noJitter)).toBe(30000);
  });

  it("±20% 안에서 흔든다", () => {
    expect(backoffDelayMs(0, () => 0)).toBe(800);
    expect(backoffDelayMs(0, () => 1)).toBe(1200);
    expect(backoffDelayMs(5, () => 0)).toBe(24000);
    expect(backoffDelayMs(5, () => 1)).toBe(36000);
    for (let i = 0; i < 100; i += 1) {
      const delay = backoffDelayMs(2);
      expect(delay).toBeGreaterThanOrEqual(3200);
      expect(delay).toBeLessThanOrEqual(4800);
    }
  });

  it("열려서 시도 횟수가 0으로 돌아가면 다시 1초부터 기다린다", () => {
    expect(backoffDelayMs(6, noJitter)).toBe(30000);
    expect(backoffDelayMs(0, noJitter)).toBe(1000);
  });
});
