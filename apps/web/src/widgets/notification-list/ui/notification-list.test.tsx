import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Notification } from "@/entities/notification";
import { notificationStreamStore } from "@/features/notification-stream";

import { NotificationList } from "./notification-list";

const observers: { callback: IntersectionObserverCallback }[] = [];

class FakeIntersectionObserver {
  constructor(callback: IntersectionObserverCallback) {
    observers.push({ callback });
  }
  observe() {}
  disconnect() {}
  unobserve() {}
}

function notification(
  notificationId: number,
  seq: number,
  overrides: Partial<Notification> = {},
): Notification {
  return {
    notificationId,
    seq,
    type: "POST_COMMENT",
    postId: 100 + notificationId,
    post: {
      postId: 100 + notificationId,
      contentPreview: `글 앞부분 ${notificationId}`,
    },
    commentId: 1,
    actor: { id: 2, nickname: `회원${notificationId}` },
    actorCount: 1,
    reportWeekStart: null,
    read: false,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
    ...overrides,
  };
}

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, error: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

interface Routes {
  /** 목록 요청마다 차례로 돌려줄 쪽. 마지막 것은 되풀이한다. */
  pages: { items: Notification[]; nextCursor: string | null }[];
  unread?: { count: number; latestSeq: number };
  readAll?: { updated: number; unreadCount: number };
}

/** 주소와 메서드로 응답을 고르는 fetch. 부른 기록은 `calls`에 남긴다. */
function stubApi(routes: Routes) {
  const calls: { url: string; method: string; body?: string }[] = [];
  let listCalls = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      calls.push({ url, method, body: init?.body as string | undefined });
      if (url === "/api/notifications/unread-count") {
        return ok(routes.unread ?? { count: 0, latestSeq: 0 });
      }
      if (url === "/api/notifications/read-all") {
        return ok(routes.readAll ?? { updated: 0, unreadCount: 0 });
      }
      if (method === "PUT") {
        return new Response(null, { status: 204 });
      }
      const page = routes.pages[Math.min(listCalls, routes.pages.length - 1)];
      listCalls += 1;
      return ok(page);
    }),
  );
  return calls;
}

function renderList() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <NotificationList />
    </QueryClientProvider>,
  );
  return queryClient;
}

function reachEnd() {
  observers
    .at(-1)!
    .callback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    );
}

