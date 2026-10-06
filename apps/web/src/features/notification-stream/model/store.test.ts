import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import type { StreamHandlers } from "../api/connect";

import { createNotificationStreamStore, type StreamDeps } from "./store";

interface Attempt {
  lastEventId: number;
  handlers: StreamHandlers;
  close: ReturnType<typeof vi.fn>;
}

function notificationEvent(notificationId: number, seq: number) {
  return {
    notification: {
      notificationId,
      seq,
      type: "POST_COMMENT" as const,
      postId: 10,
      post: null,
      commentId: 7,
      actor: { id: 2, nickname: "오구" },
      actorCount: 1,
      read: false,
      createdAt: "2026-10-06T00:00:00Z",
      updatedAt: "2026-10-06T00:00:00Z",
    },
    unreadCount: 1,
  };
}

/** 열기에 성공하는 가짜 연결. 시도마다 받은 번호와 핸들러를 기록한다. */
function setup() {
  const attempts: Attempt[] = [];
  const deps = {
    loadLatestSeq: vi.fn<StreamDeps["loadLatestSeq"]>().mockResolvedValue(17),
    onNotification: vi.fn<StreamDeps["onNotification"]>(),
    onUnreadCount: vi.fn<StreamDeps["onUnreadCount"]>(),
    onReconnected: vi.fn<StreamDeps["onReconnected"]>(),
    // 흔들기가 0이 되는 값이라 대기 시간이 정확히 1초, 2초, 4초다.
    random: () => 0.5,
    openStream: vi.fn<NonNullable<StreamDeps["openStream"]>>(
      async (lastEventId, handlers) => {
        const close = vi.fn();
        attempts.push({ lastEventId, handlers, close });
        return { close };
      },
    ),
  } satisfies StreamDeps;
  const store = createNotificationStreamStore();
  return { store, deps, attempts };
}

/** 대기 중인 프라미스(번호 조회, 티켓 발급)를 끝까지 흘려보낸다. */
async function flush() {
  await vi.advanceTimersByTimeAsync(0);
}

function setVisibility(state: "visible" | "hidden") {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => state,
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  setVisibility("visible");
});

afterEach(() => {
  vi.useRealTimers();
});

