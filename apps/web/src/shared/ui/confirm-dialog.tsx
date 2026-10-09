"use client";

import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  type ReactNode,
} from "react";

import { Button } from "./button";

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * 되돌릴 수 없는 동작 전에 묻는 화면 안 대화상자(`window.confirm` 대신).
 * 열리면 취소 버튼에 초점을 두고, Tab과 Shift+Tab은 대화상자 안에서만 돈다(aria-modal).
 * Escape나 바깥 배경을 누르면 취소한다. 닫히면 연 요소로 초점을 돌려주고, 그 요소가
 * 사라질 수 있으면(지운 댓글의 메뉴 등) `finalFocus`로 옮길 곳을 준다.
 * `pending`이면 두 버튼을 잠근다(요청이 끝나기 전에 다시 누르지 않게).
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = "취소",
  pending = false,
  error,
  finalFocus,
  children,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  description?: string;
  confirmLabel: string;
  cancelLabel?: string;
  pending?: boolean;
  /** 확인한 동작이 실패했을 때 대화상자 안에 보여 줄 문구. */
  error?: string | null;
  /** 닫힐 때 초점을 옮길 요소. 없거나 null을 돌려주면 연 요소(아직 화면에 있으면)로 돌려준다. */
  finalFocus?: () => HTMLElement | null;
  /** 설명 아래에 둘 입력(고를 것, 적을 것). 대화상자 안의 초점 순환에 함께 든다. */
  children?: ReactNode;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  // 부모가 다시 그릴 때마다 새 함수가 와도 아래 effect가 다시 돌지 않게(초점이 취소로 튀지 않게) ref로 읽는다.
  // layout effect라 같은 커밋에서 닫힐 때도 cleanup이 최신 값을 본다.
  const latest = useRef({ onCancel, pending, finalFocus });
  useLayoutEffect(() => {
    latest.current = { onCancel, pending, finalFocus };
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    const opener =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    cancelRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        if (!latest.current.pending) {
          event.preventDefault();
          latest.current.onCancel();
        }
        return;
      }
      const dialog = dialogRef.current;
      if (event.key !== "Tab" || !dialog) {
        return;
      }
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>(FOCUSABLE),
      );
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement;
      if (!first || !last) {
        event.preventDefault();
        dialog.focus();
      } else if (
        event.shiftKey &&
        (active === first || !dialog.contains(active))
      ) {
        event.preventDefault();
        last.focus();
      } else if (
        !event.shiftKey &&
        (active === last || !dialog.contains(active))
      ) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      const target =
        latest.current.finalFocus?.() ?? (opener?.isConnected ? opener : null);
      target?.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-neutral-950/40 p-4"
      onClick={(event) => {
        if (event.target === event.currentTarget && !pending) {
          onCancel();
        }
      }}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        className="flex w-full max-w-sm flex-col gap-4 rounded-lg bg-white p-6 shadow-lg"
      >
        <div className="flex flex-col gap-1">
          <h2 id={titleId} className="text-base font-semibold text-neutral-900">
            {title}
          </h2>
          {description ? (
            <p id={descriptionId} className="text-sm text-neutral-600">
              {description}
            </p>
          ) : null}
        </div>
        {children}
        {error ? (
          <p role="alert" className="text-sm text-red-600">
            {error}
          </p>
        ) : null}
        <div className="flex justify-end gap-2">
          <Button
            ref={cancelRef}
            type="button"
            variant="outline"
            size="sm"
            disabled={pending}
            onClick={onCancel}
          >
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant="destructive"
            size="sm"
            disabled={pending}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
