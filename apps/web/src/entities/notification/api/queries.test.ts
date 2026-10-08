// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import { fetchNotificationsPage, fetchUnreadCount } from "./queries";

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

describe("fetchNotificationsPage", () => {
  const page = { items: [], nextCursor: "NEXT" };

  it("US2-AC1 첫 쪽은 커서 없이 같은 출처의 /api/notifications에서 받는다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: page, error: null }),
      );

    await expect(fetchNotificationsPage(null, fetchImpl)).resolves.toEqual(
      page,
    );
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/notifications",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("US2-AC2 다음 쪽은 받은 커서를 그대로 넘긴다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: page, error: null }),
      );

    await fetchNotificationsPage("a b/c=", fetchImpl);

    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/notifications?cursor=a+b%2Fc%3D",
      expect.objectContaining({ cache: "no-store" }),
    );
  });
});
