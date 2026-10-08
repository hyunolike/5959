"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { knownUpToSeq, markAllReadInCache } from "../model/read-cache";

import { markAllNotificationsRead } from "./read-api";

export interface MarkAllReadVariables {
  /** 실시간 연결이 마지막으로 받은 이벤트 id. 연결 전이면 `null`. */
  lastEventId: number | null;
}

/**
 * 모두 읽음(US2-AC4). 누른 순간 화면이 아는 가장 큰 번호를 `upToSeq`로 보낸다. 그 번호까지만 읽음이
 * 되므로 누르는 사이에 새로 온 알림은 안 읽은 채 남는다. 응답이 오면 목록에서 그 번호 이하를 읽음으로
 * 바꾸고 배지를 서버가 센 수로 맞춘다. 실패하면 아무것도 바꾸지 않는다.
 */
export function useMarkAllReadMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ lastEventId }: MarkAllReadVariables) => {
      const upToSeq = knownUpToSeq(queryClient, lastEventId);
      const result = await markAllNotificationsRead(upToSeq);
      return { ...result, upToSeq };
    },
    onSuccess: ({ upToSeq, unreadCount }) =>
      markAllReadInCache(queryClient, upToSeq, unreadCount),
  });
}
