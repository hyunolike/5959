import { describe, expect, it } from "vitest";

import { retryWaitMinutes } from "./retry-wait-minutes";

/**
 * US2-AC3, US2-AC4: 429의 retryAfterSeconds를 분 단위로 올림해서 안내한다.
 * 최소 1분은 보여준다(예: 30초 남아도 "0분"이라고 하지 않는다).
 */
describe("retryWaitMinutes", () => {
  it.each([
    [1, 1],
    [30, 1],
    [60, 1],
    [61, 2],
    [900, 15],
    [0, 1],
  ])("%i초는 %i분으로 올림한다", (seconds, minutes) => {
    expect(retryWaitMinutes(seconds)).toBe(minutes);
  });
});
