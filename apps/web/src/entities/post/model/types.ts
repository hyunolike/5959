import type { components, operations } from "@/shared/api";

export type PostDetail = components["schemas"]["PostDetail"];
export type FeedItem = components["schemas"]["FeedItem"];
export type FeedPage = components["schemas"]["FeedPage"];
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
