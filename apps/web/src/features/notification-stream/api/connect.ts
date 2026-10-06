import type {
  StreamNotificationEvent,
  StreamUnreadCountEvent,
} from "@/entities/notification";
import { requestApi } from "@/shared/api";

/** BFF 티켓 라우트(`POST /api/notifications/stream-ticket`)의 응답(bff-routes.md). */
export interface StreamTicket {
  ticket: string;
  /** API 도메인의 스트림 주소. 브라우저가 BFF를 거치지 않고 바로 붙는 유일한 주소다. */
  streamUrl: string;
  expiresAt: string;
}

export interface StreamHandlers {
  onOpen: () => void;
  /** `seq`는 SSE 이벤트 id다. 다시 연결할 때 `lastEventId`로 넘긴다. */
  onNotification: (event: StreamNotificationEvent, seq: number) => void;
  onUnreadCount: (event: StreamUnreadCountEvent) => void;
  /** 연결이 끊겼다. 이 연결은 이미 닫혔으므로 새 티켓으로 다시 열어야 한다. */
  onError: () => void;
}

export interface StreamConnection {
  close: () => void;
}

/** `EventSource`에서 쓰는 부분만. 테스트가 가짜로 바꾼다. */
export interface EventSourceLike {
  addEventListener: (type: string, listener: (event: Event) => void) => void;
  close: () => void;
}

export interface OpenStreamOptions {
  fetchImpl?: typeof fetch;
  createEventSource?: (url: string) => EventSourceLike;
}

/**
 * 일회용 티켓을 받는다. 같은 출처 BFF만 부르고, 응답에는 access 토큰이 없다(FR-006).
 * 세션이 끝났으면 401 `ApiError`를 던진다.
 */
export function fetchStreamTicket(
  fetchImpl: typeof fetch = fetch,
): Promise<StreamTicket> {
  return requestApi<StreamTicket>(
    "/api/notifications/stream-ticket",
    { method: "POST", cache: "no-store" },
    fetchImpl,
  );
}

/** 주소에는 티켓과 마지막 번호만 싣는다. 새 `EventSource`는 `Last-Event-ID` 헤더를 붙일 수 없다. */
function streamAddress(ticket: StreamTicket, lastEventId: number): string {
  const url = new URL(ticket.streamUrl);
  url.search = new URLSearchParams({
    ticket: ticket.ticket,
    lastEventId: String(lastEventId),
  }).toString();
  return url.toString();
}

function parseJson<T>(event: Event): T | null {
  try {
    return JSON.parse((event as MessageEvent<string>).data) as T;
  } catch {
    return null;
  }
}

/**
 * 새 티켓을 받아 실시간 알림 스트림을 한 번 연다(research R3, R14). `lastEventId` 뒤의 알림은
 * 서버가 연결 직후 순서대로 다시 보낸다.
 *
 * 브라우저 `EventSource`는 끊기면 같은 주소(이미 쓴 티켓)로 스스로 다시 붙으려 한다. 그래서
 * `error`가 오면 바로 닫고 `onError`를 한 번만 알린다. 다시 여는 것은 호출한 쪽(스토어)이
 * 이 함수를 새로 불러서 한다. 서버가 15분 뒤 닫는 것도 같은 `error`로 온다.
 */
export async function openStream(
  lastEventId: number,
  handlers: StreamHandlers,
  {
    fetchImpl = fetch,
    createEventSource = (url) => new EventSource(url),
  }: OpenStreamOptions = {},
): Promise<StreamConnection> {
  const ticket = await fetchStreamTicket(fetchImpl);
  const source = createEventSource(streamAddress(ticket, lastEventId));
  let closed = false;
  const close = () => {
    closed = true;
    source.close();
  };

  source.addEventListener("open", () => {
    if (!closed) {
      handlers.onOpen();
    }
  });
  source.addEventListener("error", () => {
    if (closed) {
      return;
    }
    close();
    handlers.onError();
  });
  source.addEventListener("notification", (event) => {
    const body = parseJson<StreamNotificationEvent>(event);
    if (closed || body === null) {
      return;
    }
    const seq = Number((event as MessageEvent).lastEventId);
    handlers.onNotification(
      body,
      Number.isFinite(seq) && seq > 0 ? seq : body.notification.seq,
    );
  });
  source.addEventListener("unread-count", (event) => {
    const body = parseJson<StreamUnreadCountEvent>(event);
    if (!closed && body !== null) {
      handlers.onUnreadCount(body);
    }
  });

  return { close };
}
