import type { components } from "@/shared/api";

export type MonsterView = components["schemas"]["MonsterView"];
export type EmotionType = components["schemas"]["EmotionType"];

/** 감정 한국어 이름 (spec US1-AC4, data-model.md:62) */
export const EMOTION_LABELS: Record<EmotionType, string> = {
  ANXIETY: "불안",
  LETHARGY: "무기력",
  LONELINESS: "외로움",
  SELF_DEPRECATION: "자기비하",
  IRRITATION: "짜증",
};

/**
 * 차트에서 감정을 가르는 색. `globals.css`의 `[data-emotion]` 진한 색과 같다(types.test.ts가 지킨다).
 * 몬스터 그림의 색에서 출발해, 이 순서로 나란히 놓여도 색각 이상에서 구분되게 밝기를 벌렸다
 * (검증 수치는 DESIGN_SYSTEM.md). 그래도 차트는 언제나 이름과 숫자를 글자로 함께 보인다.
 */
export const EMOTION_COLORS: Record<EmotionType, string> = {
  ANXIETY: "#6d28d9",
  LETHARGY: "#7187e0",
  LONELINESS: "#45c6dd",
  SELF_DEPRECATION: "#a3b03c",
  IRRITATION: "#cf2f38",
};

/**
 * 감정의 한국어 이름. 실시간으로 받은 값이 계약에 없는 감정이어도 화면에 "undefined"가
 * 보이지 않게 "감정"으로 대신한다.
 */
export function emotionLabel(emotion: EmotionType | string): string {
  return EMOTION_LABELS[emotion as EmotionType] ?? "감정";
}

/** HP를 쉼표와 함께 적는다. 값이 없거나 숫자가 아니면(계약을 어긴 값) 0으로 적어 화면이 죽지 않게 한다. */
export function formatHp(value: number): string {
  return Number.isFinite(value) ? value.toLocaleString("ko-KR") : "0";
}
