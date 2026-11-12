"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

/**
 * - `checking`: 처음 묻는 중. 깨어 있으면 금방 끝나므로 화면에 아무것도 띄우지 않는다.
 * - `waking`: 잠들어 있어 깨우는 중. 깨어날 때까지 주기적으로 다시 묻는다.
 * - `ready`: 깨어 있다.
 * - `unavailable`: 기다려도 깨어나지 않았다. 다시 기다리라고 하면 처음부터 묻는다.
 */
export type ApiReadinessStatus =
  "checking" | "waking" | "ready" | "unavailable";

export interface ApiReadiness {
  status: ApiReadinessStatus;
  /** 이번에 깨우기 시작한 시각(ms). 깨우는 중이 아니면 `null`. 안내가 얼마나 기다렸는지 보여 줄 때 쓴다. */
  wakingSince: number | null;
  /** 깨어 있으면 바로, 아니면 깨어날 때까지 기다려 `true`를 준다. 끝내 깨어나지 않으면 `false`. */
  waitUntilReady: () => Promise<boolean>;
}

/** 공급자 밖(다른 화면, 테스트)에서는 늘 깨어 있다고 본다. 지금까지처럼 바로 보낸다. */
const ALWAYS_READY: ApiReadiness = {
  status: "ready",
  wakingSince: null,
  waitUntilReady: () => Promise.resolve(true),
};

const ApiReadinessContext = createContext<ApiReadiness>(ALWAYS_READY);

export function useApiReadiness(): ApiReadiness {
  return useContext(ApiReadinessContext);
}

/** API의 liveness만 묻는 웹 라우트. DB를 보지 않아 잠든 DB를 깨우지 않는다(018 US2-AC3). */
const HEALTH_PATH = "/api/health/live";

/** 다시 묻는 간격. 묻는 요청 하나하나가 잠든 서버를 깨우는 신호도 된다. */
const POLL_INTERVAL_MS = 5_000;
/** 무료 플랜의 기동은 4~5분이다(018 research R1, 2026-11-12 측정 최장 313초). 넉넉히 7분을 기다린다. */
const GIVE_UP_AFTER_MS = 7 * 60_000;
/** 서버는 15분 동안 요청이 없으면 잠든다. 그보다 앞서 다시 확인한다. */
const STALE_AFTER_MS = 10 * 60_000;

async function probeHealth(fetchImpl: typeof fetch): Promise<boolean> {
  try {
    const response = await fetchImpl(HEALTH_PATH, { cache: "no-store" });
    if (!response.ok) return false;
    const body = (await response.json()) as { status?: string };
    return body.status === "UP";
  } catch {
    return false;
  }
}

interface ApiReadinessProviderProps {
  children: ReactNode;
  pollIntervalMs?: number;
  giveUpAfterMs?: number;
  staleAfterMs?: number;
  fetchImpl?: typeof fetch;
}

/**
 * API(Render 무료)가 잠들어 있는지 화면이 열릴 때 `/api/health/live`로 묻고, 잠들어 있으면 깨어날 때까지 기다린다(018 US2-AC7).
 * 잠든 서버는 기동에 4분쯤 걸리는데 BFF는 15초만 기다리므로, 그동안 보낸 요청은 실패로 보인다.
 * 게다가 Render가 붙잡아 둔 요청이 기동 뒤 처리될 수 있어, 실패로 보인 가입이 실제로는 되는 일이 생긴다.
 * 그래서 제출은 깨어난 것을 확인한 뒤에만 보낸다(US2-AC8).
 */
export function ApiReadinessProvider({
  children,
  pollIntervalMs = POLL_INTERVAL_MS,
  giveUpAfterMs = GIVE_UP_AFTER_MS,
  staleAfterMs = STALE_AFTER_MS,
  fetchImpl = fetch,
}: ApiReadinessProviderProps) {
  const [status, setStatus] = useState<ApiReadinessStatus>("checking");
  const [wakingSince, setWakingSince] = useState<number | null>(null);
  const statusRef = useRef<ApiReadinessStatus>("checking");
  const readyAtRef = useRef(0);
  const waitersRef = useRef<Array<(ready: boolean) => void>>([]);
  const runningRef = useRef(false);
  const mountedRef = useRef(true);

  const update = useCallback((next: ApiReadinessStatus) => {
    const enteringWake = next === "waking" && statusRef.current !== "waking";
    statusRef.current = next;
    if (!mountedRef.current) return;
    setStatus(next);
    if (enteringWake) setWakingSince(Date.now());
    else if (next !== "waking") setWakingSince(null);
  }, []);

  const settle = useCallback((ready: boolean) => {
    const waiters = waitersRef.current;
    waitersRef.current = [];
    waiters.forEach((resolve) => resolve(ready));
  }, []);

  const run = useCallback(async () => {
    if (runningRef.current) return;
    runningRef.current = true;
    const startedAt = Date.now();

    while (mountedRef.current) {
      if (await probeHealth(fetchImpl)) {
        readyAtRef.current = Date.now();
        update("ready");
        settle(true);
        break;
      }
      if (!mountedRef.current) break;
      if (Date.now() - startedAt >= giveUpAfterMs) {
        update("unavailable");
        settle(false);
        break;
      }
      update("waking");
      await new Promise<void>((resolve) => setTimeout(resolve, pollIntervalMs));
    }
    runningRef.current = false;
  }, [fetchImpl, giveUpAfterMs, pollIntervalMs, settle, update]);

  useEffect(() => {
    mountedRef.current = true;
    // 상태는 첫 응답을 받은 뒤에야 바뀐다. effect 안에서 곧바로 바꾸지 않도록 한 박자 미뤄 시작한다.
    queueMicrotask(() => void run());
    return () => {
      // 타이머는 지우지 않는다. 개발 모드의 두 번 마운트에서 도는 중인 반복이 멈춘 채 남지 않게,
      // 잠을 마치고 mountedRef를 보고 스스로 끝내게 둔다.
      mountedRef.current = false;
      settle(false);
    };
  }, [run, settle]);

  const waitUntilReady = useCallback((): Promise<boolean> => {
    const fresh = Date.now() - readyAtRef.current < staleAfterMs;
    if (statusRef.current === "ready" && fresh) return Promise.resolve(true);

    const waiting = new Promise<boolean>((resolve) => {
      waitersRef.current.push(resolve);
    });
    if (!runningRef.current) {
      // 오래전에 확인했거나 기다리다 포기한 뒤라면 처음부터 다시 묻는다.
      update("checking");
      void run();
    }
    return waiting;
  }, [run, staleAfterMs, update]);

  const value = useMemo(
    () => ({ status, wakingSince, waitUntilReady }),
    [status, wakingSince, waitUntilReady],
  );

  return (
    <ApiReadinessContext.Provider value={value}>
      {children}
    </ApiReadinessContext.Provider>
  );
}
