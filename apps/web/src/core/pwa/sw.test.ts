// @vitest-environment node
import { readFileSync } from "node:fs";
import path from "node:path";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * `public/sw.js`를 가짜 서비스 워커 전역(self, caches, fetch) 위에서 그대로 실행해 검사한다.
 * 번들러를 거치지 않는 파일이라 실제로 배포되는 코드를 읽는다.
 */
const SOURCE = readFileSync(
  path.join(__dirname, "../../../public/sw.js"),
  "utf8",
);
const ORIGIN = "https://new.5959.site";
const LOADING_HTML = "<html>loading</html>";

type Listener = (event: FakeEvent) => void;

interface FakeEvent {
  request?: { method: string; url: string; mode: string };
  responded?: Promise<Response>;
  waits: Promise<unknown>[];
  respondWith(response: Promise<Response>): void;
  waitUntil(promise: Promise<unknown>): void;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function loadWorker({ oldCaches = [] as string[] } = {}) {
  const listeners: Record<string, Listener> = {};
  const precached: string[] = [];
  const deleted: string[] = [];
  const self = {
    addEventListener: (type: string, fn: Listener) => {
      listeners[type] = fn;
    },
    location: { origin: ORIGIN },
    skipWaiting: vi.fn(async () => {}),
    clients: { claim: vi.fn(async () => {}) },
  };
  const caches = {
    open: async () => ({
      addAll: async (urls: string[]) => {
        precached.push(...urls);
      },
    }),
    match: async (url: string) =>
      url === "/cold-start.html" ? new Response(LOADING_HTML) : undefined,
    keys: async () => ["ogu-cold-start-v1", ...oldCaches],
    delete: async (key: string) => {
      deleted.push(key);
      return true;
    },
  };
  const fetch = vi.fn<(request: unknown) => Promise<Response>>();
  new Function("self", "caches", "fetch", SOURCE)(self, caches, fetch);

  function dispatch(type: string, request?: FakeEvent["request"]): FakeEvent {
    const event: FakeEvent = {
      request,
      waits: [],
      respondWith(response) {
        this.responded = response;
      },
      waitUntil(promise) {
        this.waits.push(promise);
      },
    };
    listeners[type](event);
    return event;
  }

  const navigate = (pathname: string) =>
    dispatch("fetch", {
      method: "GET",
      url: ORIGIN + pathname,
      mode: "navigate",
    });
  const askReady = (pathname: string) =>
    dispatch("fetch", {
      method: "GET",
      url: `${ORIGIN}/__ogu/cold-start-wait?u=${encodeURIComponent(ORIGIN + pathname)}`,
      mode: "cors",
    });

  return { self, fetch, precached, deleted, dispatch, navigate, askReady };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("public/sw.js", () => {
  it("COLDSTART-AC1 서버가 1초 안에 답하면 그 응답을 그대로 쓴다", async () => {
    const worker = loadWorker();
    worker.fetch.mockResolvedValue(new Response("real page"));

    const event = worker.navigate("/home");

    expect(await (await event.responded!).text()).toBe("real page");
  });

  it("COLDSTART-AC2 1초가 넘으면 로딩 화면을 먼저 보여 주고, 다시 이동하면 맡아 둔 응답을 내준다", async () => {
    const worker = loadWorker();
    const server = deferred<Response>();
    worker.fetch.mockReturnValue(server.promise);

    const first = worker.navigate("/home?tab=1");
    await vi.advanceTimersByTimeAsync(1000);
    const loading = await first.responded!;
    expect(await loading.text()).toBe(LOADING_HTML);
    expect(loading.headers.get("Cache-Control")).toBe("no-store");
    expect(first.waits).toHaveLength(1);

    const ready = worker.askReady("/home?tab=1");
    server.resolve(new Response("real page"));
    expect(await (await ready.responded!).json()).toEqual({ state: "ready" });

    // 주소에 # 이 붙어 와도 같은 응답을 찾는다.
    const second = worker.navigate("/home?tab=1#comments");
    expect(await (await second.responded!).text()).toBe("real page");
    // 다시 이동해도 서버에는 요청이 한 번만 간다.
    expect(worker.fetch).toHaveBeenCalledTimes(1);
  });

  it("COLDSTART-AC3 서버 요청이 실패하면 로딩 화면이 다시 시도를 띄우게 failed를 알린다", async () => {
    const worker = loadWorker();
    const server = deferred<Response>();
    worker.fetch.mockReturnValue(server.promise);

    worker.navigate("/home");
    await vi.advanceTimersByTimeAsync(1000);
    const ready = worker.askReady("/home");
    server.reject(new TypeError("offline"));

    expect(await (await ready.responded!).json()).toEqual({ state: "failed" });
  });

  it("COLDSTART-AC3 맡아 둔 응답이 없으면 missing을 알려 로딩 화면이 새로 요청하게 한다", async () => {
    const worker = loadWorker();

    const ready = worker.askReady("/home");

    expect(await (await ready.responded!).json()).toEqual({ state: "missing" });
  });

  it("COLDSTART-AC4 페이지 이동만 맡는다. /api, 데이터 요청, POST, 다른 출처는 건드리지 않는다", () => {
    const worker = loadWorker();
    const untouched = [
      {
        method: "GET",
        url: `${ORIGIN}/api/notifications/stream`,
        mode: "navigate",
      },
      {
        method: "GET",
        url: `${ORIGIN}/api/auth/oauth/kakao/callback?code=x`,
        mode: "navigate",
      },
      { method: "GET", url: `${ORIGIN}/home?_rsc=1`, mode: "cors" },
      {
        method: "GET",
        url: `${ORIGIN}/_next/static/chunk.js`,
        mode: "no-cors",
      },
      { method: "POST", url: `${ORIGIN}/login`, mode: "navigate" },
      {
        method: "GET",
        url: "https://cloud.umami.is/script.js",
        mode: "navigate",
      },
    ];

    for (const request of untouched) {
      expect(worker.dispatch("fetch", request).responded).toBeUndefined();
    }
    expect(worker.fetch).not.toHaveBeenCalled();
  });

  it("COLDSTART-AC5 설치할 때 로딩 화면과 로고만 캐시하고, 켜질 때 예전 캐시를 지운다", async () => {
    const worker = loadWorker({
      oldCaches: ["ogu-cold-start-v0", "other-app"],
    });

    await Promise.all(worker.dispatch("install").waits);
    await Promise.all(worker.dispatch("activate").waits);

    expect(worker.precached).toEqual([
      "/cold-start.html",
      "/pwa/ogu-logo-v2-192.png",
    ]);
    expect(worker.deleted).toEqual(["ogu-cold-start-v0"]);
    expect(worker.self.skipWaiting).toHaveBeenCalled();
    expect(worker.self.clients.claim).toHaveBeenCalled();
  });
});
