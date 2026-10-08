import type { QueryClient } from "@tanstack/react-query";

import type { PostDetail } from "@/entities/post";
import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

import {
  applyOptimisticAttack,
  type AttackKind,
  type AttackOptions,
} from "../model/optimistic-hp";

const noop = () => {};

/**
 * 캐시의 글 상세에 공격을 낙관적으로 반영하고(research R6), 되돌리는 함수를 돌려준다.
 * 되돌리기는 그 사이 서버 값이 들어와 몬스터가 바뀌었으면 건드리지 않는다.
 * 줄일 것이 없거나 상세가 아직 없으면 아무것도 하지 않는다.
 */
export function attackOptimistically(
  queryClient: QueryClient,
  postId: number,
  attack: AttackKind,
  options?: AttackOptions,
): () => void {
  const key = QUERY_KEYS.postDetail(postId);
  const before = queryClient.getQueryData<PostDetail>(key);
  if (!before) {
    return noop;
  }
  const after = applyOptimisticAttack(before, attack, options);
  if (after === before) {
    return noop;
  }
  // 구조 공유(structural sharing) 때문에 캐시에 들어간 객체는 `after`와 다를 수 있다.
  const applied = queryClient.setQueryData<PostDetail>(key, after)?.monster;

  return () =>
    queryClient.setQueryData<PostDetail>(key, (current) =>
      current && current.monster === applied
        ? {
            ...current,
            monster: before.monster,
            myCommentCounted: before.myCommentCounted,
          }
        : current,
    );
}

/** 겹친 공격 중 댓글 목록을 바꾼 공격이 끼어 있던 글. 마지막 공격이 끝날 때 함께 다시 불러온다. */
const commentsToRefresh = new WeakMap<QueryClient, Set<number>>();

/**
 * 공격 하나가 끝났을 때(성공이든 실패든) 부른다. `onSettled` 안에서는 끝나는 공격도 아직
 * 진행 중으로 세므로, 이 글의 공격이 하나만 남았으면(`isMutating === 1`) 그게 마지막이다.
 *
 * - 마지막 공격이 아니면 다시 불러오지 않는다. 먼저 끝난 공격의 서버 값에는 아직 응답을
 *   기다리는 공격이 빠져 있어, HP가 올라갔다가 다시 내려가며 두 번 흔들린다.
 * - 마지막 공격이면 상세를 다시 불러와 서버 값이 이기게 한다. 그동안 댓글 목록을 바꾼
 *   공격(`refreshComments`)이 하나라도 있었으면 `["posts", id]` 아래 댓글까지, 아니면
 *   상세만(`exact`) 불러온다. 피드는 다음에 볼 때 다시 불러오도록 낡은 것으로 표시한다.
 */
export function settleAttack(
  queryClient: QueryClient,
  postId: number,
  { refreshComments }: { refreshComments: boolean },
): void {
  let pending = commentsToRefresh.get(queryClient);
  if (!pending) {
    pending = new Set();
    commentsToRefresh.set(queryClient, pending);
  }
  if (refreshComments) {
    pending.add(postId);
  }
  if (
    queryClient.isMutating({ mutationKey: MUTATION_KEYS.attack(postId) }) > 1
  ) {
    return;
  }

  const withComments = pending.delete(postId);
  void queryClient.invalidateQueries({
    queryKey: QUERY_KEYS.postDetail(postId),
    exact: !withComments,
  });
  void queryClient.invalidateQueries({
    queryKey: QUERY_KEYS.allFeeds,
    refetchType: "none",
  });
}
