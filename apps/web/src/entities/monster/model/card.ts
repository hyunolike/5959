import type { components } from "@/shared/api";

import { emotionLabel } from "./types";

/** 도감의 카드 한 장(010). 처치된 몬스터 하나를 내가 본 모습이다. 글의 본문과 글쓴이는 없다. */
export type MonsterCard = components["schemas"]["MonsterCard"];
export type MonsterCardPage = components["schemas"]["MonsterCardPage"];
export type MonsterCardSummary = components["schemas"]["MonsterCardSummary"];
export type MonsterCardGrade = components["schemas"]["MonsterCardGrade"];
export type MonsterCardRole = components["schemas"]["MonsterCardRole"];

/** 등급의 이름. 등급은 뽑기가 아니라 몬스터의 세기로 정해진다(010 research R5). */
export const CARD_GRADE_LABELS: Record<MonsterCardGrade, string> = {
  COMMON: "일반",
  RARE: "희귀",
  LEGENDARY: "전설",
};

export const CARD_ROLE_LABELS: Record<MonsterCardRole, string> = {
  MINE: "내 몬스터",
  TOGETHER: "함께 물리침",
};

const SEOUL = "Asia/Seoul";
const SHORT_DATE = new Intl.DateTimeFormat("ko-KR", {
  timeZone: SEOUL,
  month: "long",
  day: "numeric",
});
const LONG_DATE = new Intl.DateTimeFormat("ko-KR", {
  timeZone: SEOUL,
  year: "numeric",
  month: "long",
  day: "numeric",
});

/** 물리친 날의 짧은 꼴. 예: "10월 12일". 한국 시간이다. */
export function cardDateShort(defeatedAt: string): string {
  return SHORT_DATE.format(new Date(defeatedAt));
}

/** 물리친 날의 긴 꼴. 예: "2026년 10월 12일". */
export function cardDateLong(defeatedAt: string): string {
  return LONG_DATE.format(new Date(defeatedAt));
}

/** 카드를 가리키는 이름. 크게 본 카드의 제목에 쓴다. 예: "불안 카드 No.128". */
export function cardTitle(card: Pick<MonsterCard, "emotion" | "cardNo">) {
  return `${emotionLabel(card.emotion)} 카드 No.${card.cardNo}`;
}
