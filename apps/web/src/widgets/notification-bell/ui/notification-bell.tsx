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
import { Toaster, useToasts } from "@/shared/ui";

const NOTIFICATIONS_PATH = "/notifications";

/**
 * 알림 종. 마운트된 동안 실시간 연결을 열어 두고, 안 읽은 수를 배지로 보이며, 새 알림을 토스트로 띄운다.
 * 레이아웃이 온보딩을 마친 회원에게만 그린다(US1-AC8).
 *
 * 토스트 문구는 종류, 닉네임, 인원 수로만 만든다. 글이나 댓글 본문은 넣지 않는다(ADR-0005).
 * 알림 페이지를 보고 있으면 목록에 바로 나타나므로 토스트를 띄우지 않는다.
 */
export function NotificationBell() {
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, show, dismiss } = useToasts();

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
      onClick: () => router.push(`/post/${notification.postId}`),
    });
  });

  const { data: unread } = useUnreadCountQuery();
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
