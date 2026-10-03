/**
 * TanStack Query 키. 루트가 다르면 무효화도 따로 된다: 글 상세는 `["posts", id]`,
 * 피드(Batch 6, T034)는 `["feed", ...]`처럼 따로 루트를 둔다. 상세를 무효화해도
 * 피드가 함께 다시 불러와지지 않게, 피드 키를 `["posts", ...]` 아래에 두지 않는다.
 *
 * 댓글 목록(Batch 8, T045)은 일부러 상세 아래 `["posts", id, "comments"]`에 둔다.
 * 공감과 댓글은 상세(HP, 공감 수, 댓글 수)와 댓글 목록(댓글 공감 수)을 함께 바꾸므로,
 * 공격이 끝나면 `["posts", id]` 하나를 무효화해 둘을 같이 서버 값으로 맞춘다. 상세의
 * 분석 중 폴링(`refetchInterval`)은 무효화가 아니라 상세 쿼리만 다시 부르므로 댓글을
 * 끌고 오지 않는다.
 */
export const QUERY_KEYS = {
  apiHealth: ["api-health"] as const,
  me: ["me"] as const,
  postDetail: (postId: number) => ["posts", postId] as const,
  comments: (postId: number) => ["posts", postId, "comments"] as const,
  /** 정렬과 필터가 다르면 다른 목록이다. 무효화는 `["feed"]` 하나로 모든 피드를 지운다. */
  feed: (filter: object) => ["feed", filter] as const,
  /** 모든 피드. 공감과 댓글로 공감 수, 댓글 수, HP가 바뀌면 이 키로 피드를 낡은 것으로 표시한다. */
  allFeeds: ["feed"] as const,
};
