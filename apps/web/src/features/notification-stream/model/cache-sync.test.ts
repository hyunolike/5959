import { QueryClient, type InfiniteData } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";

import {
  unreadCountQueryOptions,
  type Notification,
  type NotificationPage,
  type UnreadCount,
} from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

import { applyNotificationEvent, applyUnreadCountEvent } from "./cache-sync";

function notification(
  notificationId: number,
  seq: number,
  overrides: Partial<Notification> = {},
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
    read: false,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
    ...overrides,
  };
}

function listCache(client: QueryClient) {
  return client.getQueryData<InfiniteData<NotificationPage>>(
    QUERY_KEYS.notifications,
  );
}

function seedList(client: QueryClient, pages: NotificationPage[]) {
  client.setQueryData<InfiniteData<NotificationPage>>(
    QUERY_KEYS.notifications,
    { pages, pageParams: pages.map((_, index) => (index === 0 ? null : "c")) },
  );
}

describe("applyNotificationEvent", () => {
  it("US1-AC1 새 알림을 목록 첫 쪽 맨 앞에 넣고 안 읽은 수를 unreadCount로 바꾼다", () => {
    const client = new QueryClient();
    seedList(client, [
      { items: [notification(1, 5), notification(2, 4)], nextCursor: "c" },
    ]);
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 5,
    });

    applyNotificationEvent(client, {
      notification: notification(3, 6),
      unreadCount: 3,
    });

    expect(
      listCache(client)?.pages[0].items.map((item) => item.notificationId),
    ).toEqual([3, 1, 2]);
    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 3,
      latestSeq: 6,
    });
  });

  it("US1-AC2 같은 묶음이 새 번호로 다시 오면 기존 항목을 지우고 맨 앞에 하나만 둔다", () => {
    const client = new QueryClient();
    seedList(client, [
      { items: [notification(1, 5), notification(2, 4)], nextCursor: "c" },
      { items: [notification(7, 2)], nextCursor: null },
    ]);
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 5,
    });

    applyNotificationEvent(client, {
      notification: notification(2, 6, { actorCount: 3 }),
      unreadCount: 2,
    });

    const pages = listCache(client)?.pages ?? [];
    expect(pages[0].items.map((item) => item.notificationId)).toEqual([2, 1]);
    expect(pages[0].items[0]).toMatchObject({ seq: 6, actorCount: 3 });
    expect(pages[1].items.map((item) => item.notificationId)).toEqual([7]);
    // 묶음 갱신은 안 읽은 수를 늘리지 않는다. 서버가 준 값을 그대로 쓴다.
    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 2,
      latestSeq: 6,
    });
  });

  it("US1-AC2 뒤쪽 쪽에 있던 같은 알림도 지우고 첫 쪽 맨 앞으로 올린다", () => {
    const client = new QueryClient();
    seedList(client, [
      { items: [notification(1, 5)], nextCursor: "c" },
      { items: [notification(7, 2), notification(8, 1)], nextCursor: null },
    ]);

    applyNotificationEvent(client, {
      notification: notification(7, 6),
      unreadCount: 2,
    });

    const pages = listCache(client)?.pages ?? [];
    expect(pages[0].items.map((item) => item.notificationId)).toEqual([7, 1]);
    expect(pages[1].items.map((item) => item.notificationId)).toEqual([8]);
  });

  it("목록을 아직 불러오지 않았으면 목록 캐시를 만들지 않고 안 읽은 수만 바꾼다", () => {
    const client = new QueryClient();

    applyNotificationEvent(client, {
      notification: notification(3, 6),
      unreadCount: 1,
    });

    expect(listCache(client)).toBeUndefined();
    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 1,
      latestSeq: 6,
    });
  });

  it("마지막 번호는 뒤로 가지 않는다", () => {
    const client = new QueryClient();
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 9,
    });

    applyNotificationEvent(client, {
      notification: notification(3, 6),
      unreadCount: 3,
    });

    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 3,
      latestSeq: 9,
    });
  });
});

describe("안 읽은 수 조회와 이벤트의 경합", () => {
  it("조회가 진행 중일 때 온 이벤트를 늦게 도착한 옛 응답이 덮지 않는다", async () => {
    const client = new QueryClient();
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 5,
    });
    let respond: (response: Response) => void = () => {};
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            respond = resolve;
          }),
      ),
    );

    // 서버가 번호 5까지 본 응답을 만드는 사이에 번호 6 알림이 이벤트로 먼저 온다.
    const fetching = client.fetchQuery({
      ...unreadCountQueryOptions(),
      staleTime: 0,
    });
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled());
    applyNotificationEvent(client, {
      notification: notification(3, 6),
      unreadCount: 3,
    });
    respond(
      new Response(
        JSON.stringify({
          success: true,
          data: { count: 2, latestSeq: 5 },
          error: null,
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
    await fetching;

    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 3,
      latestSeq: 6,
    });
    vi.unstubAllGlobals();
  });
});

describe("applyUnreadCountEvent", () => {
  it("US1-AC7 unread-count 이벤트는 배지 숫자만 바꾸고 목록과 마지막 번호는 그대로 둔다", () => {
    const client = new QueryClient();
    const pages = [
      { items: [notification(1, 5), notification(2, 4)], nextCursor: null },
    ];
    seedList(client, pages);
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 2,
      latestSeq: 5,
    });

    applyUnreadCountEvent(client, { unreadCount: 0 });

    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toEqual({
      count: 0,
      latestSeq: 5,
    });
    expect(listCache(client)?.pages).toEqual(pages);
  });

  it("안 읽은 수를 아직 불러오지 않았으면 캐시를 만들지 않는다", () => {
    const client = new QueryClient();

    applyUnreadCountEvent(client, { unreadCount: 4 });

    expect(client.getQueryData(QUERY_KEYS.unreadCount)).toBeUndefined();
  });
});
