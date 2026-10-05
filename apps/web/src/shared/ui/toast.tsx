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
 * 각 토스트는 뜬 지 4초 뒤 `onDismiss`로 닫히고, 누르면 `onClick`을 실행한 뒤 닫힌다. 마우스가 올라가 있거나
 * 초점이 안에 있는 동안은 시간이 멈춘다. 닫기 버튼("알림 닫기")이나 Escape로 바로 닫을 수 있다.
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

  // 마우스가 올라가 있거나 초점이 안에 있는 동안은 멈추고, 풀리면 남은 시간만큼 더 보여 준다.
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const paused = hovered || focused;
  const remainingRef = useRef(TOAST_DURATION_MS);

  useEffect(() => {
    if (paused) {
      return;
    }
    const startedAt = Date.now();
    const timer = setTimeout(
      () => dismissRef.current(toast.id),
      remainingRef.current,
    );
    return () => {
      clearTimeout(timer);
      remainingRef.current = Math.max(
        0,
        remainingRef.current - (Date.now() - startedAt),
      );
    };
  }, [paused, toast.id]);

  return (
    <div
      className="pointer-events-auto flex w-full items-start rounded-lg border border-neutral-200 bg-white shadow-lg"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setFocused(false);
        }
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          onDismiss(toast.id);
        }
      }}
    >
      <button
        type="button"
        onClick={() => {
          toast.onClick?.();
          onDismiss(toast.id);
        }}
        className={cn(
          "min-w-0 flex-1 rounded-l-lg px-4 py-3 text-left text-sm text-neutral-900",
          "hover:bg-neutral-50 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none",
        )}
      >
        {toast.message}
      </button>
      <button
        type="button"
        aria-label="알림 닫기"
        onClick={() => onDismiss(toast.id)}
        className="m-1 rounded-md px-2 py-2 text-sm text-neutral-500 hover:bg-neutral-100 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>
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
