import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { stopNotificationStream } from "@/features/notification-stream";

const { pushMock, navigation } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  navigation: { pathname: "/home" },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
  usePathname: () => navigation.pathname,
}));

import { NotificationBell } from "./notification-bell";

const STREAM_URL = "http://api.example:8080/api/v1/notifications/stream";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  closed = false;
  private readonly listeners = new Map<string, ((event: Event) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: Event) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, init: { data?: string; lastEventId?: string } = {}): void {
    const event = Object.assign(new Event(type), init);
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }
}

const json = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, error: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

function stubApi(unread: { count: number; latestSeq: number }) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === "/api/notifications/unread-count") {
      return json(unread);
    }
    if (path === "/api/notifications/stream-ticket") {
      return json({
        ticket: "ticket-1",
        streamUrl: STREAM_URL,
        expiresAt: "2026-10-06T00:00:30Z",
      });
    }
    throw new Error(`예상하지 못한 요청: ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function notificationEvent(
  overrides: Record<string, unknown> = {},
  unreadCount = 1,
) {
  return {
    notification: {
      notificationId: 5,
      seq: 18,
      type: "POST_COMMENT",
      postId: 10,
      post: { postId: 10, contentPreview: "남에게 보이면 안 되는 본문" },
      commentId: 7,
      actor: { id: 2, nickname: "오구" },
      actorCount: 1,
      read: false,
      createdAt: "2026-10-06T00:00:00Z",
      updatedAt: "2026-10-06T00:00:00Z",
      ...overrides,
    },
    unreadCount,
  };
}

function renderBell() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <NotificationBell />
    </QueryClientProvider>,
  );
  return { queryClient, ...view };
}

/** 스트림이 열릴 때까지 기다렸다가 그 연결을 돌려준다. */
async function openedStream(): Promise<FakeEventSource> {
  await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
  const [source] = FakeEventSource.instances;
  act(() => source.emit("open"));
  return source;
}

function emitNotification(
  source: FakeEventSource,
  event: ReturnType<typeof notificationEvent>,
) {
  act(() =>
    source.emit("notification", {
      data: JSON.stringify(event),
      lastEventId: String(event.notification.seq),
    }),
  );
}

beforeEach(() => {
  FakeEventSource.instances = [];
  navigation.pathname = "/home";
  vi.stubGlobal("EventSource", FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("NotificationBell", () => {
  it("안 읽은 알림이 없으면 배지 없이 알림 페이지로 가는 종만 보인다", async () => {
    stubApi({ count: 0, latestSeq: 0 });
    renderBell();
    await openedStream();

    const bell = screen.getByRole("link", { name: "알림" });
    expect(bell).toHaveAttribute("href", "/notifications");
    expect(screen.queryByTestId("notification-badge")).not.toBeInTheDocument();
  });

  it("안 읽은 수를 배지로 보이고 99를 넘으면 99+로 보인다", async () => {
    stubApi({ count: 120, latestSeq: 300 });
    renderBell();

    expect(await screen.findByTestId("notification-badge")).toHaveTextContent(
      "99+",
    );
    expect(
      screen.getByRole("link", { name: "알림, 안 읽은 알림 99+개" }),
    ).toBeInTheDocument();
  });

  it("US1-AC6 마운트되면 안 읽은 수의 latestSeq를 lastEventId로 넘겨 스트림을 연다", async () => {
    stubApi({ count: 2, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    const url = new URL(source.url);
    expect(url.origin + url.pathname).toBe(STREAM_URL);
    expect(url.searchParams.get("lastEventId")).toBe("17");
    expect(url.searchParams.get("ticket")).toBe("ticket-1");
    expect(screen.getByRole("link", { name: /알림/ })).toHaveAttribute(
      "data-stream-status",
      "open",
    );
  });

  it("US1-AC1 새 알림이 오면 새로고침 없이 토스트가 뜨고 배지가 늘어난다", async () => {
    stubApi({ count: 2, latestSeq: 17 });
    renderBell();
    const source = await openedStream();
    expect(await screen.findByTestId("notification-badge")).toHaveTextContent(
      "2",
    );

    emitNotification(source, notificationEvent({}, 3));

    expect(
      screen.getByRole("button", { name: "오구 님이 내 글에 댓글을 남겼어요" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("notification-badge")).toHaveTextContent("3");
  });

  it("토스트에는 종류 문구만 쓰고 글이나 댓글 본문을 넣지 않는다(ADR-0005)", async () => {
    stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    emitNotification(source, notificationEvent());

    expect(
      screen.getByRole("region", { name: "새 알림" }),
    ).not.toHaveTextContent("본문");
  });

  it("US1-AC2 공감 묶음이 새 번호로 다시 오면 '외 N명' 문구로 토스트가 뜬다", async () => {
    stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    emitNotification(
      source,
      notificationEvent({ type: "POST_LIKE", commentId: null }, 1),
    );
    emitNotification(
      source,
      notificationEvent(
        {
          type: "POST_LIKE",
          commentId: null,
          seq: 19,
          actorCount: 2,
          actor: { id: 3, nickname: "하루" },
        },
        1,
      ),
    );

    expect(
      screen.getByRole("button", { name: "하루 님 외 1명이 공감했어요" }),
    ).toBeInTheDocument();
    // 같은 묶음이라 안 읽은 수는 하나 그대로다.
    expect(screen.getByTestId("notification-badge")).toHaveTextContent("1");
  });

  it("토스트를 누르면 관련 글로 이동한다", async () => {
    stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();
    emitNotification(source, notificationEvent());

    await userEvent.click(
      screen.getByRole("button", { name: "오구 님이 내 글에 댓글을 남겼어요" }),
    );

    expect(pushMock).toHaveBeenCalledWith("/post/10");
  });

  it("알림 페이지를 보고 있으면 토스트를 띄우지 않고 배지만 바꾼다", async () => {
    navigation.pathname = "/notifications";
    stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    emitNotification(source, notificationEvent({}, 1));

    expect(
      screen.queryByRole("button", { name: /댓글을 남겼어요/ }),
    ).not.toBeInTheDocument();
    expect(await screen.findByTestId("notification-badge")).toHaveTextContent(
      "1",
    );
    expect(
      screen.queryByRole("button", { name: /댓글을 남겼어요/ }),
    ).not.toBeInTheDocument();
  });

  it("이미 읽은 알림이 다시 오면 토스트를 띄우지 않는다", async () => {
    stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    emitNotification(source, notificationEvent({ read: true }, 0));

    expect(
      screen.queryByRole("button", { name: /댓글을 남겼어요/ }),
    ).not.toBeInTheDocument();
  });

  it("US1-AC7 unread-count 이벤트는 토스트 없이 배지만 바꾼다", async () => {
    stubApi({ count: 3, latestSeq: 17 });
    renderBell();
    const source = await openedStream();
    expect(await screen.findByTestId("notification-badge")).toHaveTextContent(
      "3",
    );

    act(() => source.emit("unread-count", { data: '{"unreadCount":0}' }));

    await waitFor(() =>
      expect(
        screen.queryByTestId("notification-badge"),
      ).not.toBeInTheDocument(),
    );
    expect(
      screen.getByRole("region", { name: "새 알림" }),
    ).toBeEmptyDOMElement();
  });

  it("위젯이 사라지면 연결을 닫는다", async () => {
    stubApi({ count: 0, latestSeq: 17 });
    const { unmount } = renderBell();
    const source = await openedStream();

    unmount();

    expect(source.closed).toBe(true);
  });

  it("로그아웃에 성공하면 연결을 닫고 다시 붙지 않는다", async () => {
    const fetchMock = stubApi({ count: 0, latestSeq: 17 });
    renderBell();
    const source = await openedStream();

    act(() => stopNotificationStream());

    expect(source.closed).toBe(true);
    expect(screen.getByRole("link", { name: /알림/ })).toHaveAttribute(
      "data-stream-status",
      "idle",
    );
    const ticketCalls = fetchMock.mock.calls.filter(
      ([input]) => String(input) === "/api/notifications/stream-ticket",
    );
    expect(ticketCalls).toHaveLength(1);
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
