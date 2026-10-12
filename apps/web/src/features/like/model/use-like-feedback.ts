"use client";

import { useRef, useState } from "react";

/** React가 요청 상태를 그리기 전의 연속 입력도 막고, 실제 성공만 알린다. */
export function useLikeFeedback() {
  const inFlight = useRef(false);
  const [confirmation, setConfirmation] = useState(0);
  const [message, setMessage] = useState("");
  return {
    confirmation,
    message,
    begin() {
      if (inFlight.current) return false;
      inFlight.current = true;
      setMessage("");
      return true;
    },
    success(liked: boolean) {
      setMessage(liked ? "공감했어요." : "공감을 취소했어요.");
      if (liked) setConfirmation((count) => count + 1);
    },
    settle() {
      inFlight.current = false;
    },
  };
}
