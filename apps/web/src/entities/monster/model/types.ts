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
 * 차트에서 감정을 가르는 색. 몬스터 그림에서 뽑았고 `globals.css`의 `[data-emotion]` 값과 같다
 * (types.test.ts가 지킨다). 불안(보라)과 무기력(남색)은 색이 가까우니, 차트는 언제나 이름과 숫자를
 * 글자로 함께 보인다.
 */
export const EMOTION_COLORS: Record<EmotionType, string> = {
  ANXIETY: "#8b5cf6",
  LETHARGY: "#7187e0",
  LONELINESS: "#35b6d3",
  SELF_DEPRECATION: "#8a9a3c",
  IRRITATION: "#e5484d",
};
