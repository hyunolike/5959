import { describe, expect, it } from "vitest";

import { COMMENT_TONE_LABELS, type CommentTone } from "./types";

const ALL_COMMENT_TONES: CommentTone[] = [
  "VENT_WITH_ME",
  "COMFORT_ME",
  "WARM_ADVICE",
  "MAKE_ME_LAUGH",
];

describe("COMMENT_TONE_LABELS", () => {
  it.each(ALL_COMMENT_TONES)("%s에 한국어 라벨이 있다", (tone) => {
    expect(COMMENT_TONE_LABELS[tone]).toEqual(expect.any(String));
    expect(COMMENT_TONE_LABELS[tone].length).toBeGreaterThan(0);
  });

  it("data-model.md:24의 표시 문구와 정확히 같다", () => {
    expect(COMMENT_TONE_LABELS).toEqual({
      VENT_WITH_ME: "대신 욕해주기",
      COMFORT_ME: "무조건 위로해주기",
      WARM_ADVICE: "따뜻한 조언해주기",
      MAKE_ME_LAUGH: "웃겨주기",
    });
  });
});
