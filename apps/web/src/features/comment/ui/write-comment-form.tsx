"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useId, useRef } from "react";
import { useForm, useWatch } from "react-hook-form";

import type { Comment } from "@/entities/comment";
import { ApiError } from "@/shared/api";
import { cn, countGraphemes } from "@/shared/lib";
import { Button, Textarea } from "@/shared/ui";

import { useCreateCommentMutation } from "../api/use-create-comment-mutation";
import {
  COMMENT_CONTENT_MAX_LENGTH,
  writeCommentSchema,
  type WriteCommentFormInput,
  type WriteCommentFormValues,
} from "../model/schema";

/** 남은 글자가 이보다 적어지면 글자 수를 보인다. */
const COUNTER_MARGIN = 50;

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
 * 입력 칸과 등록 버튼은 한 줄이다. 글 상세는 좁은 화면에서 이 폼을 화면 아래에 붙인다.
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
  // 글자 수는 끝이 가까워졌을 때만 보인다. 한 줄 입력 옆에 늘 두면 입력줄이 번잡하다.
  const showCounter = length > COMMENT_CONTENT_MAX_LENGTH - COUNTER_MARGIN;

  // 입력 칸은 한 줄에서 시작해 내용만큼 늘어난다. 가장 큰 높이는 CSS(`max-h-32`)가 막는다.
  const { ref: registerContent, ...contentField } = register("content");
  const contentRef = useRef<HTMLTextAreaElement | null>(null);
  useEffect(() => {
    const element = contentRef.current;
    // 그려지지 않은 곳(테스트 환경, 숨은 화면)에서는 높이를 알 수 없다. 건드리지 않는다.
    if (!element || element.scrollHeight === 0) {
      return;
    }
    const border = element.offsetHeight - element.clientHeight;
    element.style.height = "auto";
    element.style.height = `${element.scrollHeight + border}px`;
  }, [content]);
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
        <div className="text-caption text-fg-subtle flex items-center justify-between gap-2">
          <span>{replyLabel(replyTo)}</span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-caption px-2 font-medium"
            onClick={cancelReply}
            aria-label="답글 취소"
          >
            취소
          </Button>
        </div>
      ) : null}
      {replyTo?.author?.ai ? (
        // AI 친구는 한 글에 한 번만 답한다. 답글을 쓰기 전에 알린다(009 FR-006).
        <p className="text-caption text-fg-subtle">
          AI 친구는 답글에 다시 답하지 못해요.
        </p>
      ) : null}
      {errors.content ? (
        <p
          id={`${contentId}-error`}
          role="alert"
          className="text-body-sm text-danger"
        >
          {errors.content.message}
        </p>
      ) : errors.root ? (
        <p role="alert" className="text-body-sm text-danger">
          {errors.root.message}
        </p>
      ) : null}
      <label htmlFor={contentId} className="sr-only">
        {label}
      </label>
      <div className="flex items-end gap-2">
        <Textarea
          id={contentId}
          aria-invalid={!!errors.content || overLimit}
          aria-describedby={errors.content ? `${contentId}-error` : undefined}
          rows={1}
          placeholder={
            replyTo ? "답글을 남겨 주세요." : "따뜻한 한마디를 남겨 주세요."
          }
          className="max-h-32 min-h-12 flex-1 resize-none py-2.5"
          {...contentField}
          ref={(element) => {
            registerContent(element);
            contentRef.current = element;
          }}
        />
        <Button type="submit" className="shrink-0" disabled={isSubmitting}>
          등록
        </Button>
      </div>
      {showCounter ? (
        <p
          className={cn(
            "text-caption self-end tabular-nums",
            overLimit ? "text-danger" : "text-fg-subtle",
          )}
        >
          {length}/{COMMENT_CONTENT_MAX_LENGTH}
        </p>
      ) : null}
      {/* 카운터는 입력마다 읽히지 않게 두고, 넘었을 때만 한 번 알린다. */}
      <div role="status" className="sr-only">
        {overLimit ? `${COMMENT_CONTENT_MAX_LENGTH}자를 넘었어요.` : null}
      </div>
    </form>
  );
}

/** 답글을 다는 상대. AI 친구면 닉네임이 보이는 이 자리에도 AI임을 적는다(009 US2-AC1). */
function replyLabel(replyTo: Comment): string {
  if (!replyTo.author) return "답글";
  return replyTo.author.ai
    ? `AI 친구 ${replyTo.author.nickname}에게 답글`
    : `${replyTo.author.nickname}님에게 답글`;
}
