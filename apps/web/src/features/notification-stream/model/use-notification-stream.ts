"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";

import {
  fetchUnreadCount,
  newestUnreadCount,
  type Notification,
  type UnreadCount,
} from "@/entities/notification";
import { applyRaidLive, RAID_TOPIC, type RaidLive } from "@/entities/raid";
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
        // 쿼리를 거치지 않고 직접 받는다. 세션이 끝난 탭이 초점을 받을 때마다 한 번 살펴보는데,
        // 쿼리의 401은 전역 처리(로그인 화면으로 이동)를 부르기 때문이다. 받은 값은 캐시에 넣어 배지가 쓴다.
        const unread = await fetchUnreadCount();
        queryClient.setQueryData<UnreadCount>(
          QUERY_KEYS.unreadCount,
          (cached) => newestUnreadCount(cached, unread),
        );
        return unread.latestSeq;
      },
      onNotification: (event) => {
        applyNotificationEvent(queryClient, event);
        onNotificationRef.current(event.notification);
      },
      onUnreadCount: (event) => applyUnreadCountEvent(queryClient, event),
      // 레이드 화면이 주제를 골랐을 때만 온다. 값은 레이드 캐시에 합친다(006 research R8).
      onTopic: (topic, data) => {
        if (topic === RAID_TOPIC) {
          applyRaidLive(queryClient, data as RaidLive);
        }
      },
      onReconnected: () =>
        void queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.unreadCount,
        }),
    });

    return stop;
  }, [queryClient]);

  return useNotificationStreamStatus();
}
