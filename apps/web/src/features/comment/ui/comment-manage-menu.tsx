"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";

import type { Comment } from "@/entities/comment";
import { ApiError } from "@/shared/api";
import { cn, countGraphemes } from "@/shared/lib";
import { Button, ConfirmDialog } from "@/shared/ui";

import {
  useDeleteCommentMutation,
  useUpdateCommentMutation,
} from "../api/use-manage-comment-mutations";
import {
  COMMENT_CONTENT_MAX_LENGTH,
  writeCommentSchema,
  type WriteCommentFormInput,
  type WriteCommentFormValues,
} from "../model/schema";

const MENU_BUTTON =
  "rounded px-1 text-neutral-500 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none";

function manageErrorMessage(error: unknown, action: "고치지" | "지우지") {
  if (error instanceof ApiError) {
    if (error.code === "NOT_AUTHOR") {
      return `내 댓글만 ${action === "고치지" ? "고칠" : "지울"} 수 있어요.`;
    }
    if (error.code === "COMMENT_NOT_FOUND" || error.code === "POST_NOT_FOUND") {
      return "이미 지워진 댓글이에요.";
    }
  }
  return `댓글을 ${action} 못했습니다. 잠시 후 다시 시도해주세요.`;
}

/**
 * 내 댓글의 수정과 삭제 메뉴(US4-AC3, FR-014). 위젯이 `mine`인 댓글에만 붙인다.
 * 수정은 그 자리에서 작성과 같은 규칙(1~300자, 사람이 보는 글자)으로 고치고, 삭제는 화면 안
 * 확인 대화상자를 거친다. 원 댓글을 지우면 답글도 함께 지워진다고 알린다.
 */
export function CommentManageMenu({
  postId,
  comment,
}: {
  postId: number;
  comment: Comment;
}) {
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const deleteMutation = useDeleteCommentMutation(postId);
  const isRoot = comment.replies.length > 0;

  const closeDialog = () => {
    setConfirming(false);
    deleteMutation.reset();
  };

  return (
    <>
      {editing ? null : (
        <>
          <button
            type="button"
            className={MENU_BUTTON}
            onClick={() => setEditing(true)}
          >
            수정
          </button>
          <button
            type="button"
            className={MENU_BUTTON}
            onClick={() => setConfirming(true)}
          >
            삭제
          </button>
        </>
      )}
      {editing ? (
        <EditCommentForm
          postId={postId}
          comment={comment}
          onDone={() => setEditing(false)}
        />
      ) : null}
      <ConfirmDialog
        open={confirming}
        title="댓글을 지울까요?"
        description={
          isRoot
            ? "이 댓글에 달린 답글도 함께 지워져요."
            : "지운 댓글은 되돌릴 수 없어요."
        }
        confirmLabel="삭제하기"
        pending={deleteMutation.isPending}
        error={
          deleteMutation.isError
            ? manageErrorMessage(deleteMutation.error, "지우지")
            : null
        }
        onCancel={closeDialog}
        onConfirm={() =>
          deleteMutation.mutate(comment.commentId, {
            onSuccess: () => setConfirming(false),
          })
        }
      />
    </>
  );
}

function EditCommentForm({
  postId,
  comment,
  onDone,
}: {
  postId: number;
  comment: Comment;
  onDone: () => void;
}) {
  const mutation = useUpdateCommentMutation(postId);
  const contentId = useId();
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<WriteCommentFormInput, unknown, WriteCommentFormValues>({
    resolver: zodResolver(writeCommentSchema),
    defaultValues: { content: comment.content },
  });
  const content = useWatch({ control, name: "content" }) ?? "";
  const length = countGraphemes(content.trim());
  const overLimit = length > COMMENT_CONTENT_MAX_LENGTH;

  const onSubmit = handleSubmit(async ({ content: value }) => {
    try {
      await mutation.mutateAsync({
        commentId: comment.commentId,
        content: value,
      });
      onDone();
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) {
        setError("content", { message: error.message });
        return;
      }
      setError("root", { message: manageErrorMessage(error, "고치지") });
    }
  });

  const message = errors.content?.message ?? errors.root?.message;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex basis-full flex-col gap-2"
    >
      <label htmlFor={contentId} className="sr-only">
        댓글 수정
      </label>
      <textarea
        id={contentId}
        rows={2}
        autoFocus
        className="w-full resize-y rounded-md border border-neutral-300 bg-white p-2 text-sm text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
        {...register("content")}
      />
      <div className="flex items-start justify-between gap-2">
        {message ? (
          <p role="alert" className="text-sm text-red-600">
            {message}
          </p>
        ) : (
          <span />
        )}
        <div className="flex shrink-0 items-center gap-2">
          <p
            className={cn(
              "tabular-nums",
              overLimit ? "text-red-600" : "text-neutral-500",
            )}
          >
            {length}/{COMMENT_CONTENT_MAX_LENGTH}
          </p>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            취소
          </Button>
          <Button type="submit" size="sm" disabled={isSubmitting}>
            저장
          </Button>
        </div>
      </div>
      <div role="status" className="sr-only">
        {overLimit ? `${COMMENT_CONTENT_MAX_LENGTH}자를 넘었어요.` : null}
      </div>
    </form>
  );
}
