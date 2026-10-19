"use client";

import { useEffect } from "react";

export const SERVICE_WORKER_URL = "/sw.js";

/**
 * 서비스 워커를 붙일 주소인지. 서비스 워커는 https에서만 돈다. 로컬과 e2e(http://localhost)에는
 * 붙이지 않아 테스트가 서비스 워커 없이 평소처럼 돈다.
 */
export function shouldRegister(appOrigin: string): boolean {
  return new URL(appOrigin).protocol === "https:";
}

/**
 * 웹이 꺼졌다 켜지는 동안 흰 화면 대신 로딩 화면을 보여 주는 서비스 워커(`public/sw.js`)를
 * 등록한다. 한 번 방문한 브라우저부터 효과가 있다. 화면에는 아무것도 그리지 않는다.
 */
export function ServiceWorkerRegistration({
  appOrigin,
}: {
  appOrigin: string;
}) {
  useEffect(() => {
    if (!shouldRegister(appOrigin) || !("serviceWorker" in navigator)) return;
    // 등록 실패는 로딩 화면만 못 보는 것이라 화면 동작에는 영향이 없다.
    navigator.serviceWorker.register(SERVICE_WORKER_URL).catch(() => {});
  }, [appOrigin]);

  return null;
}
