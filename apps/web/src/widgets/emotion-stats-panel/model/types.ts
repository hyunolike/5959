import type { components } from "@/shared/api";

export type EmotionStats = components["schemas"]["EmotionStats"];
export type EmotionShare = components["schemas"]["EmotionShare"];
export type WeeklyEmotionCount = components["schemas"]["WeeklyEmotionCount"];
export type EmotionType = components["schemas"]["EmotionType"];

/** 감정의 한국어 이름표(몬스터 엔티티의 `EMOTION_LABELS`). 차트는 이름표를 받아서만 쓴다. */
export type EmotionLabels = Record<EmotionType, string>;

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