beforeEach(() => {
  observers.length = 0;
  vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
  notificationStreamStore.setState({ lastEventId: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
  notificationStreamStore.setState({ lastEventId: null });
});

describe("NotificationList", () => {
  it("US2-AC1 알림을 받은 순서대로 목록으로 보여 주고 항목마다 문구, 글 앞부분, 읽음 여부가 있다", async () => {
    stubApi({
      pages: [
        {
          items: [notification(2, 20), notification(1, 10, { read: true })],
          nextCursor: null,
        },
      ],
      unread: { count: 1, latestSeq: 20 },
    });

    renderList();

    const list = await screen.findByRole("list", { name: "알림" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("회원2 님이 내 글에 댓글을 남겼어요");
    expect(items[0]).toHaveTextContent("글 앞부분 2");
    expect(items[0]).toHaveTextContent("안 읽음");
    expect(items[1]).toHaveTextContent("회원1 님이 내 글에 댓글을 남겼어요");
    expect(items[1]).not.toHaveTextContent("안 읽음");
    expect(within(items[0]).getByRole("link")).toHaveAttribute(
      "href",
      "/post/102",
    );
  });

  it("US2-AC2 목록 끝이 보이면 다음 쪽을 이어 붙이고 같은 알림은 한 번만 보인다", async () => {
    const calls = stubApi({
      pages: [
        {
          items: [notification(3, 30), notification(2, 20)],
          nextCursor: "NEXT",
        },
        { items: [notification(2, 20), notification(1, 10)], nextCursor: null },
      ],
      unread: { count: 3, latestSeq: 30 },
    });

    renderList();
    await screen.findByText("글 앞부분 3");

    reachEnd();

    expect(await screen.findByText("글 앞부분 1")).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    expect(screen.getAllByText("글 앞부분 2")).toHaveLength(1);
    expect(calls.map((call) => call.url)).toContain(
      "/api/notifications?cursor=NEXT",
    );
  });

  it("US2-AC3 안 읽은 알림을 누르면 읽음 요청을 보내고 바로 읽음으로 보인다", async () => {
    const calls = stubApi({
      pages: [{ items: [notification(1, 10)], nextCursor: null }],
      unread: { count: 1, latestSeq: 10 },
    });

    renderList();
    const link = await screen.findByRole("link");
    // jsdom은 이동하지 않는다. 링크의 기본 동작만 막아 둔다.
    link.addEventListener("click", (event) => event.preventDefault());

    fireEvent.click(link);

    await waitFor(() => expect(link).not.toHaveTextContent("안 읽음"));
    expect(calls).toContainEqual({
      url: "/api/notifications/1/read",
      method: "PUT",
      body: undefined,
    });
  });

  it("US2-AC3 이미 읽은 알림을 누르면 읽음 요청을 다시 보내지 않는다", async () => {
    const calls = stubApi({
      pages: [
        { items: [notification(1, 10, { read: true })], nextCursor: null },
      ],
    });

    renderList();
    const link = await screen.findByRole("link");
    link.addEventListener("click", (event) => event.preventDefault());

    fireEvent.click(link);

    expect(calls.filter((call) => call.method === "PUT")).toEqual([]);
  });

  it("US2-AC4 모두 읽음을 누르면 아는 번호 가운데 큰 값을 보내고 그 번호까지 읽음으로 보인다", async () => {
    const calls = stubApi({
      pages: [
        { items: [notification(2, 20), notification(1, 10)], nextCursor: null },
      ],
      unread: { count: 2, latestSeq: 18 },
      readAll: { updated: 2, unreadCount: 0 },
    });
    notificationStreamStore.setState({ lastEventId: 20 });

    renderList();
    await screen.findByText("글 앞부분 2");
    const button = await screen.findByRole("button", { name: "모두 읽음" });
    await waitFor(() => expect(button).not.toHaveAttribute("aria-disabled"));

    fireEvent.click(button);

    await waitFor(() =>
      expect(screen.queryByText("안 읽음")).not.toBeInTheDocument(),
    );
    expect(calls).toContainEqual({
      url: "/api/notifications/read-all",
      method: "POST",
      body: JSON.stringify({ upToSeq: 20 }),
    });
    expect(await screen.findByRole("status")).toHaveTextContent(
      "모든 알림을 읽음으로 표시했어요.",
    );
  });

  it("US2-AC4 안 읽은 알림이 없으면 모두 읽음을 눌러도 요청하지 않는다", async () => {
    const calls = stubApi({
      pages: [
        { items: [notification(1, 10, { read: true })], nextCursor: null },
      ],
      unread: { count: 0, latestSeq: 10 },
    });

    renderList();
    await screen.findByText("글 앞부분 1");
    const button = screen.getByRole("button", { name: "모두 읽음" });
    expect(button).toHaveAttribute("aria-disabled", "true");

    fireEvent.click(button);

    expect(calls.filter((call) => call.method === "POST")).toEqual([]);
  });

  it("US2-AC5 post가 null이면 '삭제된 글'로 보이고, 누르면 이동하지 않고 안내를 띄운다", async () => {
    const calls = stubApi({
      pages: [
        { items: [notification(1, 10, { post: null })], nextCursor: null },
      ],
      unread: { count: 1, latestSeq: 10 },
    });

    renderList();

    const item = await screen.findByRole("listitem");
    expect(item).toHaveTextContent("삭제된 글");
    expect(item).not.toHaveTextContent("글 앞부분");
    // 갈 곳이 없으므로 링크가 아니다.
    expect(within(item).queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText("삭제된 글이에요.")).not.toBeInTheDocument();

    fireEvent.click(within(item).getByRole("button"));

    expect(screen.getByRole("status")).toHaveTextContent("삭제된 글이에요.");
    // 목록에는 그대로 남고, 본 것이므로 읽음이 된다.
    expect(screen.getByRole("listitem")).toHaveTextContent("삭제된 글");
    await waitFor(() =>
      expect(calls).toContainEqual({
        url: "/api/notifications/1/read",
        method: "PUT",
        body: undefined,
      }),
    );
  });

  it("알림이 하나도 없으면 안내를 보이고 모두 읽음 버튼은 없다", async () => {
    stubApi({ pages: [{ items: [], nextCursor: null }] });

    renderList();

    expect(
      await screen.findByText("아직 받은 알림이 없어요."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "모두 읽음" }),
    ).not.toBeInTheDocument();
  });

  it("목록을 받는 사이에 실시간으로 더 새 알림이 왔으면 목록을 한 번 다시 받는다", async () => {
    const calls = stubApi({
      pages: [
        { items: [notification(1, 10)], nextCursor: null },
        { items: [notification(2, 20), notification(1, 10)], nextCursor: null },
      ],
      unread: { count: 2, latestSeq: 20 },
    });
    // 스트림은 번호 20까지 받았는데, 먼저 나간 목록 조회는 번호 10까지만 담아 왔다.
    notificationStreamStore.setState({ lastEventId: 20 });

    renderList();

    expect(await screen.findByText("글 앞부분 2")).toBeInTheDocument();
    expect(
      calls.filter((call) => call.url === "/api/notifications"),
    ).toHaveLength(2);
  });

  it("다시 받아도 그 번호의 알림이 없으면(보관 기간이 지남) 되풀이하지 않는다", async () => {
    const calls = stubApi({
      pages: [{ items: [notification(1, 10)], nextCursor: null }],
      unread: { count: 1, latestSeq: 20 },
    });
    notificationStreamStore.setState({ lastEventId: 20 });

    renderList();
    await screen.findByText("글 앞부분 1");

    await waitFor(() =>
      expect(
        calls.filter((call) => call.url === "/api/notifications"),
      ).toHaveLength(2),
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(
      calls.filter((call) => call.url === "/api/notifications"),
    ).toHaveLength(2);
  });
});
