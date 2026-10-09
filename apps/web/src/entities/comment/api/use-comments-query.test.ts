import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { Comment } from "../model/types";
import { commentsRequestPath, fetchCommentsPage } from "./use-comments-query";

const jsonResponse = (body: unknown, status = 200) =>
  ({ status, json: () => Promise.resolve(body) }) as unknown as Response;

const COMMENT: Comment = {
  commentId: 1,
  author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
  content: "힘내요",
  hidden: false,
  likeCount: 0,
  likedByMe: false,
  mine: false,
  createdAt: "2026-10-03T00:00:00Z",
  replies: [],
};

describe("commentsRequestPath", () => {
  it("첫 쪽은 커서 없이 같은 출처 BFF 경로를 부른다", () => {
    expect(commentsRequestPath(7, null)).toBe("/api/posts/7/comments");
  });

  it("다음 쪽은 커서를 붙인다", () => {
    expect(commentsRequestPath(7, "a b+")).toBe(
      "/api/posts/7/comments?cursor=a+b%2B",
    );
  });
});

describe("fetchCommentsPage", () => {
  it("US3-AC3 원 댓글과 그 아래 답글을 함께 받는다", async () => {
    const page = {
      items: [{ ...COMMENT, replies: [{ ...COMMENT, commentId: 2 }] }],
      nextCursor: null,
    };
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ success: true, data: page }));

    await expect(fetchCommentsPage(7, null, fetchImpl)).resolves.toEqual(page);
    expect(fetchImpl).toHaveBeenCalledWith("/api/posts/7/comments", {
      cache: "no-store",
    });
  });

  it("실패하면 ApiError를 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "POST_NOT_FOUND", message: "글이 없습니다." },
        },
        404,
      ),
    );
    const error = await fetchCommentsPage(7, null, fetchImpl).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("POST_NOT_FOUND");
  });
});

describe("QUERY_KEYS.comments", () => {
  it("글 상세 키 아래에 둬서 상세를 무효화하면 댓글도 함께 다시 불러온다", () => {
    expect(QUERY_KEYS.comments(7).slice(0, 2)).toEqual(
      QUERY_KEYS.postDetail(7),
    );
  });
});
