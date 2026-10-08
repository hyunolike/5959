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
