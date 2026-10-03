"use client";

import { useRouter } from "next/navigation";

import { ApiError } from "@/shared/api";

import { useCreatePostMutation } from "../api/use-create-post-mutation";
import type { WritePostFormValues } from "../model/schema";
import { PostForm, type PostFormError } from "./post-form";

const RATE_LIMITED_MESSAGE = "잠시 뒤 다시 써 주세요";
const GENERIC_ERROR_MESSAGE =
  "글을 올리지 못했습니다. 잠시 후 다시 시도해주세요.";

/**
 * 고민 글쓰기(FR-001). 올리면 그 글의 상세 화면(`/post/{id}`)으로 간다(US1-AC1).
 * 1시간 작성 제한(429)은 다시 써 달라고 안내하고(research R9), 온보딩 전(403)이면
 * 온보딩 화면으로 보낸다(US1-AC7).
 */
export function WriteForm() {
  const router = useRouter();
  const createPostMutation = useCreatePostMutation();

  const onSubmit = async (
    values: WritePostFormValues,
  ): Promise<PostFormError | void> => {
    try {
      const created = await createPostMutation.mutateAsync(values);
      router.push(`/post/${created.postId}`);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "POST_RATE_LIMITED") {
          return { field: "root", message: RATE_LIMITED_MESSAGE };
        }
        if (error.code === "ONBOARDING_REQUIRED") {
          router.push("/onboarding");
          return;
        }
        if (error.status === 400) {
          return { field: "content", message: error.message };
        }
      }
      return { field: "root", message: GENERIC_ERROR_MESSAGE };
    }
  };

  return (
    <PostForm
      submitLabel="올리기"
      locked={createPostMutation.isSuccess}
      onSubmit={onSubmit}
    />
  );
}
