"use client";

import { domAnimation, LazyMotion, MotionConfig } from "framer-motion";
import type { ReactNode } from "react";

/** drag/layout 기능 묶음은 가져오지 않고 선택·응답·닫기 반응에 필요한 기능만 사용한다. */
export function InteractionProvider({ children }: { children: ReactNode }) {
  return (
    <MotionConfig reducedMotion="user">
      <LazyMotion features={domAnimation} strict>
        {children}
      </LazyMotion>
    </MotionConfig>
  );
}
