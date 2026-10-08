import { describe, expect, it } from "vitest";

import type { PostDetail } from "@/entities/post";

import {
  ATTACK_DAMAGE,
  applyOptimisticAttack,
  optimisticMonster,
} from "./optimistic-hp";

const ALIVE = {
  emotion: "ANXIETY",
  hp: 10,
  maxHp: 10,
  status: "ALIVE",
} as const;

function detail(overrides: Partial<PostDetail> = {}): PostDetail {
  return {
    postId: 7,
    author: {
      id: 1,
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
    },
    content: "내일 발표가 걱정돼요",
    commentTone: "COMFORT_ME",
    analysisStatus: "ANALYZED",
    monster: ALIVE,
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    mine: false,
    myCommentCounted: false,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

/** data-model.md 반영 규칙, plan.md Summary: 공감 −1, 첫 댓글 −3, 댓글 공감 −1. */
describe("ATTACK_DAMAGE", () => {
  it("공감은 1, 첫 댓글은 3, 댓글 공감은 1이다", () => {
    expect(ATTACK_DAMAGE).toEqual({
      POST_LIKE: 1,
      COMMENT: 3,
      COMMENT_LIKE: 1,
    });
  });
});

describe("optimisticMonster", () => {
  it("US3-AC1 다른 회원의 글에 공감하면 HP가 1 준다", () => {
    expect(optimisticMonster(detail(), "POST_LIKE")).toEqual({
      ...ALIVE,
      hp: 9,
    });
  });

  it("US3-AC2 다른 회원의 글에 처음 댓글을 달면 HP가 3 준다", () => {
    expect(optimisticMonster(detail(), "COMMENT")).toEqual({
      ...ALIVE,
      hp: 7,
    });
  });

  it("US3-AC4 다른 회원의 글에서 댓글에 공감하면 HP가 1 준다", () => {
    expect(optimisticMonster(detail(), "COMMENT_LIKE")).toEqual({
      ...ALIVE,
      hp: 9,
    });
  });

  it.each(["POST_LIKE", "COMMENT", "COMMENT_LIKE"] as const)(
    "US3-AC8 작성자의 %s는 HP를 줄이지 않는다",
    (attack) => {
      expect(optimisticMonster(detail({ mine: true }), attack)).toBeNull();
    },
  );

  it.each(["POST_LIKE", "COMMENT", "COMMENT_LIKE"] as const)(
    "몬스터가 없으면(분석 중) %s는 HP를 줄이지 않는다",
    (attack) => {
      expect(
        optimisticMonster(
          detail({ analysisStatus: "PENDING", monster: null }),
          attack,
        ),
      ).toBeNull();
    },
  );

  it("US3-AC2 내 댓글이 이미 HP에 반영됐으면 두 번째 댓글은 HP를 줄이지 않는다", () => {
    expect(
      optimisticMonster(detail({ myCommentCounted: true }), "COMMENT"),
    ).toBeNull();
  });

  it("이미 반영된 공감(취소 뒤 다시 공감)은 HP를 줄이지 않는다", () => {
    expect(
      optimisticMonster(detail(), "POST_LIKE", { alreadyApplied: true }),
    ).toBeNull();
    expect(
      optimisticMonster(detail(), "COMMENT_LIKE", { alreadyApplied: true }),
    ).toBeNull();
  });

  it.each(["POST_LIKE", "COMMENT", "COMMENT_LIKE"] as const)(
    "US3-AC5 처치된 몬스터에는 %s가 HP를 줄이지 않는다",
    (attack) => {
      expect(
        optimisticMonster(
          detail({ monster: { ...ALIVE, hp: 0, status: "DEFEATED" } }),
          attack,
        ),
      ).toBeNull();
    },
  );

  it("US3-AC5 HP는 0 아래로 내려가지 않고, 0이 되면 처치된다", () => {
    expect(
      optimisticMonster(detail({ monster: { ...ALIVE, hp: 2 } }), "COMMENT"),
    ).toEqual({ ...ALIVE, hp: 0, status: "DEFEATED" });
    expect(
      optimisticMonster(detail({ monster: { ...ALIVE, hp: 1 } }), "POST_LIKE"),
    ).toEqual({ ...ALIVE, hp: 0, status: "DEFEATED" });
  });
});

describe("applyOptimisticAttack", () => {
  it("US3-AC10 몬스터 HP를 줄인 상세를 돌려준다", () => {
    expect(applyOptimisticAttack(detail(), "POST_LIKE").monster).toEqual({
      ...ALIVE,
      hp: 9,
    });
  });

  it("US3-AC2 첫 댓글을 반영하면 내 댓글이 반영됐다고 표시해 두 번째 댓글은 줄이지 않는다", () => {
    const once = applyOptimisticAttack(detail(), "COMMENT");
    expect(once.myCommentCounted).toBe(true);
    const twice = applyOptimisticAttack(once, "COMMENT");
    expect(twice.monster?.hp).toBe(7);
  });

  it("줄지 않으면 같은 상세를 그대로 돌려준다", () => {
    const mine = detail({ mine: true });
    expect(applyOptimisticAttack(mine, "COMMENT")).toBe(mine);
  });
});
