"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { Controller, useForm, useWatch } from "react-hook-form";

import { COMMENT_TONE_LABELS, type CommentTone } from "@/entities/post";
import { cn, countGraphemes } from "@/shared/lib";
import { Button, Label } from "@/shared/ui";

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
    <form onSubmit={submit} noValidate className="flex w-full flex-col gap-5">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="content">고민</Label>
        <textarea
          id="content"
          rows={8}
          placeholder="요즘 마음을 무겁게 하는 일을 적어 주세요."
          className="w-full resize-y rounded-md border border-neutral-300 bg-white p-3 text-sm text-neutral-900 placeholder:text-neutral-400 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
          {...register("content")}
        />
        <div className="flex items-start justify-between gap-2">
          {errors.content ? (
            <p role="alert" className="text-sm text-red-600">
              {errors.content.message}
            </p>
          ) : (
            <span />
          )}
          <p
            className={cn(
              "shrink-0 text-xs tabular-nums",
              overLimit ? "text-red-600" : "text-neutral-500",
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

      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1.5 text-sm font-medium text-neutral-700">
          댓글 말투
        </legend>
        <Controller
          control={control}
          name="commentTone"
          render={({ field }) => (
            <div className="grid grid-cols-2 gap-2">
              {COMMENT_TONES.map(([tone, label]) => (
                <Button
                  key={tone}
                  type="button"
                  variant={field.value === tone ? "primary" : "outline"}
                  aria-pressed={field.value === tone}
                  onClick={() => field.onChange(tone)}
                >
                  {label}
                </Button>
              ))}
            </div>
          )}
        />
        {errors.commentTone && (
          <p role="alert" className="text-sm text-red-600">
            {errors.commentTone.message}
          </p>
        )}
      </fieldset>

      {errors.root && (
        <p role="alert" className="text-sm text-red-600">
          {errors.root.message}
        </p>
      )}

      {/* 성공 뒤 다음 화면으로 넘어가는 동안 다시 눌러 같은 요청을 또 보내지 않게 막는다. */}
      <Button type="submit" disabled={isSubmitting || locked}>
        {submitLabel}
      </Button>
    </form>
  );
}
