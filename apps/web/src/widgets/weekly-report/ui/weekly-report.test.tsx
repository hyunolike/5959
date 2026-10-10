import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  LETTER_PENDING_NOTICE,
  SUPPORT_LETTER,
  WeeklyReport,
} from "./weekly-report";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const ok = (data: unknown) =>
  jsonResponse({ success: true, data, error: null });

function report(overrides: Record<string, unknown> = {}) {
  return {
    weekStart: "2026-10-05",
    weekEnd: "2026-10-11",
    postCount: 3,
    emotionCounts: [
      { emotion: "ANXIETY", count: 2 },
      { emotion: "LETHARGY", count: 0 },
      { emotion: "LONELINESS", count: 0 },
      { emotion: "SELF_DEPRECATION", count: 0 },
      { emotion: "IRRITATION", count: 1 },
    ],
    unanalyzedCount: 0,
    topEmotion: "ANXIETY",
    defeatedCount: 1,
    receivedLikes: 12,
    receivedComments: 4,
    letterStatus: "DONE",
    letter: "지난주에는 불안한 마음이 많으셨네요.",
    previous: null,
    publishedAt: "2026-10-12T20:00:00Z",
    ...overrides,
  };
}

const RESOURCES = [
  {
    name: "자살예방상담전화",
    phone: "109",
    hours: "24시간",
    description: "전화하면 바로 상담사와 연결돼요",
  },
];

