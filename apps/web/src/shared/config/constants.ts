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
  /**
   * 알림 목록(무한 스크롤)과 안 읽은 수. 실시간 이벤트가 두 캐시를 직접 고치므로 서로 무효화에
   * 끌려가지 않게 키를 나란히 둔다.
   */
  notifications: ["notifications", "list"] as const,
  unreadCount: ["notifications", "unread-count"] as const,
  /**
   * 마이페이지의 세 목록(004 US3). 공감, 댓글, 글 삭제가 다른 화면에서 일어나므로 무효화로 따라가지
   * 않고, 탭을 열 때마다 다시 받는다(`refetchOnMount: "always"`).
   */
  myPosts: ["my", "posts"] as const,
  myComments: ["my", "comments"] as const,
  likedPosts: ["my", "liked-posts"] as const,
  /** 마이페이지 감정 통계(004 US4). 세 목록과 같이 마이페이지를 열 때마다 다시 받는다. */
  emotionStats: ["my", "emotion-stats"] as const,
};

/**
 * TanStack Query 뮤테이션 키. 한 글을 공격하는 뮤테이션(글 공감과 취소, 댓글 공감과
 * 취소, 댓글 쓰기)은 모두 `["attack", postId]`를 쓴다. 겹친 공격 중 먼저 끝난 것이
 * 상세를 다시 불러와 아직 응답을 기다리는 공격의 낙관적 HP를 덮지 않도록, 마지막
 * 공격이 끝날 때만 다시 불러온다(`isMutating`으로 센다).
 */
export const MUTATION_KEYS = {
  attack: (postId: number) => ["attack", postId] as const,
  /**
   * 글 삭제. 상세 쿼리는 이 키의 뮤테이션이 진행 중이거나 성공했으면 분석 중 폴링을 멈춘다.
   * 멈추지 않으면 지운 직후 이동하기 전에 폴링이 한 번 더 돌아 404("삭제된 글이에요")가 깜박인다.
   */
  deletePost: (postId: number) => ["deletePost", postId] as const,
};
