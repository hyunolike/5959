import { describe, expect, it, vi } from "vitest";

import { ApiError } from "./api-error";
import { requestApi } from "./request-api";

describe("requestApi", () => {
  it("204 No Content면 본문을 읽지 않고 undefined를 돌려준다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));

    await expect(
      requestApi<void>("/api/posts/7", { method: "DELETE" }, fetchMock),
    ).resolves.toBeUndefined();
  });

  it("실패 봉투면 상태와 코드를 담은 ApiError를 던진다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          data: null,
          error: { code: "NOT_AUTHOR", message: "작성자만 할 수 있습니다." },
        }),
        { status: 403 },
      ),
    );

    const error = await requestApi("/api/posts/7", {}, fetchMock).catch(
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: "NOT_AUTHOR" });
  });
});
