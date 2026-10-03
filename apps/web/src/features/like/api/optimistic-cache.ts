import type { QueryClient } from "@tanstack/react-query";

import type { PostDetail } from "@/entities/post";
import { QUERY_KEYS } from "@/shared/config";

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

/**
 * 공격이 끝나면(성공이든 실패든) 상세와 댓글 목록(`["posts", id]` 아래 전부)을 다시
 * 불러와 서버 값이 이기게 하고, 피드는 다음에 볼 때 다시 불러오도록 낡은 것으로 표시한다.
 */
export function refreshAfterAttack(
  queryClient: QueryClient,
  postId: number,
): void {
  void queryClient.invalidateQueries({
    queryKey: QUERY_KEYS.postDetail(postId),
  });
  void queryClient.invalidateQueries({
    queryKey: QUERY_KEYS.allFeeds,
    refetchType: "none",
  });
}
