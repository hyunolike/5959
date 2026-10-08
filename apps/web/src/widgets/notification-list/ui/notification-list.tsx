"use client";

import { useEffect, useRef, useState } from "react";

import {
  NotificationItem,
  uniqueNotifications,
  useNotificationsQuery,
  useUnreadCountQuery,
  type Notification,
} from "@/entities/notification";
import { DELETED_POST_NOTICE } from "@/entities/post";
import { useNotificationStreamLastEventId } from "@/features/notification-stream";
import {
  useMarkAllReadMutation,
  useMarkReadMutation,
} from "@/features/read-notification";
import { useInfiniteScroll } from "@/shared/lib";
import { Button, Card, Spinner } from "@/shared/ui";

const ALL_READ_NOTICE = "모든 알림을 읽음으로 표시했어요.";
const ALL_READ_FAILED_NOTICE =
  "모두 읽음 처리에 실패했어요. 잠시 후 다시 시도해주세요.";

/**
 * 알림 목록(US2): 최신순 20개씩 무한 스크롤, 하나 읽음, 모두 읽음, 삭제된 글 안내.
 *
 * - 알림을 누르면 읽음으로 바꾸고(응답 전에 반영) 그 글 상세로 간다(US2-AC3). 이동은 항목의 링크가 한다.
 * - 글이 지워진 알림은 "삭제된 글"로 보이고, 누르면 이동하지 않고 안내를 띄운다(US2-AC5). 본 것이므로
 *   읽음으로 바꾼다. 목록을 받은 뒤 지워진 글은 상세 화면이 같은 안내를 보인다.
 * - 모두 읽음은 누른 순간까지 본 번호만 읽음으로 바꾼다(US2-AC4).
 * - 새 알림과 공감 묶음 갱신은 실시간 이벤트가 이 목록의 캐시 맨 앞에 넣는다(notification-stream).
 *   쪽 사이에 갱신된 묶음이 뒤쪽에서 빠져도 맨 위에 다시 보인다.
 */
export function NotificationList() {
  const {
    data,
    isPending,
    isError,
    isSuccess,
    isFetching,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    refetch,
  } = useNotificationsQuery();
  const { data: unread } = useUnreadCountQuery();
  const lastEventId = useNotificationStreamLastEventId();
  const markRead = useMarkReadMutation();
  const markAllRead = useMarkAllReadMutation();
  const [notice, setNotice] = useState<string | null>(null);

  const items = uniqueNotifications(data?.pages ?? []);
  const loadNext = () => void fetchNextPage({ cancelRefetch: false });
  const sentinel = useInfiniteScroll(
    loadNext,
    hasNextPage && !isFetchingNextPage,
  );

  // 목록을 받는 동안 온 실시간 알림은 캐시에 들어가지 못하거나(아직 목록이 없다) 늦게 온 응답에 덮인다.
  // 받은 목록의 맨 위 번호가 스트림이 받은 번호보다 작으면 한 번 다시 받는다. 그 번호의 알림이 이미
  // 없을 수도 있으므로(보관 기간이 지남) 같은 번호로는 되풀이하지 않는다.
  const newestSeq = items.reduce((max, item) => Math.max(max, item.seq), 0);
  const caughtUpWith = useRef<number | null>(null);
  useEffect(() => {
    if (
      !isSuccess ||
      isFetching ||
      lastEventId === null ||
      newestSeq >= lastEventId ||
      caughtUpWith.current === lastEventId
    ) {
      return;
    }
    caughtUpWith.current = lastEventId;
    void refetch();
  }, [isSuccess, isFetching, lastEventId, newestSeq, refetch]);

  const select = (notification: Notification) => {
    if (!notification.read) {
      markRead.mutate(notification.notificationId);
    }
    setNotice(notification.post === null ? DELETED_POST_NOTICE : null);
  };

  // 아직 불러오지 않은 뒤쪽에 안 읽은 알림이 있을 수 있으므로 배지 수도 함께 본다.
  const hasUnread =
    (unread?.count ?? 0) > 0 || items.some((item) => !item.read);
  const canMarkAll = hasUnread && !markAllRead.isPending;
  const markAll = () => {
    if (!canMarkAll) {
      return;
    }
    setNotice(null);
    markAllRead.mutate(
      { lastEventId },
      {
        onSuccess: () => setNotice(ALL_READ_NOTICE),
        onError: () => setNotice(ALL_READ_FAILED_NOTICE),
      },
    );
  };

  if (isPending) {
    return (
      <div className="flex justify-center py-8">
        <Spinner />
      </div>
    );
  }

  if (isError && items.length === 0) {
    return (
      <Card className="text-center">
        <p role="alert" className="text-sm text-red-600">
          알림을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  if (items.length === 0) {
    return (
      <Card className="text-center">
        <p className="text-sm text-neutral-600">아직 받은 알림이 없어요.</p>
      </Card>
    );
  }

  return (
    <section aria-label="알림 목록" className="flex w-full flex-col gap-3">
      <div className="flex min-h-8 items-center justify-between gap-3">
        {/* 비어 있어도 남겨 두어, 안내가 생기면 화면 낭독기가 읽는다. */}
        <p role="status" className="text-sm text-neutral-700">
          {notice}
        </p>
        {/* 누른 뒤에도 초점이 버튼에 남도록 disabled 대신 aria-disabled로 막는다. */}
        <Button
          variant="outline"
          size="sm"
          aria-disabled={canMarkAll ? undefined : true}
          className="shrink-0 aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
          onClick={markAll}
        >
          모두 읽음
        </Button>
      </div>
      <ul aria-label="알림" className="flex flex-col gap-2">
        {items.map((item) => (
          <li key={item.notificationId}>
            <NotificationItem notification={item} onSelect={select} />
          </li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden className="h-px" />
      {isFetchingNextPage ? (
        <div className="flex justify-center py-4">
          <Spinner />
        </div>
      ) : hasNextPage ? (
        // 스크롤을 못 쓰는 환경(키보드, 보조기기)에서도 다음 쪽을 부를 수 있게 둔다
        <Button variant="outline" onClick={loadNext}>
          더 보기
        </Button>
      ) : null}
    </section>
  );
}
