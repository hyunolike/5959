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
 * - `stopped`: 세션이 끝났다(401). 기다렸다 다시 시도하지 않는다. 탭이 다시 보이거나 초점을 받을 때만
 *   한 번 붙어 보고(다른 탭에서 다시 로그인했을 수 있다), 되면 평소 연결로 돌아간다.
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
  /** 연달아 실패한 횟수. 20초 동안 열려 있으면 0으로 돌아간다. */
  attempt: number;
  /** 연결을 시작한다. 이미 연결 중이거나 열려 있으면 아무것도 하지 않는다(탭마다 연결 하나). */
  start: (deps: StreamDeps) => void;
  /** 연결과 예약한 재시도를 모두 닫고 `idle`로 돌아간다. 로그아웃과 위젯 언마운트에서 부른다. */
  stop: () => void;
}

/** 이 시간 동안 아무 이벤트(`ping` 포함)도 없으면 연결이 죽었다고 본다. 서버 하트비트는 25초마다 온다. */
export const SILENCE_LIMIT_MS = 60_000;
/** 탭이 가려졌거나 오프라인이던 시간이 이보다 길면, 돌아왔을 때 열린 연결도 믿지 않고 다시 붙는다. */
export const AWAY_LIMIT_MS = 30_000;
/** 이만큼 열려 있어야 안정된 연결로 보고 시도 횟수를 0으로 되돌린다. */
export const STABLE_OPEN_MS = 20_000;

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
  let silenceTimer: ReturnType<typeof setTimeout> | null = null;
  let stableTimer: ReturnType<typeof setTimeout> | null = null;
  /** 연결을 닫거나 새로 시작할 때마다 바뀐다. 그 전에 시작한 비동기 작업과 핸들러는 결과를 버린다. */
  let session = 0;
  /** 이번 세션에서 한 번이라도 열렸는지. 다시 열렸을 때만 `onReconnected`를 부른다. */
  let openedOnce = false;
  /** 탭이 가려지거나 오프라인이 된 시각. 돌아오면 지운다. */
  let awayAt: number | null = null;
  /** `stopped`에서 한 번 붙어 보는 중인지. 초점과 보임이 함께 와도 시도는 하나다. */
  let probing = false;

  return createStore<StreamState>((set, get) => {
    const clearTimers = () => {
      for (const timer of [retryTimer, silenceTimer, stableTimer]) {
        if (timer !== null) {
          clearTimeout(timer);
        }
      }
      retryTimer = null;
      silenceTimer = null;
      stableTimer = null;
    };

    /** 지금 연결과 예약한 일을 모두 버린다. 진행 중이던 시도는 결과를 버린다. */
    const closeConnection = () => {
      session += 1;
      probing = false;
      clearTimers();
      connection?.close();
      connection = null;
    };

    const scheduleRetry = () => {
      const { attempt } = get();
      clearTimers();
      set({ status: "retrying", attempt: attempt + 1 });
      retryTimer = setTimeout(
        () => void connect(),
        backoffDelayMs(attempt, deps?.random),
      );
    };

    /**
     * 연결을 한 번 시도한다. `probe`면 `stopped`에서 살펴보는 한 번의 시도다. 성공하기 전까지 상태를
     * 바꾸지 않고, 실패하면 이유와 관계없이 `stopped`로 남아 다시 시도하지 않는다.
     */
    const connect = async (probe = false) => {
      const current = deps;
      if (current === null) {
        return;
      }
      const mine = session;
      /** 이 시도가 아직 유효한지. 닫았거나 이 연결이 이미 끊겼으면 신호를 버린다. */
      let alive = true;
      const isCurrent = () => alive && mine === session;

      /** 이 연결을 버리고 기다렸다 다시 연다. 끊김(`error`)과 조용한 연결이 같은 길로 온다. */
      const drop = () => {
        alive = false;
        connection?.close();
        connection = null;
        scheduleRetry();
      };
      /** 반쯤 죽은 연결은 `error`를 내지 않는다. 이벤트가 올 때마다 다시 세고, 조용하면 끊긴 것으로 본다. */
      const armSilenceTimer = () => {
        if (silenceTimer !== null) {
          clearTimeout(silenceTimer);
        }
        silenceTimer = setTimeout(() => {
          if (isCurrent()) {
            drop();
          }
        }, SILENCE_LIMIT_MS);
      };

      if (!probe) {
        clearTimers();
        set({ status: "connecting" });
      }

      const handlers: StreamHandlers = {
        onOpen: () => {
          if (!isCurrent()) {
            return;
          }
          armSilenceTimer();
          set({ status: "open" });
          // 열리자마자 닫히기를 되풀이할 수 있다(탭이 6개 이상이면 서버가 가장 오래된 연결을 닫는다).
          // 얼마간 열려 있어야 시도 횟수를 되돌린다. 바로 되돌리면 1초마다 서로 밀어내며 끝없이 다시 붙는다.
          stableTimer = setTimeout(() => {
            if (isCurrent()) {
              set({ attempt: 0 });
            }
          }, STABLE_OPEN_MS);
          if (openedOnce) {
            current.onReconnected();
          }
          openedOnce = true;
        },
        onNotification: (event, seq) => {
          if (!isCurrent()) {
            return;
          }
          armSilenceTimer();
          // 다시 붙은 뒤 서버가 이미 받은 번호를 또 보내도 한 번만 전한다(US1-AC6).
          if (seq <= (get().lastEventId ?? 0)) {
            return;
          }
          set({ lastEventId: seq });
          current.onNotification(event);
        },
        onUnreadCount: (event) => {
          if (isCurrent()) {
            armSilenceTimer();
            current.onUnreadCount(event);
          }
        },
        onPing: () => {
          if (isCurrent()) {
            armSilenceTimer();
          }
        },
        onError: () => {
          if (isCurrent()) {
            drop();
          }
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
        if (probe) {
          // 세션이 돌아왔다. 여기서부터는 평소 연결과 같다.
          probing = false;
          set({ status: "connecting" });
        }
        connection = opened;
        // 열리지도 끊기지도 않은 채 멈춘 연결도 같은 시간 뒤에 버린다.
        armSilenceTimer();
      } catch (error) {
        if (mine !== session) {
          return;
        }
        if (probe) {
          // 살펴본 결과 아직 붙을 수 없다. 다음에 초점이 올 때 한 번 더 본다.
          probing = false;
          set({ lastEventId: null });
          return;
        }
        if (isSessionEnded(error)) {
          // 리스너는 남겨 둔다. 다른 탭에서 다시 로그인했을 수 있으니 초점이 오면 한 번 살펴본다.
          closeConnection();
          openedOnce = false;
          // 다시 로그인한 회원은 다를 수 있다. 옛 번호를 버려 다음에는 새로 조회하게 한다.
          set({ status: "stopped", lastEventId: null, attempt: 0 });
          return;
        }
        scheduleRetry();
      }
    };

    /**
     * 탭이 다시 보이거나, 초점을 받거나, 네트워크가 돌아왔다.
     *
     * - `retrying`: 기다리지 않고 바로 붙는다.
     * - `open`: 오래(30초 넘게) 떠나 있었으면 연결이 조용히 죽었을 수 있다. 닫고 다시 붙는다. 티켓 하나를
     *   더 쓸 뿐이고, 마지막 이벤트 id 덕분에 빠지는 알림은 없다.
     * - `stopped`: 다른 탭에서 다시 로그인했을 수 있다. 보이거나 초점이 올 때 한 번만 붙어 본다.
     */
    const wake = (canProbe: boolean) => {
      const awayFor = awayAt === null ? 0 : Date.now() - awayAt;
      awayAt = null;
      const { status } = get();
      if (status === "retrying") {
        void connect();
      } else if (status === "open" && awayFor > AWAY_LIMIT_MS) {
        closeConnection();
        void connect();
      } else if (status === "stopped" && canProbe && !probing) {
        probing = true;
        void connect(true);
      }
    };
    const markAway = () => {
      awayAt ??= Date.now();
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        markAway();
      } else {
        wake(true);
      }
    };
    const onFocus = () => wake(true);
    const onOnline = () => {
      // 가려진 탭은 다시 보일 때 붙는다.
      if (document.visibilityState !== "hidden") {
        wake(false);
      }
    };

    const listen = () => {
      document.addEventListener("visibilitychange", onVisibilityChange);
      window.addEventListener("focus", onFocus);
      window.addEventListener("online", onOnline);
      window.addEventListener("offline", markAway);
    };
    const unlisten = () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("focus", onFocus);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", markAway);
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
        closeConnection();
        unlisten();
        deps = nextDeps;
        openedOnce = false;
        awayAt = null;
        set({ lastEventId: null, attempt: 0 });
        listen();
        void connect();
      },
      stop: () => {
        closeConnection();
        unlisten();
        deps = null;
        awayAt = null;
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
 * 이 탭의 실시간 연결이 마지막으로 받은 이벤트 id(seq). 연결 전이거나 닫았으면 `null`이다. 알림 목록이
 * 모두 읽음의 `upToSeq`와, 목록이 스트림보다 뒤처졌는지를 정할 때 쓴다.
 */
export function useNotificationStreamLastEventId(): number | null {
  return useStore(notificationStreamStore, (state) => state.lastEventId);
}

/**
 * 로그아웃에 성공했을 때 부른다. 연결과 예약한 재시도를 닫아, 끝난 세션으로 티켓을 다시 요청하지 않는다.
 * 다시 로그인하면 알림 종이 새로 마운트되면서 연결을 시작한다.
 */
export function stopNotificationStream(): void {
  notificationStreamStore.getState().stop();
}
