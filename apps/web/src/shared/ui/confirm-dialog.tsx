"use client";

import { useEffect, useId, useRef } from "react";

import { Button } from "./button";

/**
 * 되돌릴 수 없는 동작 전에 묻는 화면 안 대화상자(`window.confirm` 대신).
 * 열리면 취소 버튼에 초점을 두고, Escape나 바깥 배경을 누르면 취소한다.
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
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    cancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !pending) {
        onCancel();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, pending, onCancel]);

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
