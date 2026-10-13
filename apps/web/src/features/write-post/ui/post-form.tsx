"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CircleNotch } from "@phosphor-icons/react";
import { Controller, useForm, useWatch } from "react-hook-form";

import { COMMENT_TONE_LABELS, type CommentTone } from "@/entities/post";
import { cn, countGraphemes } from "@/shared/lib";
import { Button, Chip, Label, Textarea } from "@/shared/ui";

import {
  POST_CONTENT_MAX_LENGTH,
  writePostSchema,
  type WritePostFormInput,
  type WritePostFormValues,
} from "../model/schema";

const COMMENT_TONES = Object.entries(COMMENT_TONE_LABELS) as [
  CommentTone,
  string,
][];

/** 보낸 뒤 폼에 보여 줄 실패. `content`면 본문 아래, `root`면 버튼 위에 보인다. */
export interface PostFormError {
  field: "content" | "root";
  message: string;
}

/**
 * 고민 글 폼(FR-001). 쓰기(`WriteForm`)와 고치기(US4-AC1, widgets/post-editor)가 같이 쓴다.
 * 본문 글자 수는 사람이 보는 글자(grapheme)로 세고(research R8), 말투는 버튼 4개 중
 * 하나를 고른다. 검증 규칙은 둘 다 `writePostSchema`다(API도 작성과 수정에 같은 규칙).
 * `onSubmit`이 실패를 돌려주면 그 자리에 보여 주고, `locked`면 버튼을 잠근다(성공 뒤 이동 중).
 */
export function PostForm({
  defaultValues,
  submitLabel,
  locked = false,
  onSubmit,
}: {
  defaultValues?: WritePostFormValues;
  submitLabel: string;
  locked?: boolean;
  onSubmit: (values: WritePostFormValues) => Promise<PostFormError | void>;
}) {
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<WritePostFormInput, unknown, WritePostFormValues>({
    resolver: zodResolver(writePostSchema),
    defaultValues: defaultValues ?? { content: "", commentTone: undefined },
  });

  const content = useWatch({ control, name: "content" }) ?? "";
  const length = countGraphemes(content.trim());
  const overLimit = length > POST_CONTENT_MAX_LENGTH;

  const submit = handleSubmit(async (values) => {
    const failure = await onSubmit(values);
    if (failure) {
      setError(failure.field, { message: failure.message });
    }
  });

  return (
    <form onSubmit={submit} noValidate className="flex w-full flex-col gap-7">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="content" className="text-body text-fg font-bold">
          고민
        </Label>
        <p id="content-hint" className="text-body-sm text-fg-subtle leading-6">
          형식 없이 편하게 적어 주세요. 닉네임, 직군, 경력과 함께 회원에게만
          보여요.
        </p>
        <Textarea
          id="content"
          aria-required
          aria-invalid={!!errors.content || overLimit}
          aria-describedby={
            errors.content ? "content-error content-hint" : "content-hint"
          }
          rows={8}
          placeholder="요즘 마음을 무겁게 하는 일을 적어 주세요."
          className="min-h-48 resize-y leading-7"
          {...register("content")}
        />
        <div className="flex items-start justify-between gap-2">
          {errors.content ? (
            <p
              id="content-error"
              role="alert"
              className="text-body-sm text-danger"
            >
              {errors.content.message}
            </p>
          ) : (
            <span />
          )}
          <p
            className={cn(
              "text-caption shrink-0 tabular-nums",
              overLimit ? "text-danger" : "text-fg-subtle",
            )}
          >
            {length}/{POST_CONTENT_MAX_LENGTH}
          </p>
        </div>
        {/* 카운터는 입력마다 읽히지 않게 두고, 넘었을 때만 한 번 알린다. */}
        <div role="status" className="sr-only">
          {overLimit ? `${POST_CONTENT_MAX_LENGTH}자를 넘었어요.` : null}
        </div>
      </div>

      <fieldset
        aria-describedby="comment-tone-hint"
        className="flex flex-col gap-1.5"
      >
        <legend className="text-body text-fg font-bold">댓글 말투</legend>
        <p
          id="comment-tone-hint"
          className="text-body-sm text-fg-subtle mb-1.5 leading-6"
        >
          듣고 싶은 댓글의 말투를 하나 골라 주세요. 댓글을 쓰는 회원에게 함께
          보여요.
        </p>
        <Controller
          control={control}
          name="commentTone"
          render={({ field }) => (
            <div className="flex flex-wrap gap-x-2 gap-y-3">
              {COMMENT_TONES.map(([tone, label]) => {
                const selected = field.value === tone;
                return (
                  <Chip
                    key={tone}
                    pressed={selected}
                    onClick={() => field.onChange(tone)}
                  >
                    {label}
                  </Chip>
                );
              })}
            </div>
          )}
        />
        {errors.commentTone && (
          <p role="alert" className="text-body-sm text-danger">
            {errors.commentTone.message}
          </p>
        )}
      </fieldset>

      {errors.root && (
        <p role="alert" className="text-body-sm text-danger">
          {errors.root.message}
        </p>
      )}

      {/* 성공 뒤 다음 화면으로 넘어가는 동안 다시 눌러 같은 요청을 또 보내지 않게 막는다. */}
      <Button
        type="submit"
        size="lg"
        disabled={isSubmitting || locked}
        aria-busy={isSubmitting || locked}
      >
        {isSubmitting || locked ? (
          <CircleNotch
            aria-hidden
            size={18}
            className="motion-safe:animate-spin"
          />
        ) : null}
        {submitLabel}
      </Button>
    </form>
  );
}
