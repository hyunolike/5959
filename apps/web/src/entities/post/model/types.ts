import type { components } from "@/shared/api";

export type PostDetail = components["schemas"]["PostDetail"];
export type FeedItem = components["schemas"]["FeedItem"];
export type FeedPage = components["schemas"]["FeedPage"];
export type CommentTone = components["schemas"]["CommentTone"];

/** 댓글 말투 한국어 표시 문구 (data-model.md:24, spec FR-001) */
export const COMMENT_TONE_LABELS: Record<CommentTone, string> = {
  VENT_WITH_ME: "대신 욕해주기",
  COMFORT_ME: "무조건 위로해주기",
  WARM_ADVICE: "따뜻한 조언해주기",
  MAKE_ME_LAUGH: "웃겨주기",
};
