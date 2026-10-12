"use client";

import { useSyncExternalStore } from "react";

const QUERY = "(prefers-reduced-motion: reduce)";
function subscribe(listener: () => void) {
  if (typeof window.matchMedia !== "function") return () => {};
  const media = window.matchMedia(QUERY);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}
function snapshot() {
  return typeof window.matchMedia === "function"
    ? window.matchMedia(QUERY).matches
    : true;
}

/** 서버와 지원하지 않는 환경에서는 움직임을 먼저 끈다. 실행 중 설정 변경에도 반응한다. */
export function useReducedMotionPreference() {
  return useSyncExternalStore(subscribe, snapshot, () => true);
}
