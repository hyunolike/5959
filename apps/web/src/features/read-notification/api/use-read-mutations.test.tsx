import {
  QueryClient,
  QueryClientProvider,
  type InfiniteData,
} from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type {
  Notification,
  NotificationPage,
  UnreadCount,
} from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

import { useMarkAllReadMutation } from "./use-mark-all-read-mutation";
import { useMarkReadMutation } from "./use-mark-read-mutation";

function notification(
  notificationId: number,
  seq: number,
  read = false,
): Notification {
  return {
    notificationId,
    seq,
    type: "POST_COMMENT",
    postId: 10,
    post: { postId: 10, contentPreview: "미리보기" },
    commentId: 1,
    actor: { id: 2, nickname: "오구" },
    actorCount: 1,
    read,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
  };
}

function setup(items: Notification[], unread: UnreadCount) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  client.setQueryData<InfiniteData<NotificationPage>>(
    QUERY_KEYS.notifications,
    { pages: [{ items, nextCursor: null }], pageParams: [null] },
  );
  client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, unread);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

const readFlags = (client: QueryClient) =>
  client
    .getQueryData<InfiniteData<NotificationPage>>(QUERY_KEYS.notifications)
    ?.pages.flatMap((page) => page.items.map((item) => item.read));

const unreadOf = (client: QueryClient) =>
  client.getQueryData<UnreadCount>(QUERY_KEYS.unreadCount);

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

/** 테스트가 풀어 줄 때까지 응답하지 않는 fetch. */
function deferredFetch() {
  let release: (response: Response) => void = () => {};
  const fetchMock = vi.fn(
    () =>
      new Promise<Response>((resolve) => {
        release = resolve;
      }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return { fetchMock, release: (response: Response) => release(response) };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useMarkReadMutation", () => {
  it("US2-AC3 응답 전에 목록과 배지를 바꾸고 PUT /api/notifications/{id}/read를 부른다", async () => {
    const { client, wrapper } = setup(
      [notification(2, 20), notification(1, 10)],
      { count: 2, latestSeq: 20 },
    );
    const { fetchMock, release } = deferredFetch();
    const { result } = renderHook(() => useMarkReadMutation(), { wrapper });

    act(() => result.current.mutate(2));

    await waitFor(() => expect(readFlags(client)).toEqual([true, false]));
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 20 });
    expect(fetchMock).toHaveBeenCalledWith("/api/notifications/2/read", {
      method: "PUT",
    });

    release(new Response(null, { status: 204 }));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(readFlags(client)).toEqual([true, false]);
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 20 });
  });

  it("US2-AC3 실패하면 목록과 배지를 되돌린다", async () => {
    const { client, wrapper } = setup(
      [notification(2, 20), notification(1, 10)],
      { count: 2, latestSeq: 20 },
    );
    const { release } = deferredFetch();
    const { result } = renderHook(() => useMarkReadMutation(), { wrapper });

    act(() => result.current.mutate(2));
    await waitFor(() => expect(readFlags(client)).toEqual([true, false]));

    release(
      json(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "오류" },
        },
        500,
      ),
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(readFlags(client)).toEqual([false, false]);
    expect(unreadOf(client)).toEqual({ count: 2, latestSeq: 20 });
  });

  it("US2-AC6 없는 알림(404)이면 되돌리고 목록을 낡은 것으로 표시해 다시 받게 한다", async () => {
    const { client, wrapper } = setup([notification(1, 10)], {
      count: 1,
      latestSeq: 10,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json(
          {
            success: false,
            data: null,
            error: { code: "NOTIFICATION_NOT_FOUND", message: "없음" },
          },
          404,
        ),
      ),
    );
    const { result } = renderHook(() => useMarkReadMutation(), { wrapper });

    act(() => result.current.mutate(1));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(readFlags(client)).toEqual([false]);
    expect(client.getQueryState(QUERY_KEYS.notifications)?.isInvalidated).toBe(
      true,
    );
  });
});

describe("useMarkAllReadMutation", () => {
  it("US2-AC4 아는 번호 가운데 큰 값을 upToSeq로 보내고, 응답의 안 읽은 수로 배지를 맞춘다", async () => {
    const { client, wrapper } = setup(
      [notification(2, 20), notification(1, 10)],
      { count: 2, latestSeq: 18 },
    );
    const { fetchMock, release } = deferredFetch();
    const { result } = renderHook(() => useMarkAllReadMutation(), { wrapper });

    act(() => result.current.mutate({ lastEventId: 20 }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    expect(fetchMock).toHaveBeenCalledWith("/api/notifications/read-all", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ upToSeq: 20 }),
    });

    // 누르는 사이에 새 알림(번호 21)이 실시간으로 왔다.
    client.setQueryData<InfiniteData<NotificationPage>>(
      QUERY_KEYS.notifications,
      (list) =>
        list && {
          ...list,
          pages: [
            {
              items: [notification(3, 21), ...list.pages[0].items],
              nextCursor: null,
            },
          ],
        },
    );
    client.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, {
      count: 3,
      latestSeq: 21,
    });

    release(
      json({
        success: true,
        data: { updated: 2, unreadCount: 1 },
        error: null,
      }),
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // 번호 20까지만 읽음이 되고 새로 온 알림은 안 읽은 채 남는다.
    expect(readFlags(client)).toEqual([false, true, true]);
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 21 });
  });

  it("US2-AC4 실패하면 목록과 배지를 그대로 둔다", async () => {
    const { client, wrapper } = setup([notification(1, 10)], {
      count: 1,
      latestSeq: 10,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json(
          {
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "오류" },
          },
          500,
        ),
      ),
    );
    const { result } = renderHook(() => useMarkAllReadMutation(), { wrapper });

    act(() => result.current.mutate({ lastEventId: null }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(readFlags(client)).toEqual([false]);
    expect(unreadOf(client)).toEqual({ count: 1, latestSeq: 10 });
  });
});
