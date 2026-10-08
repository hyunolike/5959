import type { components } from "@/shared/api";

export type Comment = components["schemas"]["Comment"];
export type CommentPage = components["schemas"]["CommentPage"];

/** 마이페이지 "내 댓글"의 한 줄(004 US3-AC2). 댓글이 달린 글의 앞 50글자가 함께 온다. */
export type MyComment = components["schemas"]["MyComment"];
export type MyCommentPage = components["schemas"]["MyCommentPage"];
