import type { components, operations } from "@/shared/api";

export type PostDetail = components["schemas"]["PostDetail"];
export type FeedItem = components["schemas"]["FeedItem"];
export type FeedPage = components["schemas"]["FeedPage"];
/** 로그인하지 않은 방문자에게 보이는 글(011). 닉네임과 회원 번호가 들어갈 자리가 없다. */
export type PublicFeedItem = components["schemas"]["PublicFeedItem"];
export type PublicFeed = components["schemas"]["PublicFeed"];
export type SimilarPosts = components["schemas"]["SimilarPosts"];
export type CommentTone = components["schemas"]["CommentTone"];

type FeedQueryParams = NonNullable<
  operations["getFeed"]["parameters"]["query"]
>;
export type FeedOrder = NonNullable<FeedQueryParams["order"]>;

/**
 * 피드 정렬과 필터(FR-011). 직군은 하나라도 맞으면, 경력도 하나라도 맞으면 통과하고 둘은 모두 맞아야 한다.
 * 빈 배열은 거르지 않는다는 뜻이다.
 */
export interface FeedFilter {
  order: FeedOrder;
  jobRoles: readonly components["schemas"]["JobRole"][];
  careerYears: readonly components["schemas"]["CareerYear"][];
}

/** 댓글 말투 한국어 표시 문구 (data-model.md:24, spec FR-001) */
export const COMMENT_TONE_LABELS: Record<CommentTone, string> = {
  VENT_WITH_ME: "대신 욕해주기",
  COMFORT_ME: "무조건 위로해주기",
  WARM_ADVICE: "따뜻한 조언해주기",
  MAKE_ME_LAUGH: "웃겨주기",
};

/**
 * 말투를 고르면 쓰기 화면에 보이는 댓글 예시(016 US1-AC6). 고른 말투로 어떤 댓글을 받게 되는지
 * 쓰기 전에 짐작하게 한다.
 */
export const COMMENT_TONE_EXAMPLES: Record<CommentTone, string> = {
  VENT_WITH_ME: "그건 진짜 너무했다. 듣는 내가 다 화나네요.",
  COMFORT_ME: "그동안 혼자 버티느라 정말 애썼어요.",
  WARM_ADVICE: "오늘은 푹 쉬고, 내일 하나씩 정리해 봐요.",
  MAKE_ME_LAUGH: "그 정도면 몬스터가 아니라 보스급인데요?",
};

/**
 * 없거나 지운 글을 열려 할 때의 안내. 글 상세(404 `POST_NOT_FOUND`)와 알림 목록(지운 글의 알림을
 * 누름, 004 US2-AC5)이 같은 말을 쓴다.
 */
export const DELETED_POST_NOTICE = "삭제된 글이에요.";
