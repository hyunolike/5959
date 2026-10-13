"use client";

import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { Button } from "./button";

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

let openingSequence = 0;
let historyCleanup: Promise<void> | null = null;
let cleanupTarget: { marker: string; openedUrl: string } | null = null;

/** 닫기의 비동기 history 이동이 끝나기 전에는 새 안내창의 entry를 만들지 않는다. */
function returnFromDialog(marker: string, openedUrl: string) {
  // 같은 새로고침 entry를 여러 닫힌 안내가 발견해도 뒤로 가기는 한 번만 한다.
  if (
    historyCleanup &&
    cleanupTarget?.marker === marker &&
    cleanupTarget.openedUrl === openedUrl
  )
    return;
  let finish!: () => void;
  const pending = new Promise<void>((resolve) => {
    finish = resolve;
  });
  historyCleanup = pending;
  cleanupTarget = { marker, openedUrl };
  const complete = () => {
    window.removeEventListener("popstate", complete);
    if (historyCleanup === pending) {
      historyCleanup = null;
      cleanupTarget = null;
    }
    finish();
  };
  window.addEventListener("popstate", complete, { once: true });
  queueMicrotask(() => {
    if (
      window.location.href !== openedUrl ||
      history.state?.__oguDialog !== marker
    )
      complete();
    else history.back();
  });
}

