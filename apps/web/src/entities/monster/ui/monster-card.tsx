import Image from "next/image";
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

import {
  CARD_GRADE_LABELS,
  CARD_ROLE_LABELS,
  cardDateLong,
  cardDateShort,
  type MonsterCard as MonsterCardData,
} from "../model/card";
import { spritePath } from "../model/sprite";
import { emotionLabel } from "../model/types";

/**
 * 도감의 카드 한 장(010 US2). 테두리는 감정의 진한 색, 그림 자리는 옅은 색이다. 반짝임은 `globals.css`의
 * `.ogu-card`가 `data-grade`를 보고 그린다. 등급과 감정은 글자로도 적는다(색과 반짝임만으로 알리지 않는다).
 * 글자가 든 줄(`ogu-card-text`)은 반짝임 위에 그려, 빛이 글자의 색과 대비를 바꾸지 않는다.
 * `lg`는 크게 볼 때의 모습으로, 물리친 날을 길게 적고 내 몫을 한 줄 더 보인다.
 */
export function MonsterCard({
  card,
  size = "sm",
  className,
  ...props
}: Omit<ComponentProps<"div">, "children"> & {
  card: MonsterCardData;
  size?: "sm" | "lg";
}) {
  const large = size === "lg";
  return (
    <div
      data-emotion={card.emotion}
      data-grade={card.grade}
      {...props}
      className={cn(
        "ogu-card bg-emotion text-fg aspect-[5/7] w-full text-left",
        large ? "rounded-surface p-2.5" : "rounded-control p-1.5",
        className,
      )}
    >
      <div
        className={cn(
          "bg-surface flex h-full flex-col",
          large ? "rounded-control gap-2.5 p-3" : "rounded-inline gap-1.5 p-2",
        )}
      >
        <div className="ogu-card-text flex items-baseline justify-between gap-2">
          <span
            className={cn(
              "font-extrabold break-keep",
              large ? "text-title" : "text-body-sm",
            )}
          >
            {emotionLabel(card.emotion)}
          </span>
          <span
            className={cn(
              "text-fg-subtle shrink-0 tabular-nums",
              large ? "text-label" : "text-caption",
            )}
          >
            No.{card.cardNo}
          </span>
        </div>
        <div className="bg-emotion-tint rounded-inline flex min-h-0 flex-1 items-center justify-center p-2">
          <Image
            src={spritePath(card.emotion, "defeated")}
            alt=""
            width={512}
            height={512}
            sizes={large ? "280px" : "160px"}
            draggable={false}
            className="size-full object-contain select-none"
          />
        </div>
        <div
          className={cn(
            "ogu-card-text flex flex-wrap items-center justify-between gap-x-2 gap-y-1",
            large ? "text-label" : "text-caption",
          )}
        >
          <span className="bg-surface-sunken text-fg rounded-full px-2 py-0.5 font-bold">
            {CARD_GRADE_LABELS[card.grade]}
          </span>
          <span className="text-fg-muted tabular-nums">
            {large
              ? `${cardDateLong(card.defeatedAt)}에 물리침`
              : cardDateShort(card.defeatedAt)}
          </span>
        </div>
        <p
          className={cn(
            "ogu-card-text text-fg-muted font-medium",
            large ? "text-label" : "text-caption",
          )}
        >
          {CARD_ROLE_LABELS[card.role]}
        </p>
        {large ? <CardShare card={card} /> : null}
      </div>
      <span aria-hidden="true" className="ogu-card-shine" />
    </div>
  );
}

/** 이 몬스터에서 내가 한 몫. 내 몬스터면 함께해 준 회원 수, 함께 물리친 것이면 내가 줄인 HP다. */
function CardShare({ card }: { card: MonsterCardData }) {
  const mine = card.role === "MINE";
  return (
    <dl className="ogu-card-text bg-surface-sunken rounded-inline text-label flex items-center justify-between gap-2 px-2.5 py-1.5">
      <dt className="text-fg-muted">
        {mine ? "함께 물리친 회원" : "내가 준 피해"}
      </dt>
      <dd className="text-fg font-bold tabular-nums">
        {mine ? `${card.helperCount ?? 0}명` : card.myDamage}
      </dd>
    </dl>
  );
}
