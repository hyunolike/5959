import Link from "next/link";

import { formatDate } from "@/shared/lib";

import type { MyComment } from "../model/types";

/**
 * 마이페이지 "내 댓글"의 한 줄(004 US3-AC2): 댓글 본문, 달린 글의 앞부분, 시각. 누르면 그 글 상세로 간다.
 * 답글이면 "답글"이라고 글자로 알린다.
 */
export function MyCommentItem({ comment }: { comment: MyComment }) {
  return (
    <Link
      href={`/post/${comment.postId}`}
      className="flex w-full flex-col gap-1 rounded-lg border border-neutral-200 bg-white px-4 py-3 transition-colors hover:border-neutral-300 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
    >
      <span className="flex items-start gap-2">
        {comment.reply ? (
          <span className="shrink-0 rounded-full bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
            답글
          </span>
        ) : null}
        <span className="text-sm break-words whitespace-pre-line text-neutral-900">
          {comment.content}
        </span>
      </span>
      <span className="text-sm break-words text-neutral-500">
        <span className="sr-only">댓글을 단 글: </span>
        {comment.postContentPreview}
      </span>
      <time dateTime={comment.createdAt} className="text-xs text-neutral-500">
        {formatDate(comment.createdAt, "YYYY.MM.DD HH:mm")}
      </time>
    </Link>
  );
}
