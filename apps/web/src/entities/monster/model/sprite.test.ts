import { describe, expect, it } from "vitest";

import { ANXIETY_LOOKS, monsterLook, spritePath } from "./sprite";

describe("monsterLook", () => {
  it("017 US1-AC1 불안은 글 번호를 4로 나눈 나머지로 네 모습 가운데 하나를 고른다", () => {
    expect(ANXIETY_LOOKS).toBe(4);
    expect(
      [100, 101, 102, 103].map((id) => monsterLook("ANXIETY", id)),
    ).toEqual([0, 1, 2, 3]);
    expect(monsterLook("ANXIETY", 104)).toBe(0);
  });

  it("017 US1-AC4 다른 감정과 글 번호가 없는 자리는 지금 모습이다", () => {
    expect(monsterLook("LETHARGY", 101)).toBe(0);
    expect(monsterLook("ANXIETY")).toBe(0);
  });
});

describe("spritePath", () => {
  it("017 US1-AC3 새 모습은 단계마다 그 모습의 그림을 쓴다", () => {
    expect(spritePath("ANXIETY", "full", false, 1)).toBe(
      "/monsters/anxiety-v2-full.webp",
    );
    expect(spritePath("ANXIETY", "defeated", false, 3)).toBe(
      "/monsters/anxiety-v4-defeated.webp",
    );
  });

  it("017 US1-AC4 모습 0, 다른 감정, 보스는 지금 그림이다", () => {
    expect(spritePath("ANXIETY", "hurt")).toBe("/monsters/anxiety-hurt.webp");
    expect(spritePath("ANXIETY", "hurt", false, 0)).toBe(
      "/monsters/anxiety-hurt.webp",
    );
    expect(spritePath("ANXIETY", "full", true, 2)).toBe(
      "/monsters/boss-anxiety.webp",
    );
  });
});
