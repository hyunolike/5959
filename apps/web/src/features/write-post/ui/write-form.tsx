"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { Controller, useForm, useWatch } from "react-hook-form";

import { COMMENT_TONE_LABELS, type CommentTone } from "@/entities/post";
import { ApiError } from "@/shared/api";
import { cn, countGraphemes } from "@/shared/lib";
import { Button, Label } from "@/shared/ui";

import { useCreatePostMutation } from "../api/use-create-post-mutation";
import {
  POST_CONTENT_MAX_LENGTH,
  writePostSchema,
  type WritePostFormInput,
  type WritePostFormValues,
} from "../model/schema";

const RATE_LIMITED_MESSAGE = "잠시 뒤 다시 써 주세요";
const GENERIC_ERROR_MESSAGE =
  "글을 올리지 못했습니다. 잠시 후 다시 시도해주세요.";

const COMMENT_TONES = Object.entries(COMMENT_TONE_LABELS) as [
  CommentTone,
  string,
][];

/**
 * 고민 글쓰기 폼(FR-001). 본문 글자 수는 사람이 보는 글자(grapheme)로 세고
 * (research R8), 말투는 버튼 4개 중 하나를 고른다. 올리면 그 글의 상세
 * 화면(`/post/{id}`)으로 간다(US1-AC1). 1시간 작성 제한(429)은 다시 써 달라고
 * 안내하고(research R9), 온보딩 전(403)이면 온보딩 화면으로 보낸다(US1-AC7).
 */
export function WriteForm() {
  const router = useRouter();
  const createPostMutation = useCreatePostMutation();
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<WritePostFormInput, unknown, WritePostFormValues>({
    resolver: zodResolver(writePostSchema),
    defaultValues: { content: "", commentTone: undefined },
  });

  const content = useWatch({ control, name: "content" }) ?? "";
  const length = countGraphemes(content.trim());
  const overLimit = length > POST_CONTENT_MAX_LENGTH;

  const onSubmit = handleSubmit(async (values) => {
    try {
      const created = await createPostMutation.mutateAsync(values);
      router.push(`/post/${created.postId}`);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "POST_RATE_LIMITED") {
          setError("root", { message: RATE_LIMITED_MESSAGE });
          return;
        }
        if (error.code === "ONBOARDING_REQUIRED") {
          router.push("/onboarding");
          return;
        }
        if (error.status === 400) {
          setError("content", { message: error.message });
          return;
        }
      }
      setError("root", { message: GENERIC_ERROR_MESSAGE });
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex w-full flex-col gap-5">
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
            aria-live="polite"
            className={cn(
              "shrink-0 text-xs tabular-nums",
              overLimit ? "text-red-600" : "text-neutral-500",
            )}
          >
            {length}/{POST_CONTENT_MAX_LENGTH}
          </p>
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

      <Button type="submit" disabled={isSubmitting}>
        올리기
      </Button>
    </form>
  );
}
