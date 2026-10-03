/**
 * TanStack Query 키. 루트가 다르면 무효화도 따로 된다: 글 상세는 `["posts", id]`,
 * 피드(Batch 6, T034)는 `["feed", ...]`처럼 따로 루트를 둔다. 상세를 무효화해도
 * 피드가 함께 다시 불러와지지 않게, 피드 키를 `["posts", ...]` 아래에 두지 않는다.
 */
export const QUERY_KEYS = {
  apiHealth: ["api-health"] as const,
  me: ["me"] as const,
  postDetail: (postId: number) => ["posts", postId] as const,
};
