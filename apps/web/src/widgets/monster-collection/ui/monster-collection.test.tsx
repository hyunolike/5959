import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { MonsterCardData, MonsterCardPage } from "@/entities/monster";

import { MonsterCollection } from "./monster-collection";

function card(
  cardNo: number,
  overrides: Partial<MonsterCardData> = {},
): MonsterCardData {
  return {
    cardNo,
    emotion: "ANXIETY",
    grade: "COMMON",
    role: "TOGETHER",
    defeatedAt: "2026-10-12T03:00:00Z",
    myDamage: 4,
    helperCount: null,
    postId: cardNo * 10,
    ...overrides,
  };
}

function summary(counts: Partial<Record<MonsterCardData["emotion"], number>>) {
  const emotions = [
    "ANXIETY",
    "LETHARGY",
    "LONELINESS",
    "SELF_DEPRECATION",
    "IRRITATION",
  ] as const;
  const byEmotion = emotions.map((emotion) => ({
    emotion,
    count: counts[emotion] ?? 0,
  }));
  return {
    total: byEmotion.reduce((sum, item) => sum + item.count, 0),
    byEmotion,
  };
}

const json = (data: unknown, status = 200) =>
  new Response(
    JSON.stringify(
      status === 200
        ? { success: true, data, error: null }
        : {
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "서버 오류" },
          },
    ),
    { status, headers: { "content-type": "application/json" } },
  );

function stubCards(respond: (url: URL) => Response) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) =>
    respond(new URL(String(input), "http://localhost")),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderCollection() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MonsterCollection />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("MonsterCollection", () => {
  it("US1-AC1 모은 카드가 목록으로 보이고 내 몬스터와 함께 물리친 것이 글자로 갈린다", async () => {
    stubCards(() =>
      json({
        items: [
          card(2, { role: "MINE", helperCount: 3, myDamage: 0 }),
          card(1, { emotion: "IRRITATION", grade: "RARE" }),
        ],
        nextCursor: null,
        summary: summary({ ANXIETY: 1, IRRITATION: 1 }),
      } satisfies MonsterCardPage),
    );
    renderCollection();

    const list = await screen.findByRole("list", { name: "모은 카드 목록" });
    const cards = within(list).getAllByRole("button");
    expect(cards).toHaveLength(2);
    // 누르면 대화상자가 열린다는 것을 미리 알린다.
    expect(cards[0]).toHaveAttribute("aria-haspopup", "dialog");
    expect(cards[0]).toHaveTextContent("No.2");
    expect(cards[0]).toHaveTextContent("내 몬스터");
    expect(cards[1]).toHaveTextContent("짜증");
    expect(cards[1]).toHaveTextContent("희귀");
    expect(cards[1]).toHaveTextContent("함께 물리침");
    expect(screen.getByText("모두 2장")).toBeVisible();
  });

  it("US1-AC5 카드가 더 있으면 더 보기로 이어서 받는다", async () => {
    const user = userEvent.setup();
    const fetchMock = stubCards((url) =>
      url.searchParams.get("cursor") === "다음"
        ? json({
            items: [card(1)],
            nextCursor: null,
            summary: summary({ ANXIETY: 2 }),
          })
        : json({
            items: [card(2)],
            nextCursor: "다음",
            summary: summary({ ANXIETY: 2 }),
          }),
    );
    renderCollection();

    await user.click(await screen.findByRole("button", { name: "더 보기" }));

    await waitFor(() =>
      expect(
        within(
          screen.getByRole("list", { name: "모은 카드 목록" }),
        ).getAllByRole("button"),
      ).toHaveLength(2),
    );
    expect(
      screen.queryByRole("button", { name: "더 보기" }),
    ).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("US1-AC6 카드가 없으면 얻는 방법과 글쓰기, 피드로 가는 길을 보인다", async () => {
    stubCards(() =>
      json({ items: [], nextCursor: null, summary: summary({}) }),
    );
    renderCollection();

    expect(await screen.findByText("아직 모은 카드가 없어요")).toBeVisible();
    expect(
      screen.getByText(/공감과 댓글로 함께 물리치면 카드가 생겨요/),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "고민 쓰기" })).toHaveAttribute(
      "href",
      "/write",
    );
    expect(
      screen.getByRole("link", { name: "이야기 보러 가기" }),
    ).toHaveAttribute("href", "/home");
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("US4-AC3 감정을 고르면 그 감정으로 다시 받고 위의 숫자는 그대로다", async () => {
    const user = userEvent.setup();
    const fetchMock = stubCards((url) =>
      url.searchParams.get("emotion") === "IRRITATION"
        ? json({
            items: [card(1, { emotion: "IRRITATION" })],
            nextCursor: null,
            summary: summary({ ANXIETY: 1, IRRITATION: 1 }),
          })
        : json({
            items: [card(2), card(1, { emotion: "IRRITATION" })],
            nextCursor: null,
            summary: summary({ ANXIETY: 1, IRRITATION: 1 }),
          }),
    );
    renderCollection();

    await user.click(await screen.findByRole("button", { name: "짜증 1" }));

    await waitFor(() =>
      expect(
        within(
          screen.getByRole("list", { name: "모은 카드 목록" }),
        ).getAllByRole("button"),
      ).toHaveLength(1),
    );
    expect(screen.getByText("모두 2장")).toBeVisible();
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).includes("emotion=IRRITATION"),
      ),
    ).toBe(true);
  });

  it("US3-AC1 카드를 누르면 크게 보이고 닫으면 그 카드로 초점이 돌아온다", async () => {
    const user = userEvent.setup();
    stubCards(() =>
      json({
        items: [card(7)],
        nextCursor: null,
        summary: summary({ ANXIETY: 1 }),
      }),
    );
    renderCollection();
    const opener = within(
      await screen.findByRole("list", { name: "모은 카드 목록" }),
    ).getByRole("button");

    await user.click(opener);
    expect(
      screen.getByRole("dialog", { name: "불안 카드 No.7" }),
    ).toBeVisible();

    await user.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it("불러오는 중에는 글자로 알리고, 불러오지 못하면 다시 시도할 수 있다", async () => {
    const user = userEvent.setup();
    let fail = true;
    stubCards(() =>
      fail
        ? json(null, 500)
        : json({ items: [], nextCursor: null, summary: summary({}) }),
    );
    renderCollection();

    expect(screen.getByRole("status")).toHaveTextContent(
      "몬스터 도감 불러오는 중",
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "도감을 불러오지 못했습니다.",
    );

    fail = false;
    await user.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("아직 모은 카드가 없어요")).toBeVisible();
  });

  it("FR-016 순위나 재촉하는 문구가 없다", async () => {
    stubCards(() =>
      json({
        items: [card(1)],
        nextCursor: null,
        summary: summary({ ANXIETY: 1 }),
      }),
    );
    renderCollection();

    await screen.findByRole("list", { name: "모은 카드 목록" });
    expect(document.body).not.toHaveTextContent(
      /순위|랭킹|연속|더 모아|서둘러|남았어요/,
    );
  });
});
