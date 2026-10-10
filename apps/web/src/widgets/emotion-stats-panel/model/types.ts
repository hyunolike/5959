import type { components } from "@/shared/api";

export type EmotionStats = components["schemas"]["EmotionStats"];
export type EmotionShare = components["schemas"]["EmotionShare"];
export type WeeklyEmotionCount = components["schemas"]["WeeklyEmotionCount"];
export type EmotionType = components["schemas"]["EmotionType"];

/** 감정의 한국어 이름표(몬스터 엔티티의 `EMOTION_LABELS`). 차트는 이름표를 받아서만 쓴다. */
export type EmotionLabels = Record<EmotionType, string>;

/** 차트에서 감정을 가르는 색. 주간 리포트(008)도 같은 색을 써서 몬스터 엔티티에 둔다. */
export { EMOTION_COLORS } from "@/entities/monster";
