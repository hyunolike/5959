import Link from "next/link";

import { cn, formatDate, formatRelativeTime } from "@/shared/lib";

import { notificationHref, notificationMessage } from "../model/message";
import type { Notification } from "../model/types";

/** 관련 글이 지워진 알림에서 글 앞부분 자리에 쓰는 말(US2-AC5). */
export const DELETED_POST_LABEL = "삭제된 글";

/** 보스 처치 알림에서 글 앞부분 자리에 쓰는 말(006). 이 알림에는 글이 없다. */
const RAID_NOTICE_LABEL = "레이드 결과 보기";

const ITEM_CLASS =
  "hover:bg-surface-sunken flex w-full flex-col gap-1 px-5 py-4 text-left transition-colors focus-visible:-outline-offset-2";

/**
 * 알림 목록의 한 줄(US2-AC1): 종류 문구, 관련 글의 앞부분 또는 "삭제된 글", 시각, 읽음 여부.
 *
 * - 글이 있으면 그 글 상세로 가는 링크다. 글이 지워졌으면(`post == null`) 갈 곳이 없으므로 버튼이다(US2-AC5).
 *   어느 쪽이든 누르면 `onSelect`를 부른다. 읽음 처리와 안내는 부르는 쪽이 맡는다.
 * - 안 읽음은 색만이 아니라 글자("안 읽음")로도 알린다.
 * - 문구는 종류, 닉네임, 인원 수로만 만들고, 글은 받는 사람이 관여한 글의 앞부분만 보인다. 남의 댓글
 *   본문은 응답에도 없고 여기서도 보이지 않는다(ADR-0005).
 * - 시각은 갱신 시각이다. 묶인 공감은 마지막 공감 시각이 기준이다(FR-007).
 */
export function NotificationItem({
  notification,
  onSelect,
}: {
  notification: Notification;
  onSelect: (notification: Notification) => void;
}) {
  const { post, read, updatedAt } = notification;
  const href = notificationHref(notification);
  const className = ITEM_CLASS;
  const body = (
    <>
      <span className="flex items-start justify-between gap-2">
        <span
          className={cn(
            "text-body-sm",
            read ? "text-fg-subtle font-normal" : "text-fg font-bold",
          )}
        >
          {notificationMessage(notification)}
        </span>
        {read ? (
          <span className="sr-only">읽음</span>
        ) : (
          <span className="bg-action text-caption text-on-action shrink-0 rounded-full px-2 py-0.5 font-medium whitespace-nowrap">
            안 읽음
          </span>
        )}
      </span>
      <span
        className={cn(
          "text-body-sm break-words",
          href === null
            ? "text-fg-subtle"
            : read
              ? "text-fg-subtle"
              : "text-fg-muted",
        )}
      >
        {post
          ? post.contentPreview
          : href === null
            ? DELETED_POST_LABEL
            : RAID_NOTICE_LABEL}
      </span>
      <time
        dateTime={updatedAt}
        title={formatDate(updatedAt, "YYYY.MM.DD HH:mm")}
        className="text-caption text-fg-subtle tabular-nums"
      >
        {formatRelativeTime(updatedAt)}
      </time>
    </>
  );

  if (href === null) {
    return (
      <button
        type="button"
        className={className}
        onClick={() => onSelect(notification)}
      >
        {body}
      </button>
    );
  }

  return (
    <Link
      href={href}
      className={className}
      onClick={() => onSelect(notification)}
    >
      {body}
    </Link>
  );
}
