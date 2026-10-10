"use client";

import { useEffect, useRef, type RefObject } from "react";

/** 맞는 반응의 길이. */
export const HIT_REACTION_MS = 400;

const SHAKE: Keyframe[] = [
  { transform: "translateX(0)" },
  { transform: "translateX(-6px)" },
  { transform: "translateX(5px)" },
  { transform: "translateX(-3px)" },
  { transform: "translateX(2px)" },
  { transform: "translateX(0)" },
];

function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

/**
 * HP가 줄 때마다 `target`을 흔든다(US3-AC10, FR-017). 낙관적 감소든 다시 불러온 서버 값이든
 * HP가 줄면 맞은 것이고, 늘면(낙관적 값을 서버 값으로 맞출 때) 흔들지 않는다.
 * 움직임 줄이기가 켜져 있으면 흔들지 않는다. HP 숫자와 바는 그대로 바로 바뀐다.
 */
export function useHitReaction(
  hp: number,
  target: RefObject<HTMLElement | null>,
): void {
  const previousHp = useRef(hp);

  useEffect(() => {
    const hit = hp < previousHp.current;
    previousHp.current = hp;
    const element = target.current;
    if (!hit || !element || typeof element.animate !== "function") {
      return;
    }
    if (prefersReducedMotion()) {
      return;
    }
    element.animate(SHAKE, { duration: HIT_REACTION_MS, easing: "ease-out" });
  }, [hp, target]);
}
