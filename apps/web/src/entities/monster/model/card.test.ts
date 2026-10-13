import { describe, expect, it } from "vitest";

import {
  CARD_GRADE_LABELS,
  CARD_ROLE_LABELS,
  cardDateLong,
  cardDateShort,
  cardTitle,
  type MonsterCard,
} from "./card";

const CARD: MonsterCard = {
  cardNo: 128,
  emotion: "ANXIETY",
  grade: "LEGENDARY",
  role: "MINE",
  defeatedAt: "2026-10-12T15:30:00Z",
  myDamage: 0,
  helperCount: 6,
  postId: 10,
};

describe("몬스터 카드의 글자", () => {
  it("US2-AC2 등급의 이름은 일반, 희귀, 전설이다", () => {
    expect(CARD_GRADE_LABELS).toEqual({
      COMMON: "일반",
      RARE: "희귀",
      LEGENDARY: "전설",
    });
  });

  it("US1-AC1 내 몬스터와 함께 물리친 것을 글자로 가른다", () => {
    expect(CARD_ROLE_LABELS).toEqual({
      MINE: "내 몬스터",
      TOGETHER: "함께 물리침",
    });
  });

  it("US2-AC1 물리친 날은 한국 시간으로 적는다", () => {
    // 15:30Z는 한국 시간으로 다음 날 0시 30분이다.
    expect(cardDateShort(CARD.defeatedAt)).toBe("10월 13일");
    expect(cardDateLong(CARD.defeatedAt)).toBe("2026년 10월 13일");
  });

  it("US3-AC5 카드의 이름은 감정과 번호다", () => {
    expect(cardTitle(CARD)).toBe("불안 카드 No.128");
  });
});
