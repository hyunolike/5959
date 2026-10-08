import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import { fetchLikedPostsPage } from "./use-liked-posts-query";
import { fetchMyPostsPage } from "./use-my-posts-query";

const page = { items: [], nextCursor: "NEXT" };

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const okFetch = () =>
  vi
    .fn()
    .mockResolvedValue(
      jsonResponse({ success: true, data: page, error: null }),
    );

describe.each([
  ["fetchMyPostsPage", fetchMyPostsPage, "/api/members/me/posts"],
  ["fetchLikedPostsPage", fetchLikedPostsPage, "/api/members/me/liked-posts"],
] as const)("%s", (_name, fetchPage, path) => {
  it("첫 쪽은 커서 없이 같은 출처 BFF에서 받는다", async () => {
    const fetchImpl = okFetch();

    await expect(fetchPage(null, fetchImpl)).resolves.toEqual(page);
    expect(fetchImpl).toHaveBeenCalledWith(
      path,
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("다음 쪽은 받은 커서를 그대로 넘긴다", async () => {
    const fetchImpl = okFetch();

    await fetchPage("a b/c=", fetchImpl);

    expect(fetchImpl).toHaveBeenCalledWith(
      `${path}?cursor=a+b%2Fc%3D`,
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("실패 응답은 ApiError로 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INVALID_REQUEST", message: "커서가 틀림" },
        },
        400,
      ),
    );

    await expect(fetchPage("x", fetchImpl)).rejects.toBeInstanceOf(ApiError);
  });
});
