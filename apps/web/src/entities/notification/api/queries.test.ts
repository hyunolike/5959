// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import { fetchUnreadCount } from "./queries";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

describe("fetchUnreadCount", () => {
  it("같은 출처의 /api/notifications/unread-count에서 안 읽은 수와 마지막 번호를 받는다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: { count: 3, latestSeq: 17 },
        error: null,
      }),
    );

    await expect(fetchUnreadCount(fetchImpl)).resolves.toEqual({
      count: 3,
      latestSeq: 17,
    });
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/notifications/unread-count",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("오류 응답이면 ApiError를 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
        },
        401,
      ),
    );

    await expect(fetchUnreadCount(fetchImpl)).rejects.toBeInstanceOf(ApiError);
  });
});
