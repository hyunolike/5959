import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import { feedRequestPath, fetchFeedPage } from "./use-feed-query";

const jsonResponse = (body: unknown, status = 200) =>
  ({ status, json: () => Promise.resolve(body) }) as unknown as Response;

describe("feedRequestPath", () => {
  it("기본 필터에 커서가 없으면 /api/feed만 부른다", () => {
    expect(
      feedRequestPath({ order: "LATEST", jobRoles: [], careerYears: [] }, null),
    ).toBe("/api/feed");
  });

  it("US2-AC2 다음 쪽은 커서를 붙이고, US2-AC4 필터는 같은 이름을 여러 번 쓴다", () => {
    expect(
      feedRequestPath(
        {
          order: "POPULAR",
          jobRoles: ["DESIGN", "DEVELOPMENT"],
          careerYears: ["YEAR_1"],
        },
        "MTI6MzQ1",
      ),
    ).toBe(
      "/api/feed?order=POPULAR&jobRole=DESIGN&jobRole=DEVELOPMENT&careerYear=YEAR_1&cursor=MTI6MzQ1",
    );
  });
});

describe("fetchFeedPage", () => {
  it("성공하면 피드 한 쪽을 돌려준다", async () => {
    const page = { items: [], nextCursor: null };
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: page, error: null }),
      );

    await expect(
      fetchFeedPage(
        { order: "LATEST", jobRoles: [], careerYears: [] },
        null,
        fetchImpl,
      ),
    ).resolves.toEqual(page);
    expect(fetchImpl).toHaveBeenCalledWith("/api/feed", { cache: "no-store" });
  });

  it("실패하면 ApiError를 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INVALID_REQUEST", message: "잘못된 요청입니다." },
        },
        400,
      ),
    );

    await expect(
      fetchFeedPage(
        { order: "LATEST", jobRoles: [], careerYears: [] },
        "bad",
        fetchImpl,
      ),
    ).rejects.toBeInstanceOf(ApiError);
  });
});