describe("알림 스트림 연결 상태", () => {
  it("US1-AC1 idle → connecting → open → retrying 순서로 바뀐다", async () => {
    const { store, deps, attempts } = setup();
    expect(store.getState().status).toBe("idle");

    store.getState().start(deps);
    expect(store.getState().status).toBe("connecting");

    await flush();
    attempts[0].handlers.onOpen();
    expect(store.getState().status).toBe("open");

    attempts[0].handlers.onError();
    expect(store.getState().status).toBe("retrying");
    store.getState().stop();
  });

  it("US1-AC6 첫 연결은 unread-count의 latestSeq를 lastEventId로 넘긴다", async () => {
    const { store, deps, attempts } = setup();

    store.getState().start(deps);
    await flush();

    expect(deps.loadLatestSeq).toHaveBeenCalledTimes(1);
    expect(attempts[0].lastEventId).toBe(17);
    expect(store.getState().lastEventId).toBe(17);
    store.getState().stop();
  });

  it("US1-AC6 끊기면 기다린 뒤 마지막으로 받은 번호로 다시 연다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();
    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);
    attempts[0].handlers.onNotification(notificationEvent(2, 19), 19);

    attempts[0].handlers.onError();
    await vi.advanceTimersByTimeAsync(999);
    expect(attempts).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(attempts).toHaveLength(2);
    expect(attempts[1].lastEventId).toBe(19);
    // 번호는 처음 한 번만 조회한다. 다시 조회하면 끊긴 동안의 알림을 건너뛴다.
    expect(deps.loadLatestSeq).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("connecting");
    store.getState().stop();
  });

  it("US1-AC6 이미 받은 번호 이하의 알림은 다시 전하지 않는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();

    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);
    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);
    attempts[0].handlers.onNotification(notificationEvent(9, 17), 17);

    expect(deps.onNotification).toHaveBeenCalledTimes(1);
    expect(store.getState().lastEventId).toBe(18);
    store.getState().stop();
  });

  it("연달아 실패하면 1초, 2초, 4초로 늘려 기다리고, 20초 동안 열려 있으면 시도 횟수를 0으로 되돌린다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();

    attempts[0].handlers.onError();
    expect(store.getState().attempt).toBe(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(attempts).toHaveLength(2);

    attempts[1].handlers.onError();
    expect(store.getState().attempt).toBe(2);
    await vi.advanceTimersByTimeAsync(1999);
    expect(attempts).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(attempts).toHaveLength(3);

    attempts[2].handlers.onOpen();
    await vi.advanceTimersByTimeAsync(19_999);
    expect(store.getState().attempt).toBe(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(store.getState().attempt).toBe(0);

    attempts[2].handlers.onError();
    await vi.advanceTimersByTimeAsync(1000);
    expect(attempts).toHaveLength(4);
    store.getState().stop();
  });

  it("열리자마자 끊기기를 되풀이하면(탭이 6개 이상) 대기 시간이 계속 늘어난다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();

    // 서버가 여섯 번째 연결을 받으면 가장 오래된 연결을 닫는다. 열렸다가 5초 만에 닫히기를 되풀이한다.
    const waits = [1000, 2000, 4000, 8000];
    for (const [index, wait] of waits.entries()) {
      attempts[index].handlers.onOpen();
      await vi.advanceTimersByTimeAsync(5000);
      attempts[index].handlers.onError();
      expect(store.getState().attempt).toBe(index + 1);

      await vi.advanceTimersByTimeAsync(wait - 1);
      expect(attempts).toHaveLength(index + 1);
      await vi.advanceTimersByTimeAsync(1);
      expect(attempts).toHaveLength(index + 2);
    }
    store.getState().stop();
  });

  it("티켓 발급이 네트워크 오류로 실패해도 기다린 뒤 다시 시도한다", async () => {
    const { store, deps } = setup();
    deps.openStream.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    store.getState().start(deps);
    await flush();
    expect(store.getState().status).toBe("retrying");

    await vi.advanceTimersByTimeAsync(1000);
    expect(deps.openStream).toHaveBeenCalledTimes(2);
    store.getState().stop();
  });

  it("US1-AC8 티켓 발급이 401이면 stopped이고 다시 시도하지 않는다", async () => {
    const { store, deps } = setup();
    deps.openStream.mockRejectedValue(
      new ApiError(401, { code: "SESSION_EXPIRED", message: "세션 만료" }),
    );

    store.getState().start(deps);
    await flush();
    expect(store.getState().status).toBe("stopped");

    await vi.advanceTimersByTimeAsync(120_000);
    window.dispatchEvent(new Event("online"));
    await flush();

    expect(deps.openStream).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("stopped");
    store.getState().stop();
  });

  it("stopped에서 탭에 초점이 오면 한 번만 다시 붙어 보고, 실패하면 되풀이하지 않는다", async () => {
    const { store, deps } = setup();
    const sessionEnded = new ApiError(401, {
      code: "SESSION_EXPIRED",
      message: "세션 만료",
    });
    deps.openStream.mockRejectedValue(sessionEnded);
    store.getState().start(deps);
    await flush();
    expect(store.getState().status).toBe("stopped");

    // 초점과 보임이 함께 와도 시도는 한 번이다.
    window.dispatchEvent(new Event("focus"));
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();

    expect(deps.openStream).toHaveBeenCalledTimes(2);
    // 살펴보는 동안에도 화면에는 stopped로 남는다(끝난 세션으로 안 읽은 수를 묻지 않는다).
    expect(store.getState().status).toBe("stopped");
    await vi.advanceTimersByTimeAsync(120_000);
    expect(deps.openStream).toHaveBeenCalledTimes(2);

    // 네트워크 오류로 실패해도 기다렸다 다시 하지 않는다. 다음 초점에서 한 번 더 본다.
    deps.openStream.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    window.dispatchEvent(new Event("focus"));
    await flush();
    await vi.advanceTimersByTimeAsync(120_000);

    expect(deps.openStream).toHaveBeenCalledTimes(3);
    expect(store.getState().status).toBe("stopped");
    store.getState().stop();
  });

  it("stopped에서 다른 탭으로 로그인한 뒤 초점이 오면 새 번호부터 다시 붙는다", async () => {
    const { store, deps, attempts } = setup();
    deps.openStream.mockRejectedValueOnce(
      new ApiError(401, { code: "SESSION_EXPIRED", message: "세션 만료" }),
    );
    store.getState().start(deps);
    await flush();
    expect(store.getState().status).toBe("stopped");

    // 다시 로그인한 회원은 다를 수 있다. 옛 번호를 쓰지 않고 안 읽은 수를 새로 받는다.
    deps.loadLatestSeq.mockResolvedValue(3);
    window.dispatchEvent(new Event("focus"));
    await flush();

    expect(attempts).toHaveLength(1);
    expect(attempts[0].lastEventId).toBe(3);
    expect(store.getState().status).toBe("connecting");
    attempts[0].handlers.onOpen();
    expect(store.getState().status).toBe("open");

    // 다시 붙은 뒤에는 평소처럼 끊기면 기다렸다 다시 연다.
    attempts[0].handlers.onError();
    await vi.advanceTimersByTimeAsync(1000);
    expect(attempts).toHaveLength(2);
    store.getState().stop();
  });

  it("stop한 뒤에는 초점이 와도 다시 붙어 보지 않는다", async () => {
    const { store, deps } = setup();
    deps.openStream.mockRejectedValue(
      new ApiError(401, { code: "SESSION_EXPIRED", message: "세션 만료" }),
    );
    store.getState().start(deps);
    await flush();
    store.getState().stop();

    window.dispatchEvent(new Event("focus"));
    await flush();

    expect(deps.openStream).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("idle");
  });

  it("US1-AC6 열려 있어도 60초 동안 아무 이벤트가 없으면 닫고 다시 붙는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();
    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);

    await vi.advanceTimersByTimeAsync(59_999);
    expect(attempts[0].close).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);

    expect(attempts[0].close).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("retrying");
    await vi.advanceTimersByTimeAsync(1000);
    expect(attempts).toHaveLength(2);
    expect(attempts[1].lastEventId).toBe(18);
    store.getState().stop();
  });

  it("ping을 비롯한 이벤트가 올 때마다 조용한 시간을 다시 센다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();

    for (let i = 0; i < 6; i += 1) {
      await vi.advanceTimersByTimeAsync(25_000);
      attempts[0].handlers.onPing();
    }
    await vi.advanceTimersByTimeAsync(50_000);
    attempts[0].handlers.onUnreadCount({ unreadCount: 0 });
    await vi.advanceTimersByTimeAsync(50_000);

    expect(attempts).toHaveLength(1);
    expect(attempts[0].close).not.toHaveBeenCalled();
    expect(store.getState().status).toBe("open");
    // ping에는 id가 없다. 마지막 이벤트 id는 그대로다.
    expect(store.getState().lastEventId).toBe(17);
    store.getState().stop();
  });

  it("연결이 열리지도 끊기지도 않은 채 60초가 지나면 닫고 다시 시도한다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();

    await vi.advanceTimersByTimeAsync(60_000);

    expect(attempts[0].close).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("retrying");
    store.getState().stop();
  });

  it("US1-AC6 탭이 30초 넘게 가려졌다 돌아오면 열려 있어도 닫고 바로 다시 붙는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();
    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);

    setVisibility("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    // 잠든 동안에는 타이머도 돌지 않는다. 시계만 앞으로 간다.
    vi.setSystemTime(Date.now() + 31_000);
    setVisibility("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();

    expect(attempts[0].close).toHaveBeenCalledTimes(1);
    expect(attempts).toHaveLength(2);
    expect(attempts[1].lastEventId).toBe(18);
    expect(store.getState().status).toBe("connecting");
    store.getState().stop();
  });

  it("US1-AC6 30초 넘게 오프라인이었다 돌아와도 열린 연결을 닫고 다시 붙는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();

    window.dispatchEvent(new Event("offline"));
    vi.setSystemTime(Date.now() + 31_000);
    window.dispatchEvent(new Event("online"));
    await flush();

    expect(attempts[0].close).toHaveBeenCalledTimes(1);
    expect(attempts).toHaveLength(2);
    store.getState().stop();
  });

  it("잠깐(30초 이하) 가려졌다 돌아오면 열린 연결을 그대로 둔다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();

    setVisibility("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    vi.setSystemTime(Date.now() + 30_000);
    setVisibility("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();

    expect(attempts).toHaveLength(1);
    expect(attempts[0].close).not.toHaveBeenCalled();
    store.getState().stop();
  });

  it("US1-AC8 안 읽은 수 조회가 401이어도 stopped이고 티켓을 요청하지 않는다", async () => {
    const { store, deps } = setup();
    deps.loadLatestSeq.mockRejectedValue(
      new ApiError(401, { code: "UNAUTHORIZED", message: "인증 필요" }),
    );

    store.getState().start(deps);
    await flush();

    expect(store.getState().status).toBe("stopped");
    expect(deps.openStream).not.toHaveBeenCalled();
  });

  it("US1-AC6 기다리는 중에 네트워크가 돌아오면(online) 기다리지 않고 바로 연결한다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onError();
    attempts[0].handlers.onError();
    expect(store.getState().status).toBe("retrying");

    window.dispatchEvent(new Event("online"));
    await flush();

    expect(attempts).toHaveLength(2);
    // 예약해 둔 재시도는 취소돼 한 번 더 열지 않는다.
    await vi.advanceTimersByTimeAsync(60_000);
    expect(attempts).toHaveLength(2);
    store.getState().stop();
  });

  it("US1-AC6 기다리는 중에 탭이 다시 보이면(visibilitychange) 기다리지 않고 바로 연결한다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onError();

    setVisibility("hidden");
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();
    expect(attempts).toHaveLength(1);

    setVisibility("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();
    expect(attempts).toHaveLength(2);
    store.getState().stop();
  });

  it("열려 있을 때는 online이나 visibilitychange가 와도 새로 열지 않는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();

    window.dispatchEvent(new Event("online"));
    document.dispatchEvent(new Event("visibilitychange"));
    await flush();

    expect(attempts).toHaveLength(1);
    expect(attempts[0].close).not.toHaveBeenCalled();
    store.getState().stop();
  });

  it("US1-AC7 탭 하나에서 start를 두 번 불러도 연결은 하나다", async () => {
    const { store, deps, attempts } = setup();

    store.getState().start(deps);
    store.getState().start(deps);
    await flush();

    expect(attempts).toHaveLength(1);
    store.getState().stop();
  });

  it("다시 붙었을 때만 onReconnected를 부른다(안 읽은 수를 새로 받는다)", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();
    expect(deps.onReconnected).not.toHaveBeenCalled();

    attempts[0].handlers.onError();
    await vi.advanceTimersByTimeAsync(1000);
    attempts[1].handlers.onOpen();

    expect(deps.onReconnected).toHaveBeenCalledTimes(1);
    store.getState().stop();
  });

  it("로그아웃이면(stop) 연결을 닫고 idle로 돌아가며 다시 붙지 않는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onOpen();
    attempts[0].handlers.onNotification(notificationEvent(1, 18), 18);

    store.getState().stop();

    expect(attempts[0].close).toHaveBeenCalledTimes(1);
    expect(store.getState()).toMatchObject({
      status: "idle",
      lastEventId: null,
      attempt: 0,
    });

    // 닫은 연결에서 늦게 온 신호와 브라우저 이벤트는 아무것도 하지 않는다.
    attempts[0].handlers.onError();
    attempts[0].handlers.onNotification(notificationEvent(2, 19), 19);
    window.dispatchEvent(new Event("online"));
    await vi.advanceTimersByTimeAsync(120_000);

    expect(attempts).toHaveLength(1);
    expect(deps.onNotification).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("idle");
  });

  it("기다리는 중에 stop하면 예약한 재시도를 취소한다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    attempts[0].handlers.onError();

    store.getState().stop();
    await vi.advanceTimersByTimeAsync(120_000);

    expect(attempts).toHaveLength(1);
  });

  it("티켓을 받는 사이에 stop하면 늦게 열린 연결을 바로 닫는다", async () => {
    let resolveOpen: (connection: { close: () => void }) => void = () => {};
    const close = vi.fn();
    const { store, deps } = setup();
    deps.openStream.mockImplementationOnce(
      () =>
        new Promise<{ close: () => void }>((resolve) => {
          resolveOpen = resolve;
        }),
    );
    store.getState().start(deps);
    await flush();

    store.getState().stop();
    resolveOpen({ close });
    await flush();

    expect(close).toHaveBeenCalledTimes(1);
    expect(store.getState().status).toBe("idle");
  });

  it("로그아웃 뒤 다시 로그인하면(start) 새 회원의 번호부터 다시 붙는다", async () => {
    const { store, deps, attempts } = setup();
    store.getState().start(deps);
    await flush();
    store.getState().stop();

    deps.loadLatestSeq.mockResolvedValue(3);
    store.getState().start(deps);
    await flush();

    expect(attempts).toHaveLength(2);
    expect(attempts[1].lastEventId).toBe(3);
    store.getState().stop();
  });
});
