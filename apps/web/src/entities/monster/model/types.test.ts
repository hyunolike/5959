import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { EMOTION_COLORS, EMOTION_LABELS, type EmotionType } from "./types";

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

describe("EMOTION_COLORS", () => {
  it("DS-US1-AC3 차트의 감정 색은 globals.css의 감정 색과 같다", () => {
    const css = readFileSync(
      path.resolve(__dirname, "../../../core/styles/globals.css"),
      "utf8",
    );
    for (const [emotion, color] of Object.entries(EMOTION_COLORS)) {
      const block = css.match(
        new RegExp(`\\[data-emotion="${emotion}"\\]\\s*\\{([^}]*)\\}`),
      );
      expect(block, `${emotion}의 색 묶음`).not.toBeNull();
      expect(block![1]).toContain(`--emotion: ${color};`);
    }
  });
});
