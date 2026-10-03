"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";

import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

import { deletePost } from "./manage-post-api";

/**
 * 글 삭제(US4-AC2). 성공하면 피드(`["feed"]`)를 낡은 것으로 표시하고, 상세에서 아직 진행 중인
 * 요청(분석 중 폴링 등)은 취소한다. 상세 캐시(`["posts", id]`와 그 아래 댓글)는 지금 지우지 않는다.
 * 상세 화면이 아직 떠 있는 동안 지우면 그 화면이 다시 불러와 404("삭제된 글이에요")가 잠깐 보이기
 * 때문이다. 화면을 떠난 뒤 `forgetDeletedPost`로 지운다. 분석 중 폴링은 상세 쿼리가
 * `MUTATION_KEYS.deletePost`로 이 뮤테이션을 보고 지우는 동안과 지운 뒤에 멈춘다.
 */
export function useDeletePostMutation(postId: number) {
  const queryClient = useQueryClient();

  const mutation = useMutation({
    // 상세 쿼리가 이 키로 삭제 중인지 보고 분석 중 폴링을 멈춘다.
    mutationKey: MUTATION_KEYS.deletePost(postId),
    mutationFn: () => deletePost(postId),
    onSuccess: async () => {
      await queryClient.cancelQueries({
        queryKey: QUERY_KEYS.postDetail(postId),
      });
      await queryClient.invalidateQueries({ queryKey: QUERY_KEYS.allFeeds });
    },
  });

  const forgetDeletedPost = useCallback(() => {
    queryClient.removeQueries({ queryKey: QUERY_KEYS.postDetail(postId) });
  }, [queryClient, postId]);

  return { ...mutation, forgetDeletedPost };
}
