import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import type { SimilarPosts } from "../model/types";
import {
  SIMILAR_POLL_LIMIT_MS,
  SIMILAR_POLL_MS,
  fetchSimilarPosts,
  similarPollInterval,
} from "./use-similar-posts-query";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function similar(overrides: Partial<SimilarPosts> = {}): SimilarPosts {
  return { basis: "NONE", items: [], pending: false, ...overrides };
}

describe("similarPollInterval", () => {
  it("US1-AC7 추천이 준비 중이면 3초마다 다시 불러온다", () => {
    expect(similarPollInterval(similar({ pending: true }), 0)).toBe(
      SIMILAR_POLL_MS,
    );
  });

  it("US1-AC7 같은 감정의 글이 먼저 왔어도 준비 중이면 계속 불러온다", () => {
    expect(
      similarPollInterval(
        similar({ basis: "SAME_EMOTION", pending: true }),
        SIMILAR_POLL_LIMIT_MS - 1,
      ),
    ).toBe(SIMILAR_POLL_MS);
  });

  it("US1-AC7 30초가 지나면 그만 부른다", () => {
    expect(
      similarPollInterval(similar({ pending: true }), SIMILAR_POLL_LIMIT_MS),
    ).toBe(false);
  });

  it("준비가 끝났으면 부르지 않는다", () => {
    expect(similarPollInterval(similar({ basis: "SIMILAR" }), 0)).toBe(false);
    expect(similarPollInterval(undefined, 0)).toBe(false);
  });
});

describe("fetchSimilarPosts", () => {
  it("US1-AC1 BFF 프록시의 비슷한 고민 경로를 부른다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: similar(), error: null }),
      );

    await expect(fetchSimilarPosts(7, fetchImpl)).resolves.toEqual(similar());
    expect(fetchImpl).toHaveBeenCalledWith("/api/posts/7/similar", {
      cache: "no-store",
    });
  });

  it("US2-AC7 실패하면 ApiError를 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "오류" },
        },
        500,
      ),
    );

    await expect(fetchSimilarPosts(7, fetchImpl)).rejects.toBeInstanceOf(
      ApiError,
    );
  });
});
