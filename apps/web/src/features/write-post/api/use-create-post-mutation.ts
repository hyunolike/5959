"use client";

import { useMutation } from "@tanstack/react-query";

import type { ApiResponse, components } from "@/shared/api";
import { ApiError } from "@/shared/api";

import type { WritePostFormValues } from "../model/schema";

export type PostCreated = components["schemas"]["PostCreated"];

/**
 * 같은 출처 BFF 프록시(`/api/[...path]`)를 거쳐 `POST /api/v1/posts`를 부른다.
 * 실패하면 `ApiError`(429 `POST_RATE_LIMITED`, 403 `ONBOARDING_REQUIRED` 등)를 던진다.
 */
export async function createPost(
  values: WritePostFormValues,
  fetchImpl: typeof fetch = fetch,
): Promise<PostCreated> {
  const response = await fetchImpl("/api/posts", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(values),
  });
  const body = (await response.json()) as ApiResponse<PostCreated>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

export function useCreatePostMutation() {
  return useMutation({
    mutationFn: (values: WritePostFormValues) => createPost(values),
  });
}
