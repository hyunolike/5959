"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import { fetchUnreadCount, type Notification } from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

import { applyNotificationEvent, applyUnreadCountEvent } from "./cache-sync";
import {
  notificationStreamStore,
  useNotificationStreamStatus,
  type StreamStatus,
} from "./store";

/**
 * 마운트된 동안 실시간 알림 스트림을 열어 둔다(research R14). 탭 하나에 연결은 하나다.
 *
 * - 처음에는 안 읽은 수를 새로 받아 그 `latestSeq`부터 붙는다.
 * - 이벤트는 TanStack Query 캐시(목록, 안 읽은 수)에 반영하고, 새 알림은 `onNotification`으로도 알린다.
 * - 다시 붙으면 안 읽은 수를 새로 받는다(`unread-count` 이벤트는 다시 오지 않는다).
 * - 언마운트되면 닫는다. 로그아웃은 화면이 `stopNotificationStream`을 불러 바로 닫는다.
 */
export function useNotificationStream(
  onNotification: (notification: Notification) => void,
): StreamStatus {
  const queryClient = useQueryClient();
  // 부모가 다시 그려 새 함수가 와도 연결을 다시 열지 않게 ref로 읽는다.
  const onNotificationRef = useRef(onNotification);
  useEffect(() => {
    onNotificationRef.current = onNotification;
  });

  useEffect(() => {
    const { start, stop } = notificationStreamStore.getState();
    start({
      loadLatestSeq: async () => {
        const unread = await queryClient.fetchQuery({
          queryKey: QUERY_KEYS.unreadCount,
          queryFn: () => fetchUnreadCount(),
          staleTime: 0,
        });
        return unread.latestSeq;
      },
      onNotification: (event) => {
        applyNotificationEvent(queryClient, event);
        onNotificationRef.current(event.notification);
      },
      onUnreadCount: (event) => applyUnreadCountEvent(queryClient, event),
      onReconnected: () =>
        void queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.unreadCount,
        }),
    });

    return stop;
  }, [queryClient]);

  return useNotificationStreamStatus();
}
