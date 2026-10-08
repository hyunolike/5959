import { describe, expect, it } from "vitest";

import { resolveUpToSeq } from "./up-to-seq";

describe("resolveUpToSeq", () => {
  it("US2-AC4 목록 첫 항목, 마지막 SSE id, latestSeq 가운데 큰 값을 쓴다", () => {
    expect(
      resolveUpToSeq({ firstItemSeq: 12, lastEventId: 9, latestSeq: 7 }),
    ).toBe(12);
    expect(
      resolveUpToSeq({ firstItemSeq: 12, lastEventId: 15, latestSeq: 7 }),
    ).toBe(15);
    expect(
      resolveUpToSeq({ firstItemSeq: 12, lastEventId: 15, latestSeq: 21 }),
    ).toBe(21);
  });

  it("모르는 값은 빼고 고른다", () => {
    expect(
      resolveUpToSeq({ firstItemSeq: null, lastEventId: null, latestSeq: 4 }),
    ).toBe(4);
    expect(
      resolveUpToSeq({
        firstItemSeq: 6,
        lastEventId: null,
        latestSeq: undefined,
      }),
    ).toBe(6);
  });

  it("아는 값이 하나도 없으면 0이다(아무것도 읽음으로 바꾸지 않는다)", () => {
    expect(
      resolveUpToSeq({
        firstItemSeq: undefined,
        lastEventId: null,
        latestSeq: undefined,
      }),
    ).toBe(0);
  });
});
