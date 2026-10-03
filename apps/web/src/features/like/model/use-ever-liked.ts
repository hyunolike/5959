import { useState } from "react";

/**
 * 이 화면에서 공감한 상태를 한 번이라도 봤는지. 봤다면 그 공감은 이미 HP에 반영됐으므로
 * (취소해도 기록은 남는다) 다시 공감할 때 HP를 낙관적으로 줄이지 않는다.
 */
export function useEverLiked(likedByMe: boolean): boolean {
  const [everLiked, setEverLiked] = useState(likedByMe);
  if (likedByMe && !everLiked) {
    setEverLiked(true);
  }
  return everLiked || likedByMe;
}
