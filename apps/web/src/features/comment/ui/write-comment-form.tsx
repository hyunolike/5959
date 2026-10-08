"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useId } from "react";
import { useForm, useWatch } from "react-hook-form";

import type { Comment } from "@/entities/comment";
import { ApiError } from "@/shared/api";
import { cn, countGraphemes } from "@/shared/lib";
import { Button } from "@/shared/ui";

import { useCreateCommentMutation } from "../api/use-create-comment-mutation";
import {
  COMMENT_CONTENT_MAX_LENGTH,
  writeCommentSchema,
  type WriteCommentFormInput,
  type WriteCommentFormValues,
} from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "댓글을 올리지 못했습니다. 잠시 후 다시 시도해주세요.";

/** 다시 보내도 결과가 같은 실패를 사람이 읽을 문구로 바꾼다. */
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "INVALID_PARENT_COMMENT":
        return "답글에는 답글을 달 수 없어요.";
      case "COMMENT_NOT_FOUND":
        return "답글을 달 댓글이 지워졌어요.";
      case "POST_NOT_FOUND":
        return "삭제된 글이에요.";
    }
  }
  return GENERIC_ERROR_MESSAGE;
}

function isUnreplyable(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.code === "COMMENT_NOT_FOUND" ||
      error.code === "INVALID_PARENT_COMMENT")
  );
}

/**
 * 댓글과 답글 쓰기 폼(US3-AC2, US3-AC3, FR-008). 글자 수는 사람이 보는 글자로 세어
 * 300자 기준으로 보여 준다(research R8). `replyTo`가 있으면 그 원 댓글에 다는 답글이고,
 * 누구에게 다는지 보여 준다. 올리면 입력을 비우고 답글 상태를 끝낸다.
 */
export function WriteCommentForm({
  postId,
  replyTo = null,
  onReplyDone,
  onOptimisticAttack,
  onAttackSettled,
}: {
  postId: number;
  replyTo?: Comment | null;
  onReplyDone?: () => void;
  onOptimisticAttack?: () => () => void;
  onAttackSettled?: () => void;
}) {
  const mutation = useCreateCommentMutation(postId, {
    onOptimisticAttack,
    onAttackSettled,
  });
  const contentId = useId();
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    reset,
    control,
    formState: { errors, isSubmitting },
  } = useForm<WriteCommentFormInput, unknown, WriteCommentFormValues>({
    resolver: zodResolver(writeCommentSchema),
    defaultValues: { content: "" },
  });

  const replyToId = replyTo?.commentId;
  useEffect(() => {
    if (replyToId !== undefined) {
      setFocus("content");
    }
  }, [replyToId, setFocus]);

  const content = useWatch({ control, name: "content" }) ?? "";
  const length = countGraphemes(content.trim());
  const overLimit = length > COMMENT_CONTENT_MAX_LENGTH;
  const label = replyTo ? "답글" : "댓글";

  const onSubmit = handleSubmit(async ({ content: value }) => {
    try {
      await mutation.mutateAsync(
        replyTo
          ? { content: value, parentId: replyTo.commentId }
          : { content: value },
      );
      reset();
      if (replyTo) {
        onReplyDone?.();
      }
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 400 &&
        error.code !== "INVALID_PARENT_COMMENT"
      ) {
        setError("content", { message: error.message });
        return;
      }
      setError("root", { message: errorMessage(error) });
      // 답글 대상이 지워졌거나 답글이었으면 같은 대상으로는 다시 보내도 실패한다.
      // 답글 상태를 끝내 쓴 글은 그대로 두고 댓글로 다시 보낼 수 있게 한다.
      if (replyTo && isUnreplyable(error)) {
        onReplyDone?.();
      }
    }
  });

  const cancelReply = () => {
    onReplyDone?.();
    // 눌렀던 취소 버튼이 사라지므로 초점을 입력 칸으로 돌린다.
    setFocus("content");
  };

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-2">
      {replyTo ? (
        <div className="flex items-center justify-between gap-2 text-xs text-neutral-600">
          <span>
            {replyTo.author ? `${replyTo.author.nickname}님에게 답글` : "답글"}
          </span>
          <button
            type="button"
            onClick={cancelReply}
            aria-label="답글 취소"
            className="rounded px-1 text-neutral-500 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
          >
            취소
          </button>
        </div>
      ) : null}
      <label htmlFor={contentId} className="sr-only">
        {label}
      </label>
      <textarea
        id={contentId}
        rows={3}
        placeholder={
          replyTo ? "답글을 남겨 주세요." : "따뜻한 한마디를 남겨 주세요."
        }
        className="w-full resize-y rounded-md border border-neutral-300 bg-white p-3 text-sm text-neutral-900 placeholder:text-neutral-400 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
        {...register("content")}
      />
      <div className="flex items-start justify-between gap-2">
        {errors.content ? (
          <p role="alert" className="text-sm text-red-600">
            {errors.content.message}
          </p>
        ) : errors.root ? (
          <p role="alert" className="text-sm text-red-600">
            {errors.root.message}
          </p>
        ) : (
          <span />
        )}
        <div className="flex shrink-0 items-center gap-3">
          <p
            className={cn(
              "text-xs tabular-nums",
              overLimit ? "text-red-600" : "text-neutral-500",
            )}
          >
            {length}/{COMMENT_CONTENT_MAX_LENGTH}
          </p>
          <Button type="submit" size="sm" disabled={isSubmitting}>
            등록
          </Button>
        </div>
      </div>
      {/* 카운터는 입력마다 읽히지 않게 두고, 넘었을 때만 한 번 알린다. */}
      <div role="status" className="sr-only">
        {overLimit ? `${COMMENT_CONTENT_MAX_LENGTH}자를 넘었어요.` : null}
      </div>
    </form>
  );
}