/**
 * 되돌릴 수 없는 동작 전에 묻는 화면 안 대화상자(`window.confirm` 대신).
 * 열리면 취소 버튼에 초점을 두고(`initialFocus="first"`면 안의 첫 입력에), Tab과 Shift+Tab은 대화상자 안에서만 돈다(aria-modal).
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
  role = "alertdialog",
  initialFocus = "cancel",
  presentation = "dialog",
  confirmVariant = "danger",
  dismissOnBack = false,
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
  role?: "dialog" | "alertdialog";
  /** 열릴 때 초점을 둘 곳. 고르거나 적을 것이 있는 대화상자는 `first`로 첫 입력에 둔다. */
  initialFocus?: "cancel" | "first";
  presentation?: "dialog" | "sheet";
  confirmVariant?: "primary" | "danger";
  /** 모바일 안내창에서 뒤로 가기는 페이지를 떠나기 전에 안내창을 닫는다. */
  dismissOnBack?: boolean;
  /** 설명 아래에 둘 입력(고를 것, 적을 것). 대화상자 안의 초점 순환에 함께 든다. */
  children?: ReactNode;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  // 첫 렌더의 오래된 marker만 잡아 같은 렌더의 다른 열린 안내와 구분한다.
  const staleHistoryMarker = useRef(
    typeof window !== "undefined" &&
      typeof history.state?.__oguDialog === "string" &&
      history.state.__oguDialog.startsWith("ogu-dialog-")
      ? (history.state.__oguDialog as string)
      : null,
  );
  // 부모가 다시 그릴 때마다 새 함수가 와도 아래 effect가 다시 돌지 않게(초점이 취소로 튀지 않게) ref로 읽는다.
  // layout effect라 같은 커밋에서 닫힐 때도 cleanup이 최신 값을 본다.
  const latest = useRef({ onCancel, pending, finalFocus, initialFocus });
  useLayoutEffect(() => {
    latest.current = { onCancel, pending, finalFocus, initialFocus };
  });

  useEffect(() => {
    if (!open) {
      const marker = staleHistoryMarker.current;
      staleHistoryMarker.current = null;
      if (dismissOnBack && marker)
        returnFromDialog(marker, window.location.href);
      return;
    }
    staleHistoryMarker.current = null;
    const opener =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    const background = Array.from(document.body.children)
      .filter(
        (element): element is HTMLElement =>
          element instanceof HTMLElement && element !== overlayRef.current,
      )
      .map((element) => ({ element, inert: element.inert }));
    for (const { element } of background) element.inert = true;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const marker = `ogu-dialog-${titleId}-${++openingSequence}`;
    const openedUrl = window.location.href;
    let active = true;
    let registered = false;
    const register = () => {
      if (!active) return;
      if (window.location.href !== openedUrl) {
        latest.current.onCancel();
        return;
      }
      const state = { ...history.state, __oguDialog: marker };
      // Forward로 돌아온 닫힌 안내창 entry는 다시 쌓지 않고 새 opening으로 교체한다.
      if (
        typeof history.state?.__oguDialog === "string" &&
        history.state.__oguDialog.startsWith("ogu-dialog-")
      )
        history.replaceState(state, "");
      else history.pushState(state, "");
      registered = true;
    };
    if (dismissOnBack) {
      if (historyCleanup) void historyCleanup.then(register);
      else register();
    }
    const onPopState = () => {
      if (
        registered &&
        history.state?.__oguDialog !== marker &&
        !latest.current.pending
      )
        latest.current.onCancel();
    };
    if (dismissOnBack) window.addEventListener("popstate", onPopState);
    const firstFocusable =
      latest.current.initialFocus === "first"
        ? dialogRef.current?.querySelector<HTMLElement>(FOCUSABLE)
        : null;
    (firstFocusable ?? cancelRef.current)?.focus();

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
      active = false;
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("popstate", onPopState);
      if (
        dismissOnBack &&
        registered &&
        window.location.href === openedUrl &&
        history.state?.__oguDialog === marker
      )
        returnFromDialog(marker, openedUrl);
      for (const { element, inert } of background) element.inert = inert;
      document.body.style.overflow = previousOverflow;
      const target =
        latest.current.finalFocus?.() ?? (opener?.isConnected ? opener : null);
      target?.focus();
    };
  }, [open, dismissOnBack, titleId]);

  if (!open) {
    return null;
  }

  return createPortal(
    <div
      ref={overlayRef}
      className={`bg-scrim fixed inset-0 z-50 flex justify-center ${presentation === "sheet" ? "items-end sm:items-center sm:p-4" : "items-center p-4"}`}
      onClick={(event) => {
        // 안내를 연 더블클릭의 두 번째 클릭이 새 배경을 바로 닫지 않게 한다.
        if (
          event.target === event.currentTarget &&
          event.detail <= 1 &&
          !pending
        ) {
          onCancel();
        }
      }}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        className={`bg-surface flex max-h-[85dvh] w-full max-w-sm flex-col gap-4 overflow-y-auto p-6 ${presentation === "sheet" ? "sm:rounded-surface rounded-t-surface pb-[max(1.5rem,env(safe-area-inset-bottom))]" : "rounded-surface"}`}
      >
        {/* 아래에서 올라온 면이라는 표시. 끌 수는 없어 장식으로만 둔다. 가운데 뜨는 넓은 화면에서는 뺀다. */}
        {presentation === "sheet" ? (
          <span
            data-sheet-grabber
            aria-hidden="true"
            className="bg-line -mt-3 h-1 w-9 shrink-0 self-center rounded-full sm:hidden"
          />
        ) : null}
        <div className="flex flex-col gap-1">
          <h2 id={titleId} className="text-fg text-base font-bold">
            {title}
          </h2>
          {description ? (
            <p id={descriptionId} className="text-fg-subtle text-sm break-keep">
              {description}
            </p>
          ) : null}
        </div>
        {children}
        {error ? (
          <p role="alert" className="text-danger text-sm">
            {error}
          </p>
        ) : null}
        <div
          className={
            presentation === "sheet"
              ? "grid grid-cols-2 gap-2"
              : "flex justify-end gap-2"
          }
        >
          <Button
            ref={cancelRef}
            type="button"
            variant="secondary"
            size={presentation === "sheet" ? "md" : "sm"}
            disabled={pending}
            onClick={onCancel}
          >
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant={confirmVariant}
            size={presentation === "sheet" ? "md" : "sm"}
            disabled={pending}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