/** 리포트 조회에는 [responses]를 차례로, 도움 리소스 조회에는 목록을 돌려준다. */
function renderReport(...responses: Response[]) {
  const reportCalls = vi.fn();
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).includes("support-resources")) {
      return ok(RESOURCES);
    }
    reportCalls(String(input));
    return responses.shift() ?? ok(report());
  });
  vi.stubGlobal("fetch", fetchMock);
  const queryClient = new QueryClient();
  render(
    <QueryClientProvider client={queryClient}>
      <WeeklyReport weekStart="2026-10-05" />
    </QueryClientProvider>,
  );
  return { reportCalls };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("WeeklyReport", () => {
  it("US1-AC3 기간, 글 수, 감정별 글 수와 가장 많은 감정, 처치 수, 받은 공감과 댓글 수를 보여 준다", async () => {
    const { reportCalls } = renderReport(ok(report()));

    const article = await screen.findByRole("article", {
      name: "주간 리포트",
    });
    expect(article).toHaveTextContent("10월 5일 ~ 10월 11일");
    expect(article).toHaveTextContent("가장 많았던 감정 불안");
    const emotions = within(
      screen.getByRole("list", { name: "감정별 글 수" }),
    ).getAllByRole("listitem");
    expect(emotions.map((item) => item.textContent)).toEqual([
      "불안2개",
      "무기력0개",
      "외로움0개",
      "자기비하0개",
      "짜증1개",
    ]);
    // 가장 많은 수가 가득 찬 막대다
    const bars = screen.getAllByTestId("emotion-bar");
    expect(bars[0]).toHaveStyle({ width: "100%" });
    expect(bars[4]).toHaveStyle({ width: "50%" });
    expect(bars[1]).toHaveStyle({ width: "0%" });
    const numbers = screen.getByLabelText("지난주의 수치");
    expect(numbers).toHaveTextContent("쓴 글3");
    expect(numbers).toHaveTextContent("처치된 몬스터1");
    expect(numbers).toHaveTextContent("받은 공감12");
    expect(numbers).toHaveTextContent("받은 댓글4");
    expect(reportCalls).toHaveBeenCalledWith(
      "/api/members/me/weekly-reports/2026-10-05",
    );
  });

  it("US1-AC7 분석되지 않은 글의 수를 따로 보이고, 분석된 글이 없으면 가장 많은 감정이 없다", async () => {
    renderReport(
      ok(
        report({
          postCount: 2,
          emotionCounts: report().emotionCounts.map((item) => ({
            ...item,
            count: 0,
          })),
          unanalyzedCount: 2,
          topEmotion: null,
        }),
      ),
    );

    expect(await screen.findByText("분석되지 않은 글 2개")).toBeInTheDocument();
    expect(
      screen.getByText("감정 분석이 끝난 글이 없어요"),
    ).toBeInTheDocument();
    screen
      .getAllByTestId("emotion-bar")
      .forEach((bar) => expect(bar).toHaveStyle({ width: "0%" }));
  });

  it("US2-AC1 AI가 쓴 편지를 보여 준다", async () => {
    renderReport(ok(report()));

    const letter = await screen.findByRole("region", { name: "편지" });
    expect(letter).toHaveTextContent("지난주에는 불안한 마음이 많으셨네요.");
    expect(screen.queryByText(LETTER_PENDING_NOTICE)).not.toBeInTheDocument();
  });

  it("US2-AC2 편지가 아직이면 준비 중이라고 알리고 수치는 그대로 보인다", async () => {
    renderReport(ok(report({ letterStatus: "PENDING", letter: null })));

    const letter = await screen.findByRole("region", { name: "편지" });
    expect(letter).toHaveTextContent(LETTER_PENDING_NOTICE);
    expect(screen.getByLabelText("지난주의 수치")).toHaveTextContent("쓴 글3");
  });

  it("US2-AC3 편지가 써지면 새로고침 없이 나타난다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { reportCalls } = renderReport(
      ok(report({ letterStatus: "PENDING", letter: null })),
      ok(report({ letterStatus: "PENDING", letter: null })),
      ok(report()),
    );

    await screen.findByText(LETTER_PENDING_NOTICE);
    await act(() => vi.advanceTimersByTimeAsync(6_000));

    expect(
      await screen.findByRole("region", { name: "편지" }),
    ).toHaveTextContent("지난주에는 불안한 마음이 많으셨네요.");
    // 편지가 온 뒤에는 다시 받지 않는다
    await act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(reportCalls).toHaveBeenCalledTimes(3);
  });

  it("US2-AC4 편지 없이 닫힌 리포트는 편지 구역 없이 수치만 보인다", async () => {
    renderReport(ok(report({ letterStatus: "GIVEN_UP", letter: null })));

    await screen.findByRole("article", { name: "주간 리포트" });
    expect(
      screen.queryByRole("region", { name: "편지" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(LETTER_PENDING_NOTICE)).not.toBeInTheDocument();
    expect(screen.getByLabelText("지난주의 수치")).toBeInTheDocument();
  });

  it("US2-AC6 위기 글이 있던 주는 편지 대신 정해 둔 문구와 도움받을 곳을 보인다", async () => {
    renderReport(ok(report({ letterStatus: "SUPPORT", letter: null })));

    const notice = await screen.findByRole("region", { name: "도움 안내" });
    expect(notice).toHaveTextContent(SUPPORT_LETTER);
    const link = await within(notice).findByRole("link", { name: "109" });
    expect(link).toHaveAttribute("href", "tel:109");
    expect(
      screen.queryByRole("region", { name: "편지" }),
    ).not.toBeInTheDocument();
    // 판정을 통보하지 않는다
    expect(notice).not.toHaveTextContent("위기");
  });

  it.each([
    [
      5,
      "LETHARGY",
      "앞 주보다 글이 2개 줄었어요.",
      "가장 많은 감정이 무기력에서 불안(으)로 바뀌었어요.",
    ],
    [
      1,
      "ANXIETY",
      "앞 주보다 글이 2개 늘었어요.",
      "가장 많은 감정은 앞 주와 같이 불안이에요.",
    ],
    [3, null, "글 수는 앞 주와 같아요.", null],
  ])(
    "US4-AC4 앞 주(글 %i개, %s)와 견준 변화를 보여 준다",
    async (postCount, topEmotion, posts, emotion) => {
      renderReport(ok(report({ previous: { postCount, topEmotion } })));

      const region = await screen.findByRole("region", {
        name: "앞 주와 견주기",
      });
      expect(region).toHaveTextContent(posts);
      if (emotion) {
        expect(region).toHaveTextContent(emotion);
      } else {
        expect(within(region).getAllByRole("listitem")).toHaveLength(1);
      }
    },
  );

  it("US4-AC4 앞 주의 리포트가 없으면 견주는 부분이 없다", async () => {
    renderReport(ok(report()));

    await screen.findByRole("article", { name: "주간 리포트" });
    expect(
      screen.queryByRole("region", { name: "앞 주와 견주기" }),
    ).not.toBeInTheDocument();
  });

  it("US1-AC9 없는 리포트면 안내와 내 리포트로 가는 길을 보인다", async () => {
    const { reportCalls } = renderReport(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "WEEKLY_REPORT_NOT_FOUND", message: "없음" },
        },
        404,
      ),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이 주의 리포트가 없어요.",
    );
    expect(
      screen.getByRole("link", { name: "내 리포트 보기" }),
    ).toHaveAttribute("href", "/my?tab=reports");
    // 없는 것은 다시 묻지 않는다
    expect(reportCalls).toHaveBeenCalledTimes(1);
  });
});
