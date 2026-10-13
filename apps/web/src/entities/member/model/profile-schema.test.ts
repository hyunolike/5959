import { describe, expect, it } from "vitest";

import { NICKNAME_REASON_LABEL } from "./profile-schema";

describe("닉네임 안내", () => {
  it("US2-AC5 AI 친구와 헷갈리는 닉네임은 쓸 수 없다고 안내한다", () => {
    expect(NICKNAME_REASON_LABEL.RESERVED).toBe(
      "AI 친구와 헷갈릴 수 있어 쓸 수 없는 닉네임입니다.",
    );
  });

  it("확인 API가 주는 사유마다 안내가 있다", () => {
    expect(Object.keys(NICKNAME_REASON_LABEL).sort()).toEqual([
      "INVALID_FORMAT",
      "RESERVED",
      "TAKEN",
    ]);
  });
});
