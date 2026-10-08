"use client";

import { useEffect, useRef } from "react";

/**
 * 목록 끝에 둔 표지가 화면에 들어오면 [onReach]를 부른다. [enabled]가 거짓이면 보지 않는다
 * (다음 쪽이 없거나 불러오는 중). 돌려준 ref를 목록 끝의 빈 요소에 붙인다.
 */
export function useInfiniteScroll(onReach: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement | null>(null);
  const onReachRef = useRef(onReach);

  useEffect(() => {
    onReachRef.current = onReach;
  });

  useEffect(() => {
    const target = ref.current;
    if (!enabled || !target) {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          onReachRef.current();
        }
      },
      { rootMargin: "200px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [enabled]);

  return ref;
}
