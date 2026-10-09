import type { ReactNode } from "react";

import type { Comment } from "../model/types";

export interface CommentActionContext {
  /** 답글이면 true. 답글에는 다시 답글을 달 수 없다(US3-AC3). */
  isReply: boolean;
}

/**
 * 댓글 하나와 그 아래 답글(FR-012). 답글은 들여 써서 원 댓글 아래에 둔다(US3-AC3).
 * 공감과 답글 버튼은 기능(features/like, features/comment)의 것이라 위젯이
 * `renderActions`로 넘긴다. 넘기지 않으면 공감 수만 글자로 보여 준다.
 */
export function CommentItem({
  comment,
  renderActions,
}: {
  comment: Comment;
  renderActions?: (
    comment: Comment,
    context: CommentActionContext,
  ) => ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3">
      <CommentBody
        comment={comment}
        isReply={false}
        renderActions={renderActions}
      />
      {comment.replies.length > 0 ? (
        <ul aria-label="답글 목록" className="flex flex-col gap-3 pl-6">
          {comment.replies.map((reply) => (
            <li key={reply.commentId}>
              <CommentBody
                comment={reply}
                isReply
                renderActions={renderActions}
              />
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** 숨긴 댓글의 자리에 보이는 말. */
export const HIDDEN_COMMENT_LABEL = "가려진 댓글이에요";

function CommentBody({
  comment,
  isReply,
  renderActions,
}: {
  comment: Comment;
  isReply: boolean;
  renderActions?: (
    comment: Comment,
    context: CommentActionContext,
  ) => ReactNode;
}) {
  // 숨긴 댓글을 다른 회원이 보면 내용과 작성자가 없다. 자리만 남기고 답글은 그대로 보인다(005 US1-AC5).
  if (comment.author === null || comment.content === null) {
    return (
      <article
        aria-label={isReply ? "가려진 답글" : "가려진 댓글"}
        className={
          isReply
            ? "flex flex-col gap-1 border-l-2 border-neutral-200 pl-3"
            : "flex flex-col gap-1"
        }
      >
        <p className="text-sm text-neutral-500 italic">
          {HIDDEN_COMMENT_LABEL}
        </p>
      </article>
    );
  }
  const nickname = comment.author.nickname;

  return (
    <article
      aria-label={`${nickname}의 ${isReply ? "답글" : "댓글"}`}
      className={
        isReply
          ? "flex flex-col gap-1 border-l-2 border-neutral-200 pl-3"
          : "flex flex-col gap-1"
      }
    >
      <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-neutral-900">
        {nickname}
        {comment.hidden ? (
          // 내 댓글이 숨겨졌다. 나에게는 내용이 보이지만 다른 회원에게는 자리만 보인다(005 US1-AC5).
          <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-normal text-amber-800">
            다른 회원에게 보이지 않아요
          </span>
        ) : null}
      </p>
      <p className="text-sm break-words whitespace-pre-wrap text-neutral-800">
        {comment.content}
      </p>
      <div className="flex flex-wrap items-center gap-2 text-xs text-neutral-600 tabular-nums">
        {renderActions ? (
          renderActions(comment, { isReply })
        ) : (
          <span>공감 {comment.likeCount}</span>
        )}
      </div>
    </article>
  );
}
