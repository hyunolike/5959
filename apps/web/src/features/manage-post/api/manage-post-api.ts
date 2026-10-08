import type { components } from "@/shared/api";
import { requestApi } from "@/shared/api";

export type PostUpdateRequest = components["schemas"]["PostUpdateRequest"];

/** 같은 출처 BFF 프록시를 거쳐 `PATCH /api/v1/posts/{id}`를 부른다(204). */
export function updatePost(
  postId: number,
  values: PostUpdateRequest,
): Promise<void> {
  return requestApi<void>(`/api/posts/${postId}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(values),
  });
}

/** 같은 출처 BFF 프록시를 거쳐 `DELETE /api/v1/posts/{id}`를 부른다(204). */
export function deletePost(postId: number): Promise<void> {
  return requestApi<void>(`/api/posts/${postId}`, { method: "DELETE" });
}
