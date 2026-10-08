import { QueryClient, type InfiniteData } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import type {
  Notification,
  NotificationPage,
  UnreadCount,
} from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

import {
  knownUpToSeq,
  markAllReadInCache,
  markReadInCache,
} from "./read-cache";

function notification(
  notificationId: number,
  seq: number,
  read = false,
): Notification {
  return {
    notificationId,
    seq,
    type: "POST_LIKE",
    postId: 10,
    post: { postId: 10, contentPreview: "미리보기" },
    commentId: null,
    actor: { id: 2, nickname: "오구" },
    actorCount: 1,
    read,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
  };
}

function seed(
  client: QueryClient,
  pages: Notification[][],
  unread?: UnreadCount,
) {
  client.setQueryData<InfiniteData<NotificationPage>>(
    QUERY_KEYS.notifications,
    {
      pages: pages.map((items) => ({ items, nextCursor: null })),
      pageParams: pages.map((_, index) => (index === 0 ? null : "c")),
    },
  );
  if (unread) {
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, unread);
  }
}

function readFlags(client: QueryClient) {
  return client
    .getQueryData<InfiniteData<NotificationPage>>(QUERY_KEYS.notifications)
    ?.pages.map((page) =>
      page.items.map((item) => [item.notificationId, item.read]),
    );
}

const unreadOf = (client: QueryClient) =>
  client.getQueryData<UnreadCount>(QUERY_KEYS.unreadCount);

describe("markReadInCache", () => {
  it("US2-AC3 그 알림만 읽음으로 바꾸고 안 읽은 수를 하나 줄인다", () => {
    const client = new QueryClient();
    seed(
      client,
      [[notification(3, 30), notification(2, 20)], [notification(1, 10)]],
      {
        count: 3,
        latestSeq: 30,
      },
    );

    markReadInCache(client, 1);

    expect(readFlags(client)).toEqual([
      [
        [3, false],
        [2, false],
      ],
      [[1, true]],
    ]);
    expect(unreadOf(client)).toEqual({ count: 2, latestSeq: 30 });
  });

  it("US2-AC3 되돌리면 그 알림이 다시 안 읽음이 되고 안 읽은 수도 돌아온다", () => {
    const client = new QueryClient();
    seed(client, [[notification(2, 20), notification(1, 10)]], {
      count: 2,
      latestSeq: 20,
    });

    const rollback = markReadInCache(client, 2);
    rollback();

    expect(readFlags(client)).toEqual([
      [
        [2, false],
        [1, false],
      ],
    ]);
    expect(unreadOf(client)).toEqual({ count: 2, latestSeq: 20 });
  });

  it("되돌리기는 그 사이 실시간으로 들어온 알림과 안 읽은 수를 지우지 않는다", () => {
    const client = new QueryClient();
    seed(client, [[notification(1, 10)]], { count: 1, latestSeq: 10 });

    const rollback = markReadInCache(client, 1);
    // 응답을 기다리는 동안 새 알림이 와서 목록 앞에 붙고, 낙관적으로 줄인 0이 1로 늘었다.
    seed(client, [[notification(2, 20), notification(1, 10, true)]], {
      count: 1,
      latestSeq: 20,
    });
    rollback();

    expect(readFlags(client)).toEqual([
      [
        [2, false],
        [1, false],
      ],
    ]);
    expect(unreadOf(client)).toEqual({ count: 2, latestSeq: 20 });
  });

  it("이미 읽은 알림이면 아무것도 바꾸지 않고, 되돌려도 그대로다", () => {
    const client = new QueryClient();
    seed(client, [[notification(1, 10, true)]], { count: 4, latestSeq: 10 });

    const rollback = markReadInCache(client, 1);
    rollback();

    expect(readFlags(client)).toEqual([[[1, true]]]);
    expect(unreadOf(client)).toEqual({ count: 4, latestSeq: 10 });
  });

  it("목록에 없는 알림(아직 목록을 열지 않았다)이면 배지만 하나 줄이고, 되돌리면 돌아온다", () => {
    const client = new QueryClient();
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 20,
    });

    const rollback = markReadInCache(client, 2);

    expect(readFlags(client)).toBeUndefined();
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 20 });

    rollback();

    expect(unreadOf(client)).toEqual({ count: 2, latestSeq: 20 });
  });

  it("안 읽은 수는 0 아래로 내려가지 않고, 캐시가 없으면 만들지 않는다", () => {
    const client = new QueryClient();
    seed(client, [[notification(1, 10)]], { count: 0, latestSeq: 10 });

    markReadInCache(client, 1);

    expect(unreadOf(client)).toEqual({ count: 0, latestSeq: 10 });

    const empty = new QueryClient();
    seed(empty, [[notification(1, 10)]]);

    markReadInCache(empty, 1);

    expect(unreadOf(empty)).toBeUndefined();
  });
});

describe("markAllReadInCache", () => {
  it("US2-AC4 upToSeq 이하만 읽음으로 바꾸고 그 뒤에 온 알림은 안 읽은 채 둔다", () => {
    const client = new QueryClient();
    seed(
      client,
      [[notification(4, 40), notification(3, 30)], [notification(1, 10)]],
      { count: 3, latestSeq: 40 },
    );

    markAllReadInCache(client, 30, 1);

    expect(readFlags(client)).toEqual([
      [
        [4, false],
        [3, true],
      ],
      [[1, true]],
    ]);
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 40 });
  });
});

describe("knownUpToSeq", () => {
  it("US2-AC4 캐시의 목록 첫 항목과 latestSeq, 넘겨받은 마지막 SSE id 가운데 큰 값이다", () => {
    const client = new QueryClient();
    seed(client, [[notification(2, 20), notification(1, 10)]], {
      count: 2,
      latestSeq: 18,
    });

    expect(knownUpToSeq(client, null)).toBe(20);
    expect(knownUpToSeq(client, 25)).toBe(25);

    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 31,
    });

    expect(knownUpToSeq(client, 25)).toBe(31);
  });

  it("캐시가 비어 있으면 넘겨받은 값만으로 정한다", () => {
    expect(knownUpToSeq(new QueryClient(), 7)).toBe(7);
    expect(knownUpToSeq(new QueryClient(), null)).toBe(0);
  });
});
