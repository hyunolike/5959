"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef } from "react";

import {
  badgeLabel,
  notificationMessage,
  useUnreadCountQuery,
  type Notification,
} from "@/entities/notification";
import { useNotificationStream } from "@/features/notification-stream";
import { useMarkReadMutation } from "@/features/read-notification";
import { Toaster, useToasts } from "@/shared/ui";

const NOTIFICATIONS_PATH = "/notifications";
const SUPPORT_TOAST_DURATION_MS = 8000;

/**
 * 알림 종. 마운트된 동안 실시간 연결을 열어 두고, 안 읽은 수를 배지로 보이며, 새 알림을 토스트로 띄운다.
 * 레이아웃이 온보딩을 마친 회원에게만 그린다(US1-AC8).
 *
 * 토스트 문구는 종류, 닉네임, 인원 수로만 만든다. 글이나 댓글 본문은 넣지 않는다(ADR-0005).
 * 알림 페이지를 보고 있으면 목록에 바로 나타나므로 토스트를 띄우지 않는다. 토스트를 누르면 목록에서
 * 누른 것과 같이 읽음으로 바꾸고 글 상세로 간다(US2-AC3).
 */
export function NotificationBell() {
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, show, dismiss } = useToasts();
  const { mutate: markRead } = useMarkReadMutation();

  // 연결은 한 번만 열고, 그때그때의 주소는 ref로 읽는다.
  const pathnameRef = useRef(pathname);
  useEffect(() => {
    pathnameRef.current = pathname;
  });

  const status = useNotificationStream((notification: Notification) => {
    if (notification.read || pathnameRef.current === NOTIFICATIONS_PATH) {
      return;
    }
    show({
      message: notificationMessage(notification),
      // 도움 안내는 놓치면 안 되므로 다른 알림보다 오래 둔다(005 research R16).
      durationMs:
        notification.type === "SUPPORT_NOTICE"
          ? SUPPORT_TOAST_DURATION_MS
          : undefined,
      // 그 사이 글이 지워졌으면 갈 곳이 없다. 누르면 닫히기만 한다(US2-AC5의 "삭제된 글" 안내는 목록이 맡는다).
      onClick:
        notification.post === null
          ? undefined
          : () => {
              // 토스트를 누르는 것도 그 알림을 누르는 것이다. 읽음으로 바꾸고 글로 간다(US2-AC3).
              markRead(notification.notificationId);
              router.push(`/post/${notification.postId}`);
            },
    });
  });

  // 연결을 닫았거나(로그아웃) 세션이 끝났으면(401) 안 읽은 수도 다시 묻지 않는다. 로그아웃이 쿼리
  // 캐시를 비운 뒤 이 조회가 다시 나가면 401을 받아 화면이 `/login?next=`로 한 번 더 튄다.
  const live = status !== "idle" && status !== "stopped";
  const { data: unread } = useUnreadCountQuery({ enabled: live });
  const badge = badgeLabel(unread?.count ?? 0);

  return (
    <>
      <Link
        href={NOTIFICATIONS_PATH}
        prefetch={false}
        aria-label={badge ? `알림, 안 읽은 알림 ${badge}개` : "알림"}
        data-stream-status={status}
        className="relative inline-flex h-10 w-10 items-center justify-center rounded-full text-neutral-700 hover:bg-neutral-200 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 24 24"
          className="h-6 w-6"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M6 9a6 6 0 1 1 12 0c0 5 2 6 2 6H4s2-1 2-6" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </svg>
        {badge ? (
          <span
            aria-hidden="true"
            data-testid="notification-badge"
            className="absolute -top-0.5 -right-0.5 min-w-5 rounded-full bg-red-600 px-1 text-center text-xs leading-5 font-semibold text-white"
          >
            {badge}
          </span>
        ) : null}
      </Link>
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </>
  );
}
