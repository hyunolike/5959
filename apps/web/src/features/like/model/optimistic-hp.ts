import type { MonsterView } from "@/entities/monster";
import type { PostDetail } from "@/entities/post";

/** 몬스터를 공격하는 행동. 서버 `monster_hp_log.action`과 같은 이름이다. */
export type AttackKind = "POST_LIKE" | "COMMENT" | "COMMENT_LIKE";

/** plan.md Summary: 공감 −1, 첫 댓글 −3, 댓글 공감 −1. */
export const ATTACK_DAMAGE: Record<AttackKind, number> = {
  POST_LIKE: 1,
  COMMENT: 3,
  COMMENT_LIKE: 1,
};

type AttackTarget = Pick<PostDetail, "mine" | "monster" | "myCommentCounted">;

export interface AttackOptions {
  /**
   * 이 공격이 이미 HP에 반영됐다고 화면이 알고 있으면 true. 이 화면에서 공감한 적이 있는
   * 대상(처음부터 공감해 둔 것, 공감한 뒤 취소한 것)에 다시 공감하는 경우다. 서버는 같은
   * 공감을 두 번 반영하지 않는다(data-model.md 반영 규칙 3).
   */
  alreadyApplied?: boolean;
}

/**
 * research R6: 공격 직후 보여 줄 몬스터. HP가 줄지 않으면 `null`이다.
 *
 * 서버의 반영 규칙(data-model.md)을 그대로 따른다.
 * 1. 글 작성자의 행동은 반영하지 않는다. 자기 글의 댓글에 공감해도 마찬가지다(US3-AC8).
 * 2. 몬스터가 없으면(분석 중) 반영하지 않는다. 몬스터가 생길 때 서버가 소급 반영한다.
 * 3. 이미 반영된 공격(두 번째 댓글, 취소 뒤 다시 공감)은 다시 줄이지 않는다.
 * 4. HP는 0에서 멈추고, 처치된 몬스터는 더 줄지 않는다(US3-AC5).
 *
 * 화면이 확신할 수 없는 경우(다른 방문에서 공감했다가 취소한 대상)는 줄여 보여 주고,
 * 이어서 상세를 다시 불러와 서버 값으로 맞춘다.
 */
export function optimisticMonster(
  target: AttackTarget,
  attack: AttackKind,
  { alreadyApplied = false }: AttackOptions = {},
): MonsterView | null {
  const { monster } = target;
  if (target.mine || !monster || monster.status === "DEFEATED") {
    return null;
  }
  if (alreadyApplied || (attack === "COMMENT" && target.myCommentCounted)) {
    return null;
  }

  const hp = Math.max(0, monster.hp - ATTACK_DAMAGE[attack]);
  return { ...monster, hp, status: hp === 0 ? "DEFEATED" : "ALIVE" };
}

/**
 * 공격을 낙관적으로 반영한 글 상세. 줄지 않으면 받은 객체를 그대로 돌려준다.
 * 첫 댓글을 반영하면 `myCommentCounted`도 켜서, 응답 전에 단 두 번째 댓글이 또 줄이지 않게 한다.
 */
export function applyOptimisticAttack(
  detail: PostDetail,
  attack: AttackKind,
  options?: AttackOptions,
): PostDetail {
  const monster = optimisticMonster(detail, attack, options);
  if (!monster) {
    return detail;
  }
  return {
    ...detail,
    monster,
    myCommentCounted: attack === "COMMENT" ? true : detail.myCommentCounted,
  };
}
