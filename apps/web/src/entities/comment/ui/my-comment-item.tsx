import Link from "next/link";

import { formatDate, formatRelativeTime } from "@/shared/lib";

import type { MyComment } from "../model/types";

/**
 * 마이페이지 "내 댓글"의 한 줄(004 US3-AC2): 댓글 본문, 달린 글의 앞부분, 시각("2시간 전"). 누르면 그 글 상세로 간다.
 * 답글이면 "답글"이라고 글자로 알린다.
 */
export function MyCommentItem({ comment }: { comment: MyComment }) {
  const relativeTime = formatRelativeTime(comment.createdAt);

  return (
    <Link
      href={`/post/${comment.postId}`}
      className="hover:bg-surface-sunken flex w-full flex-col gap-1 px-5 py-4 transition-colors focus-visible:-outline-offset-2"
    >
      <span className="flex items-start gap-2">
        {comment.reply ? (
          <span className="bg-surface-sunken text-caption text-fg-muted shrink-0 rounded-full px-2 py-0.5 font-medium">
            답글
          </span>
        ) : null}
        <span className="text-body-sm text-fg break-words whitespace-pre-line">
          {comment.content}
        </span>
      </span>
      <span className="text-body-sm text-fg-subtle break-words">
        <span className="sr-only">댓글을 단 글: </span>
        {comment.postContentPreview}
      </span>
      <time
        dateTime={comment.createdAt}
        title={
          relativeTime
            ? formatDate(comment.createdAt, "YYYY.MM.DD HH:mm")
            : undefined
        }
        className="text-caption text-fg-subtle tabular-nums"
      >
        {relativeTime}
      </time>
    </Link>
  );
}
