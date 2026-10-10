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
 * 차트에서 감정을 가르는 색. 감정마다 고정이고 순서(계약의 EmotionType 순서)로 정한다. 수가 바뀌어도
 * 색은 감정을 따라간다. 밝은 바탕에서 이웃한 색끼리 색각 이상에서도 구분되는 조합이다. 다만 바탕과의
 * 대비가 낮은 색이 있어, 차트는 언제나 이름과 숫자를 글자로 함께 보인다.
 */
export const EMOTION_COLORS: Record<EmotionType, string> = {
  ANXIETY: "#2a78d6",
  LETHARGY: "#eb6834",
  LONELINESS: "#1baf7a",
  SELF_DEPRECATION: "#eda100",
  IRRITATION: "#e87ba4",
};
