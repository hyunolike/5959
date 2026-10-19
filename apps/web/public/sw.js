/*
 * 오구 서비스 워커. 하는 일은 하나다: 웹(Cloud Run)이 꺼져 있다가 켜지는 동안 흰 화면 대신
 * 로딩 화면을 보여 준다.
 *
 * 1. 페이지 이동 요청을 서버로 그대로 보낸다.
 * 2. 응답이 LOADING_DELAY_MS 안에 오면 그 응답을 그대로 쓴다.
 * 3. 늦으면 캐시해 둔 로딩 화면을 먼저 보여 주고, 서버 요청은 계속 기다리며 잠시 맡아 둔다.
 * 4. 로딩 화면이 WAIT_PATH로 준비됐는지 물은 뒤 같은 주소로 다시 이동하면, 맡아 둔 응답을
 *    내준다. 서버에는 요청이 한 번만 간다.
 *
 * 페이지 이동(navigate)만 맡는다. `/api/**`, 화면 안의 데이터 요청, 알림 스트림, 정적 파일은
 * 손대지 않고 브라우저가 평소처럼 처리한다. 캐시하는 것은 로딩 화면과 로고뿐이다.
 */

const CACHE = "ogu-cold-start-v1";
const LOADING_PAGE = "/cold-start.html";
const PRECACHE = [LOADING_PAGE, "/pwa/ogu-logo-v2-192.png"];
const WAIT_PATH = "/__ogu/cold-start-wait";
const LOADING_DELAY_MS = 1000;
/** 로딩 화면이 끝내 찾으러 오지 않은 응답은 이만큼 뒤에 버린다. */
const PARKED_TTL_MS = 60000;

/** 로딩 화면을 보여 준 동안 기다리는 서버 응답. 주소 → { network, settled } */
const parked = new Map();

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith("ogu-") && key !== CACHE)
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname === WAIT_PATH) {
    event.respondWith(waitFor(url.searchParams.get("u")));
    return;
  }

  if (request.mode !== "navigate" || url.pathname.startsWith("/api/")) return;

  // 요청 주소에 # 뒷부분이 붙어 올 수 있다. 서버는 그 부분을 받지 않으니 떼고 맞춘다.
  const key = request.url.split("#")[0];
  const held = parked.get(key);
  if (held && held.settled) {
    parked.delete(key);
    event.respondWith(held.network);
    return;
  }

  const network = fetch(request);
  // 로딩 화면을 내준 뒤에도 서버 응답을 끝까지 받도록 워커를 살려 둔다.
  event.waitUntil(network.then(noop, noop));
  event.respondWith(respond(key, network));
});

async function respond(key, network) {
  const first = await Promise.race([
    network.then(
      (response) => ({ response }),
      (error) => ({ error }),
    ),
    delay(LOADING_DELAY_MS),
  ]);
  if (first) {
    if (first.error) throw first.error;
    return first.response;
  }

  const loading = await caches.match(LOADING_PAGE);
  if (!loading) return network;

  park(key, network);
  return new Response(loading.body, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function park(key, network) {
  const held = { network, settled: false };
  parked.set(key, held);
  const settle = () => {
    held.settled = true;
    setTimeout(() => {
      if (parked.get(key) === held) parked.delete(key);
    }, PARKED_TTL_MS);
  };
  network.then(settle, () => {
    // 실패한 응답은 맡아 두지 않는다. 로딩 화면이 다시 시도하면 새 요청이 나간다.
    if (parked.get(key) === held) parked.delete(key);
  });
}

/** 로딩 화면이 묻는다: 이 주소의 서버 응답이 왔나? ready면 다시 이동, failed면 다시 시도 버튼. */
async function waitFor(key) {
  const held = key ? parked.get(key) : undefined;
  let state = "missing";
  if (held) {
    state = await held.network.then(
      () => "ready",
      () => "failed",
    );
  }
  return new Response(JSON.stringify({ state }), {
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(() => resolve(null), ms));
}

function noop() {}
