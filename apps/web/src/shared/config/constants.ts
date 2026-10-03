export const QUERY_KEYS = {
  apiHealth: ["api-health"] as const,
  me: ["me"] as const,
  postDetail: (postId: number) => ["posts", postId] as const,
};
