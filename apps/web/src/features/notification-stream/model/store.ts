import { useStore } from "zustand";
import { createStore, type StoreApi } from "zustand/vanilla";

import type {
  StreamNotificationEvent,
  StreamUnreadCountEvent,
} from "@/entities/notification";
import { ApiError } from "@/shared/api";

import {
  openStream as defaultOpenStream,
  type StreamConnection,
  type StreamHandlers,
} from "../api/connect";

import { backoffDelayMs } from "./backoff";

/**
 * - `idle`: 시작 전이거나 닫았다(로그아웃, 위젯이 사라짐).
 * - `connecting`: 번호를 조회하거나 티켓을 받아 여는 중이다.
 * - `open`: 스트림이 열려 있다.
 * - `retrying`: 끊겨서 다시 열기 전에 기다린다.
 * - `stopped`: 세션이 끝났다(401). 다시 로그인해 `start`를 부를 때까지 시도하지 않는다.
 */
export type StreamStatus =
  "idle" | "connecting" | "open" | "retrying" | "stopped";

export interface StreamDeps {
  /** 안 읽은 수 응답의 `latestSeq`. 처음 연결할 때 한 번만 부른다. */
  loadLatestSeq: () => Promise<number>;
  onNotification: (event: StreamNotificationEvent) => void;
  onUnreadCount: (event: StreamUnreadCountEvent) => void;
  /** 끊겼다가 다시 열렸다. `unread-count` 이벤트는 다시 오지 않으므로 안 읽은 수를 새로 받는다. */
  onReconnected: () => void;
  openStream?: (
    lastEventId: number,
    handlers: StreamHandlers,
  ) => Promise<StreamConnection>;
  random?: () => number;
}

/** 연결 상태만 둔다. 알림과 안 읽은 수는 TanStack Query 캐시에 있다(ARCHITECTURE.md). */
export interface StreamState {
  status: StreamStatus;
  /** 마지막으로 받은 이벤트 id(seq). 처음에는 `latestSeq`, 닫으면 `null`. */
  lastEventId: number | null;
  /** 연달아 실패한 횟수. 열리면 0으로 돌아간다. */
  attempt: number;
  /** 연결을 시작한다. 이미 연결 중이거나 열려 있으면 아무것도 하지 않는다(탭마다 연결 하나). */
  start: (deps: StreamDeps) => void;
  /** 연결과 예약한 재시도를 모두 닫고 `idle`로 돌아간다. 로그아웃과 위젯 언마운트에서 부른다. */
  stop: () => void;
}

/** 세션이 끝났거나 권한이 없으면 다시 시도해도 같다. */
function isSessionEnded(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.status === 401 || error.status === 403)
  );
}

export function createNotificationStreamStore(): StoreApi<StreamState> {
  let deps: StreamDeps | null = null;
  let connection: StreamConnection | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  /** `start`와 `stop`마다 바뀐다. 그 전에 시작한 비동기 작업은 결과를 버린다. */
  let session = 0;
  /** 이번 세션에서 한 번이라도 열렸는지. 다시 열렸을 때만 `onReconnected`를 부른다. */
  let openedOnce = false;

  return createStore<StreamState>((set, get) => {
    const clearRetryTimer = () => {
      if (retryTimer !== null) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
    };

    const scheduleRetry = () => {
      const { attempt } = get();
      set({ status: "retrying", attempt: attempt + 1 });
      clearRetryTimer();
      retryTimer = setTimeout(
        () => void connect(),
        backoffDelayMs(attempt, deps?.random),
      );
    };

    /** 기다리는 중에 탭이 다시 보이거나 네트워크가 돌아오면 기다리지 않고 바로 붙는다. */
    const reconnectNow = () => {
      if (
        get().status === "retrying" &&
        document.visibilityState !== "hidden"
      ) {
        void connect();
      }
    };

    const detach = () => {
      session += 1;
      clearRetryTimer();
      connection?.close();
      connection = null;
      document.removeEventListener("visibilitychange", reconnectNow);
      window.removeEventListener("online", reconnectNow);
    };

    const connect = async () => {
      const current = deps;
      if (current === null) {
        return;
      }
      const mine = session;
      /** 이 시도가 아직 유효한지. 닫았거나 이 연결이 이미 끊겼으면 신호를 버린다. */
      let alive = true;
      const isCurrent = () => alive && mine === session;

      clearRetryTimer();
      set({ status: "connecting" });

      const handlers: StreamHandlers = {
        onOpen: () => {
          if (!isCurrent()) {
            return;
          }
          set({ status: "open", attempt: 0 });
          if (openedOnce) {
            current.onReconnected();
          }
          openedOnce = true;
        },
        onNotification: (event, seq) => {
          // 다시 붙은 뒤 서버가 이미 받은 번호를 또 보내도 한 번만 전한다(US1-AC6).
          if (!isCurrent() || seq <= (get().lastEventId ?? 0)) {
            return;
          }
          set({ lastEventId: seq });
          current.onNotification(event);
        },
        onUnreadCount: (event) => {
          if (isCurrent()) {
            current.onUnreadCount(event);
          }
        },
        onError: () => {
          if (!isCurrent()) {
            return;
          }
          alive = false;
          connection = null;
          scheduleRetry();
        },
      };

      try {
        let lastEventId = get().lastEventId;
        if (lastEventId === null) {
          lastEventId = await current.loadLatestSeq();
          if (mine !== session) {
            return;
          }
          // 여는 데 실패해도 이 번호를 기억한다. 다시 조회하면 그 사이 알림을 건너뛴다.
          set({ lastEventId });
        }
        const opened = await (current.openStream ?? defaultOpenStream)(
          lastEventId,
          handlers,
        );
        if (mine !== session) {
          opened.close();
          return;
        }
        if (alive) {
          connection = opened;
        }
      } catch (error) {
        if (mine !== session) {
          return;
        }
        if (isSessionEnded(error)) {
          detach();
          set({ status: "stopped" });
          return;
        }
        scheduleRetry();
      }
    };

    return {
      status: "idle",
      lastEventId: null,
      attempt: 0,
      start: (nextDeps) => {
        const { status } = get();
        if (status !== "idle" && status !== "stopped") {
          return;
        }
        detach();
        deps = nextDeps;
        openedOnce = false;
        set({ lastEventId: null, attempt: 0 });
        document.addEventListener("visibilitychange", reconnectNow);
        window.addEventListener("online", reconnectNow);
        void connect();
      },
      stop: () => {
        detach();
        deps = null;
        set({ status: "idle", lastEventId: null, attempt: 0 });
      },
    };
  });
}

/** 탭 하나에 연결 하나. 알림 종 위젯이 마운트될 때 시작하고 사라질 때 닫는다. */
export const notificationStreamStore = createNotificationStreamStore();

export function useNotificationStreamStatus(): StreamStatus {
  return useStore(notificationStreamStore, (state) => state.status);
}

/**
 * 로그아웃에 성공했을 때 부른다. 연결과 예약한 재시도를 닫아, 끝난 세션으로 티켓을 다시 요청하지 않는다.
 * 다시 로그인하면 알림 종이 새로 마운트되면서 연결을 시작한다.
 */
export function stopNotificationStream(): void {
  notificationStreamStore.getState().stop();
}
