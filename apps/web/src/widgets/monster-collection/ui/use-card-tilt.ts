"use client";

import type { PointerEvent } from "react";

/** 카드가 기우는 가장 큰 각도. */
const MAX_TILT_DEG = 12;
const TILT_PROPERTIES = ["--card-x", "--card-y", "--card-rx", "--card-ry"];

function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

/**
 * 크게 본 카드를 포인터가 있는 쪽으로 기울이고 빛을 따라 옮긴다(010 US3-AC2, research R7). 값은 CSS 변수로만 넘기고
 * 그리는 것은 `globals.css`의 `.ogu-card`가 한다. 렌더를 다시 하지 않는다. 움직임 줄이기가 켜져 있으면 아무것도
 * 하지 않는다(US3-AC4). 포인터가 떠나면 변수를 지워 가운데로 돌아온다.
 */
export function useCardTilt() {
  return {
    onPointerMove(event: PointerEvent<HTMLElement>) {
      if (prefersReducedMotion()) return;
      const target = event.currentTarget;
      const rect = target.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return;
      const x = clamp((event.clientX - rect.left) / rect.width);
      const y = clamp((event.clientY - rect.top) / rect.height);
      target.style.setProperty("--card-x", `${Math.round(x * 100)}%`);
      target.style.setProperty("--card-y", `${Math.round(y * 100)}%`);
      // 포인터가 있는 쪽이 눌려 들어가 보이게 기울인다.
      target.style.setProperty(
        "--card-rx",
        `${round((0.5 - y) * MAX_TILT_DEG)}deg`,
      );
      target.style.setProperty(
        "--card-ry",
        `${round((x - 0.5) * MAX_TILT_DEG)}deg`,
      );
    },
    onPointerLeave(event: PointerEvent<HTMLElement>) {
      for (const property of TILT_PROPERTIES) {
        event.currentTarget.style.removeProperty(property);
      }
    },
  };
}

function clamp(value: number): number {
  return Math.min(1, Math.max(0, value));
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
