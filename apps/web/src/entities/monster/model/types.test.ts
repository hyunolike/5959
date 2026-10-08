import { describe, expect, it } from "vitest";

import { EMOTION_LABELS, type EmotionType } from "./types";

const ALL_EMOTIONS: EmotionType[] = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
];

describe("EMOTION_LABELS", () => {
  it.each(ALL_EMOTIONS)("%s에 한국어 라벨이 있다", (emotion) => {
    expect(EMOTION_LABELS[emotion]).toEqual(expect.any(String));
    expect(EMOTION_LABELS[emotion].length).toBeGreaterThan(0);
  });

  it("US1-AC4의 감정 이름과 정확히 같다", () => {
    expect(EMOTION_LABELS).toEqual({
      ANXIETY: "불안",
      LETHARGY: "무기력",
      LONELINESS: "외로움",
      SELF_DEPRECATION: "자기비하",
      IRRITATION: "짜증",
    });
  });
});
