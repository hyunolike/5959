"use client";

import { m, useAnimationControls } from "framer-motion";
import { useEffect, type ReactNode } from "react";
import { useReducedMotionPreference } from "@/shared/lib";

/** API 성공 횟수가 바뀔 때만 반응한다. 낙관적 선택과 롤백은 성공 모션을 만들지 않는다. */
export function ReactionIcon({
  confirmation,
  children,
}: {
  confirmation: number;
  children: ReactNode;
}) {
  const controls = useAnimationControls();
  const reduce = useReducedMotionPreference();
  useEffect(() => {
    if (reduce) {
      controls.stop();
      controls.set({ scale: 1 });
      return;
    }
    if (confirmation > 0)
      void controls.start({
        scale: [1, 1.18, 1],
        transition: { duration: 0.22 },
      });
  }, [confirmation, controls, reduce]);
  return (
    <m.span
      aria-hidden
      initial={false}
      animate={controls}
      className="inline-flex shrink-0 items-center"
    >
      {children}
    </m.span>
  );
}
