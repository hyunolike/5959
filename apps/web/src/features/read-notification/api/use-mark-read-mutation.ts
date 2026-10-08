"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import { markReadInCache } from "../model/read-cache";

import { markNotificationRead } from "./read-api";

/**
 * 알림 하나 읽음(US2-AC3). 응답 전에 목록과 배지를 바꾸고, 실패하면 되돌린다.
 *
 * - 끝나면 안 읽은 수를 낡은 것으로 표시해 서버 값으로 맞춘다. 실시간 연결이 열려 있으면 서버의
 *   `unread-count` 이벤트가 먼저 맞추지만, 끊겨 있을 때도 배지가 맞아야 한다.
 * - 404면 그 알림이 이제 없다(보관 기간이 지남). 목록도 다시 받는다.
 * - 누른 뒤 글 상세로 이동해 이 훅을 쓴 화면이 사라져도 요청과 되돌리기는 끝까지 간다.
 */
export function useMarkReadMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (notificationId: number) =>
      markNotificationRead(notificationId),
    onMutate: async (notificationId) => {
      // 먼저 나간 조회가 늦게 돌아와 낙관적 값을 덮지 않게 한다.
      await Promise.all([
        queryClient.cancelQueries({
          queryKey: QUERY_KEYS.notifications,
          exact: true,
        }),
        queryClient.cancelQueries({
          queryKey: QUERY_KEYS.unreadCount,
          exact: true,
        }),
      ]);
      return { rollback: markReadInCache(queryClient, notificationId) };
    },
    onError: (error, _notificationId, context) => {
      context?.rollback();
      if (error instanceof ApiError && error.status === 404) {
        void queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.notifications,
          exact: true,
        });
      }
    },
    onSettled: () =>
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.unreadCount,
        exact: true,
      }),
  });
}
