import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import type { WeeklyReport } from "../model/types";
import {
  LETTER_POLL_FAST_MS,
  LETTER_POLL_LIMIT_MS,
  LETTER_POLL_SLOW_MS,
  LETTER_POLL_SLOWDOWN_AFTER_MS,
  fetchWeeklyReport,
  fetchWeeklyReportsPage,
  letterPollInterval,
} from "./queries";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function report(letterStatus: WeeklyReport["letterStatus"]): WeeklyReport {
  return {
    weekStart: "2026-10-05",
    weekEnd: "2026-10-11",
    postCount: 1,
    emotionCounts: [],
    unanalyzedCount: 0,
    topEmotion: null,
    defeatedCount: 0,
    receivedLikes: 0,
    receivedComments: 0,
    letterStatus,
    letter: letterStatus === "DONE" ? "편지" : null,
    previous: null,
    publishedAt: "2026-10-12T20:00:00Z",
  };
}

describe("letterPollInterval", () => {
  it("US2-AC3 편지를 쓰는 중이면 처음 30초는 3초마다 다시 받는다", () => {
    expect(letterPollInterval(report("PENDING"), 0)).toBe(LETTER_POLL_FAST_MS);
    expect(
      letterPollInterval(report("PENDING"), LETTER_POLL_SLOWDOWN_AFTER_MS - 1),
    ).toBe(LETTER_POLL_FAST_MS);
  });

  it("US2-AC3 그 뒤에는 15초마다 받아, 써진 편지가 30초 안에 보인다", () => {
    expect(
      letterPollInterval(report("PENDING"), LETTER_POLL_SLOWDOWN_AFTER_MS),
    ).toBe(LETTER_POLL_SLOW_MS);
    expect(LETTER_POLL_SLOW_MS).toBeLessThanOrEqual(30_000);
  });

  it("10분이 지나면 그만 받는다", () => {
    expect(letterPollInterval(report("PENDING"), LETTER_POLL_LIMIT_MS)).toBe(
      false,
    );
  });

  it.each(["DONE", "GIVEN_UP", "SUPPORT"] as const)(
    "편지를 기다리지 않는 상태(%s)면 받지 않는다",
    (status) => {
      expect(letterPollInterval(report(status), 0)).toBe(false);
    },
  );

  it("아직 받은 것이 없으면 받지 않는다", () => {
    expect(letterPollInterval(undefined, 0)).toBe(false);
  });
});

describe("fetchWeeklyReport", () => {
  it("US1-AC3 BFF 프록시의 그 주 리포트 경로를 부른다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: report("DONE"), error: null }),
      );

    await expect(fetchWeeklyReport("2026-10-05", fetchImpl)).resolves.toEqual(
      report("DONE"),
    );
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/members/me/weekly-reports/2026-10-05",
      { cache: "no-store" },
    );
  });

  it("US1-AC9 없는 리포트는 404 ApiError다", async () => {
    const notFound = () =>
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "WEEKLY_REPORT_NOT_FOUND", message: "없음" },
        },
        404,
      );
    const fetchImpl = vi.fn().mockImplementation(async () => notFound());

    await expect(
      fetchWeeklyReport("2026-10-05", fetchImpl),
    ).rejects.toMatchObject({ status: 404 });
    await expect(fetchWeeklyReport("../me", fetchImpl)).rejects.toBeInstanceOf(
      ApiError,
    );
    // 주소의 값이 경로를 벗어나지 않게 감싼다
    expect(fetchImpl).toHaveBeenLastCalledWith(
      "/api/members/me/weekly-reports/..%2Fme",
      { cache: "no-store" },
    );
  });
});

describe("fetchWeeklyReportsPage", () => {
  it("US4-AC1 첫 쪽은 커서 없이, 다음 쪽은 커서를 붙여 부른다", async () => {
    const page = { items: [], nextCursor: null };
    const fetchImpl = vi
      .fn()
      .mockImplementation(async () =>
        jsonResponse({ success: true, data: page, error: null }),
      );

    await fetchWeeklyReportsPage(null, fetchImpl);
    await fetchWeeklyReportsPage("2026-10-05", fetchImpl);

    expect(fetchImpl).toHaveBeenNthCalledWith(
      1,
      "/api/members/me/weekly-reports",
      { cache: "no-store" },
    );
    expect(fetchImpl).toHaveBeenNthCalledWith(
      2,
      "/api/members/me/weekly-reports?cursor=2026-10-05",
      { cache: "no-store" },
    );
  });
});
