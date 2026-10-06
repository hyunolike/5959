import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import {
  openStream,
  type EventSourceLike,
  type StreamHandlers,
} from "./connect";

const STREAM_URL = "http://api.example:8080/api/v1/notifications/stream";

class FakeEventSource implements EventSourceLike {
  closed = false;
  private readonly listeners = new Map<string, ((event: Event) => void)[]>();

  constructor(readonly url: string) {}

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

function ticketFetch(...tickets: string[]) {
  const fetchImpl = vi.fn<typeof fetch>();
  for (const ticket of tickets) {
    fetchImpl.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          success: true,
          data: {
            ticket,
            streamUrl: STREAM_URL,
            expiresAt: "2026-10-06T00:00:30Z",
          },
          error: null,
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );
  }
  return fetchImpl;
}

function setup(fetchImpl: typeof fetch) {
  const sources: FakeEventSource[] = [];
  const handlers = {
    onOpen: vi.fn(),
    onNotification: vi.fn(),
    onUnreadCount: vi.fn(),
    onPing: vi.fn(),
    onError: vi.fn(),
  } satisfies StreamHandlers;
  const options = {
    fetchImpl,
    createEventSource: (url: string) => {
      const source = new FakeEventSource(url);
      sources.push(source);
      return source;
    },
  };
  return { sources, handlers, options };
}

const notificationEvent = {
  notification: {
    notificationId: 5,
    seq: 18,
    type: "POST_COMMENT",
    postId: 10,
    post: { postId: 10, contentPreview: "미리보기" },
    commentId: 7,
    actor: { id: 2, nickname: "오구" },
    actorCount: 1,
    read: false,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
  },
  unreadCount: 4,
};

describe("openStream", () => {
  it("US1-AC8 같은 출처 BFF에서 티켓을 받아 그 주소로 EventSource를 연다", async () => {
    const fetchImpl = ticketFetch("ticket-1");
    const { sources, handlers, options } = setup(fetchImpl);

    await openStream(17, handlers, options);

    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/notifications/stream-ticket",
      expect.objectContaining({ method: "POST" }),
    );
    expect(sources).toHaveLength(1);
    expect(sources[0].url.startsWith(`${STREAM_URL}?`)).toBe(true);
  });

  it("US1-AC8 주소에는 ticket과 lastEventId만 있고 access 토큰이 없다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    await openStream(17, handlers, options);

    const url = new URL(sources[0].url);
    expect([...url.searchParams.keys()].sort()).toEqual([
      "lastEventId",
      "ticket",
    ]);
    expect(url.searchParams.get("ticket")).toBe("ticket-1");
    expect(sources[0].url).not.toMatch(/access|token|bearer/i);
  });

  it("US1-AC6 첫 연결 주소의 lastEventId는 넘겨받은 번호(unread-count의 latestSeq)와 같다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));
    const latestSeq = 17;

    await openStream(latestSeq, handlers, options);

    expect(new URL(sources[0].url).searchParams.get("lastEventId")).toBe("17");
  });

  it("US1-AC6 error면 바로 닫고, 다시 열 때는 새 티켓과 마지막 번호를 쓴다", async () => {
    const fetchImpl = ticketFetch("ticket-1", "ticket-2");
    const { sources, handlers, options } = setup(fetchImpl);

    await openStream(17, handlers, options);
    sources[0].emit("error");

    // 브라우저가 이미 쓴 티켓으로 스스로 다시 붙지 않게 바로 닫는다(research R3).
    expect(sources[0].closed).toBe(true);
    expect(handlers.onError).toHaveBeenCalledTimes(1);

    await openStream(21, handlers, options);

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    const url = new URL(sources[1].url);
    expect(url.searchParams.get("ticket")).toBe("ticket-2");
    expect(url.searchParams.get("lastEventId")).toBe("21");
  });

  it("닫은 뒤에 온 error와 이벤트는 알리지 않는다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    const connection = await openStream(17, handlers, options);
    connection.close();
    sources[0].emit("error");
    sources[0].emit("notification", {
      data: JSON.stringify(notificationEvent),
      lastEventId: "18",
    });

    expect(sources[0].closed).toBe(true);
    expect(handlers.onError).not.toHaveBeenCalled();
    expect(handlers.onNotification).not.toHaveBeenCalled();
  });

  it("US1-AC1 notification 이벤트는 본문과 이벤트 id(seq)를 넘긴다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    await openStream(17, handlers, options);
    sources[0].emit("open");
    sources[0].emit("notification", {
      data: JSON.stringify(notificationEvent),
      lastEventId: "18",
    });

    expect(handlers.onOpen).toHaveBeenCalledTimes(1);
    expect(handlers.onNotification).toHaveBeenCalledWith(notificationEvent, 18);
  });

  it("unread-count 이벤트는 안 읽은 수만 넘긴다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    await openStream(17, handlers, options);
    sources[0].emit("unread-count", { data: '{"unreadCount":2}' });

    expect(handlers.onUnreadCount).toHaveBeenCalledWith({ unreadCount: 2 });
  });

  it("ping 이벤트는 연결이 살아 있다는 것만 알린다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    await openStream(17, handlers, options);
    sources[0].emit("ping", { data: "{}" });

    expect(handlers.onPing).toHaveBeenCalledTimes(1);
    expect(handlers.onNotification).not.toHaveBeenCalled();
    expect(handlers.onUnreadCount).not.toHaveBeenCalled();
  });

  it("해석할 수 없는 본문은 버리고 연결은 그대로 둔다", async () => {
    const { sources, handlers, options } = setup(ticketFetch("ticket-1"));

    await openStream(17, handlers, options);
    sources[0].emit("notification", { data: "{깨진", lastEventId: "18" });

    expect(handlers.onNotification).not.toHaveBeenCalled();
    expect(sources[0].closed).toBe(false);
  });

  it("US1-AC8 티켓 발급이 401이면 ApiError를 던지고 EventSource를 열지 않는다", async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          data: null,
          error: { code: "SESSION_EXPIRED", message: "세션이 만료되었습니다." },
        }),
        { status: 401, headers: { "content-type": "application/json" } },
      ),
    );
    const { sources, handlers, options } = setup(fetchImpl);

    const attempt = openStream(17, handlers, options);

    await expect(attempt).rejects.toBeInstanceOf(ApiError);
    await expect(attempt).rejects.toMatchObject({ status: 401 });
    expect(sources).toHaveLength(0);
  });
});
