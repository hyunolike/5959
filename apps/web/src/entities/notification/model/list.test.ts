import { describe, expect, it } from "vitest";

import { uniqueNotifications } from "./list";

const item = (notificationId: number, seq: number) => ({ notificationId, seq });

describe("uniqueNotifications", () => {
  it("쪽들을 순서대로 한 목록으로 펼친다", () => {
    expect(
      uniqueNotifications([
        { items: [item(3, 30), item(2, 20)] },
        { items: [item(1, 10)] },
      ]),
    ).toEqual([item(3, 30), item(2, 20), item(1, 10)]);
  });

  it("US2-AC2 같은 알림이 두 쪽에 있으면 번호가 큰 것 하나만 앞쪽 자리에 남긴다", () => {
    // 공감 묶음이 쪽 사이에 갱신되면 같은 알림이 새 번호로 앞쪽에 다시 온다.
    expect(
      uniqueNotifications([
        { items: [item(2, 40), item(3, 30)] },
        { items: [item(2, 20), item(1, 10)] },
      ]),
    ).toEqual([item(2, 40), item(3, 30), item(1, 10)]);
    expect(
      uniqueNotifications([
        { items: [item(2, 20), item(3, 15)] },
        { items: [item(2, 40), item(1, 10)] },
      ]),
    ).toEqual([item(2, 40), item(3, 15), item(1, 10)]);
  });

  it("쪽이 없으면 빈 목록이다", () => {
    expect(uniqueNotifications([])).toEqual([]);
  });
});
