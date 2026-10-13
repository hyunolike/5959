"use client";

import Link from "next/link";
import { useEffect, useId, useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";

import {
  cardTitle,
  MonsterCard,
  type MonsterCardData,
} from "@/entities/monster";
import { Button } from "@/shared/ui";

import { useCardTilt } from "./use-card-tilt";

const FOCUSABLE =
  'button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])';

/**
 * 카드를 크게 보는 대화상자(010 US3, research R8). 열리면 닫기에 초점을 두고 Tab은 안에서만 돈다. Esc, 닫기,
 * 바깥을 눌러 닫고, 닫히면 열었던 카드로 초점이 돌아온다. 카드의 내용은 모두 글자라 화면 낭독기가 그대로 읽는다.
 */
export function CardDialog({
  card,
  onClose,
}: {
  card: MonsterCardData;
  onClose: () => void;
}) {
  const titleId = useId();
  const cardId = useId();
  const overlayRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const tilt = useCardTilt();
  // 부모가 다시 그려 새 함수가 와도 아래 effect가 다시 돌지 않게(초점이 튀지 않게) ref로 읽는다.
  const latestClose = useRef(onClose);
  useLayoutEffect(() => {
    latestClose.current = onClose;
  });

  useEffect(() => {
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
    closeRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        latestClose.current();
        return;
      }
      const dialog = dialogRef.current;
      if (event.key !== "Tab" || !dialog) return;
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>(FOCUSABLE),
      );
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !dialog.contains(active))) {
        event.preventDefault();
        last?.focus();
      } else if (
        !event.shiftKey &&
        (active === last || !dialog.contains(active))
      ) {
        event.preventDefault();
        first?.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      for (const { element, inert } of background) element.inert = inert;
      document.body.style.overflow = previousOverflow;
      if (opener?.isConnected) opener.focus();
    };
  }, []);

  return createPortal(
    <div
      ref={overlayRef}
      data-testid="card-dialog-backdrop"
      className="bg-scrim fixed inset-0 z-50 flex overflow-y-auto p-4"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={cardId}
        // 가운데 정렬은 여백으로 한다. items-center는 화면보다 긴 내용의 위쪽을 스크롤로도 닿지 못하게 자른다.
        className="m-auto flex w-full max-w-[18rem] flex-col gap-4"
      >
        <h2 id={titleId} className="sr-only">
          {cardTitle(card)}
        </h2>
        <MonsterCard
          id={cardId}
          card={card}
          size="lg"
          className="ogu-card-tilt"
          {...tilt}
        />
        <div className="flex gap-2">
          <Button asChild variant="outline" className="flex-1">
            <Link href={`/post/${card.postId}`}>글 보기</Link>
          </Button>
          <Button
            ref={closeRef}
            type="button"
            variant="primary"
            className="flex-1"
            onClick={onClose}
          >
            닫기
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
