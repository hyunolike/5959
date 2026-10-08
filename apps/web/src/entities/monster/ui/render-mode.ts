"use client";

import { useSyncExternalStore } from "react";

const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";

let webglSupport: boolean | undefined;

/** WebGL 컨텍스트를 만들 수 있는지 한 번만 확인하고 기억한다. 확인용 컨텍스트는 바로 놓는다. */
function canUseWebGL(): boolean {
  if (webglSupport === undefined) {
    try {
      const canvas = document.createElement("canvas");
      const gl = (canvas.getContext("webgl2") ??
        canvas.getContext("webgl")) as WebGLRenderingContext | null;
      webglSupport = gl != null;
      gl?.getExtension("WEBGL_lose_context")?.loseContext();
    } catch {
      webglSupport = false;
    }
  }
  return webglSupport;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia(REDUCED_MOTION).matches
  );
}

function subscribe(onChange: () => void): () => void {
  if (typeof window.matchMedia !== "function") {
    return () => {};
  }
  const query = window.matchMedia(REDUCED_MOTION);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

function canRender3D(): boolean {
  return !prefersReducedMotion() && canUseWebGL();
}

/**
 * 상세 화면에서 3D 장면을 쓸 수 있는지(US5-AC4). WebGL을 쓸 수 없거나 움직임 줄이기가
 * 켜져 있으면 거짓이다. 서버 렌더링과 첫 하이드레이션은 정지 이미지로 그린다.
 * 움직임 줄이기 설정이 바뀌면 다시 고른다.
 */
export function useCanRender3D(): boolean {
  return useSyncExternalStore(subscribe, canRender3D, () => false);
}
