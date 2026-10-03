import { requestApi, type components } from "@/shared/api";

export type LikeResult = components["schemas"]["LikeResult"];

/**
 * 같은 출처 BFF 프록시를 거친 공감과 취소. 공감은 `POST .../likes`, 취소는
 * `DELETE .../likes/me`다. 이미 공감했으면 409 `ALREADY_LIKED`, 내 글에 공감하면
 * 403 `CANNOT_LIKE_OWN_POST`를 `ApiError`로 던진다. 응답에는 HP가 없다(research R6).
 */
export function togglePostLike(
  postId: number,
  like: boolean,
): Promise<LikeResult> {
  return like
    ? requestApi(`/api/posts/${postId}/likes`, { method: "POST" })
    : requestApi(`/api/posts/${postId}/likes/me`, { method: "DELETE" });
}

export function toggleCommentLike(
  commentId: number,
  like: boolean,
): Promise<LikeResult> {
  return like
    ? requestApi(`/api/comments/${commentId}/likes`, { method: "POST" })
    : requestApi(`/api/comments/${commentId}/likes/me`, { method: "DELETE" });
}
