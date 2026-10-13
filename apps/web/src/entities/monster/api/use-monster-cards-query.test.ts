import { describe, expect, it, vi } from "vitest";

import { fetchMonsterCardsPage } from "./use-monster-cards-query";

const page = {
  items: [],
  nextCursor: null,
  summary: { total: 0, byEmotion: [] },
};

const ok = () =>
  new Response(JSON.stringify({ success: true, data: page, error: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

describe("fetchMonsterCardsPage", () => {
  it("US1-AC5 커서 없이 첫 쪽을 받는다", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => ok());

    await expect(fetchMonsterCardsPage(null, null, fetchMock)).resolves.toEqual(
      page,
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/members/me/monster-cards",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("US4-AC3 커서와 고른 감정을 함께 보낸다", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => ok());

    await fetchMonsterCardsPage("다음쪽", "IRRITATION", fetchMock);

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/api/members/me/monster-cards");
    expect(url.searchParams.get("cursor")).toBe("다음쪽");
    expect(url.searchParams.get("emotion")).toBe("IRRITATION");
  });
});
