import { describe, expect, it } from "vitest";

import { newestUnreadCount } from "./unread";

describe("newestUnreadCount", () => {
  it("캐시가 없으면 받은 값을 쓴다", () => {
    expect(newestUnreadCount(undefined, { count: 2, latestSeq: 7 })).toEqual({
      count: 2,
      latestSeq: 7,
    });
  });

  it("받은 값의 마지막 번호가 캐시와 같거나 크면 받은 값을 쓴다", () => {
    const cached = { count: 3, latestSeq: 7 };
    expect(newestUnreadCount(cached, { count: 0, latestSeq: 7 })).toEqual({
      count: 0,
      latestSeq: 7,
    });
    expect(newestUnreadCount(cached, { count: 4, latestSeq: 8 })).toEqual({
      count: 4,
      latestSeq: 8,
    });
  });

  it("받은 값의 마지막 번호가 캐시보다 작으면(늦게 도착한 옛 응답) 캐시를 그대로 둔다", () => {
    const cached = { count: 3, latestSeq: 9 };

    expect(newestUnreadCount(cached, { count: 2, latestSeq: 8 })).toBe(cached);
  });
});
