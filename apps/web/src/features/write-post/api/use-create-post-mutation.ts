"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { ApiResponse, components } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

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

/** 성공하면 새 글이 피드 맨 위에 보이도록 모든 피드(`["feed"]`)를 낡은 것으로 표시한다. */
export function useCreatePostMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (values: WritePostFormValues) => createPost(values),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: QUERY_KEYS.allFeeds }),
  });
}
