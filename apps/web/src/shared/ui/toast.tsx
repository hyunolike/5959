"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { cn } from "@/shared/lib";

/** 토스트가 저절로 사라지기까지의 시간. */
export const TOAST_DURATION_MS = 4000;

export interface ToastItem {
  id: string;
  message: string;
  /** 누르면 실행한다. 실행한 뒤 토스트는 닫힌다. */
  onClick?: () => void;
}

/**
 * 화면 오른쪽 아래에 쌓이는 짧은 알림. `toasts`는 최신이 앞이고 화면에서도 최신이 위에 온다.
 * 각 토스트는 뜬 지 4초 뒤 `onDismiss`로 닫히고, 누르면 `onClick`을 실행한 뒤 닫힌다.
 * 읽어 주기 영역(`aria-live`)은 토스트가 없어도 남겨 두어 새 토스트를 화면 낭독기가 알린다.
 */
export function Toaster({
  toasts,
  onDismiss,
}: {
  toasts: readonly ToastItem[];
  onDismiss: (id: string) => void;
}) {
  return (
    <section
      aria-label="새 알림"
      aria-live="polite"
      className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2"
    >
      {toasts.map((toast) => (
        <Toast key={toast.id} toast={toast} onDismiss={onDismiss} />
      ))}
    </section>
  );
}

function Toast({
  toast,
  onDismiss,
}: {
  toast: ToastItem;
  onDismiss: (id: string) => void;
}) {
  // 부모가 다시 그려 새 함수가 와도 타이머를 다시 걸지 않게(4초가 늘어나지 않게) ref로 읽는다.
  const dismissRef = useRef(onDismiss);
  useEffect(() => {
    dismissRef.current = onDismiss;
  });

  useEffect(() => {
    const timer = setTimeout(
      () => dismissRef.current(toast.id),
      TOAST_DURATION_MS,
    );
    return () => clearTimeout(timer);
  }, [toast.id]);

  return (
    <button
      type="button"
      onClick={() => {
        toast.onClick?.();
        onDismiss(toast.id);
      }}
      className={cn(
        "pointer-events-auto w-full rounded-lg border border-neutral-200 bg-white px-4 py-3 text-left text-sm text-neutral-900 shadow-lg",
        "hover:bg-neutral-50 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none",
      )}
    >
      {toast.message}
    </button>
  );
}

let sequence = 0;

/** 토스트 목록 상태. `show`는 새 토스트를 맨 앞(화면에서 맨 위)에 더한다. */
export function useToasts() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const show = useCallback((toast: Omit<ToastItem, "id">) => {
    sequence += 1;
    const id = `toast-${sequence}`;
    setToasts((current) => [{ ...toast, id }, ...current]);
    return id;
  }, []);

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  return { toasts, show, dismiss };
}
