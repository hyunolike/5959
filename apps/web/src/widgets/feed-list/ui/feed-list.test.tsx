import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FeedList } from "./feed-list";

const navigation = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/home",
  useRouter: () => ({ replace: navigation.replace }),
  useSearchParams: () => new URLSearchParams(navigation.search),
}));

const observers: { callback: IntersectionObserverCallback }[] = [];

class FakeIntersectionObserver {
  constructor(callback: IntersectionObserverCallback) {
    observers.push({ callback });
  }
  observe() {}
  disconnect() {}
  unobserve() {}
}

function feedItem(postId: number) {
  return {
    postId,
    author: {
      id: 1,
      nickname: `회원${postId}`,
      jobRole: "DESIGN",
      careerYear: "YEAR_3",
    },
    contentPreview: `고민 ${postId}`,
    analysisStatus: "PENDING",
    monster: null,
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    createdAt: "2026-10-03T00:00:00Z",
  };
}

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, error: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

function renderFeed() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <FeedList />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  navigation.search = "";
  navigation.replace.mockReset();
  observers.length = 0;
  vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("FeedList", () => {
  it("US2-AC1 글 목록을 보여 주고, 직군과 경력은 한국어로 보여 준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          ok({ items: [feedItem(2), feedItem(1)], nextCursor: null }),
        ),
    );

    renderFeed();

    expect(await screen.findByText("고민 2")).toBeInTheDocument();
    expect(screen.getByText("고민 1")).toBeInTheDocument();
    expect(screen.getAllByText("디자인 · 3년차")).toHaveLength(2);
  });

  it("US2-AC2 목록 끝이 보이면 다음 쪽을 이어 붙인다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(ok({ items: [feedItem(2)], nextCursor: "NEXT" }))
      .mockResolvedValueOnce(ok({ items: [feedItem(1)], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    renderFeed();
    await screen.findByText("고민 2");

    observers
      .at(-1)!
      .callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );

    expect(await screen.findByText("고민 1")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenLastCalledWith("/api/feed?cursor=NEXT", {
      cache: "no-store",
    });
  });

  it("US2-AC2 인기순에서 순위가 바뀌어 다음 쪽에 같은 글이 또 오면 한 번만 보여 준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          ok({ items: [feedItem(3), feedItem(2)], nextCursor: "NEXT" }),
        )
        .mockResolvedValueOnce(
          ok({ items: [feedItem(3), feedItem(1)], nextCursor: null }),
        ),
    );

    renderFeed();
    await screen.findByText("고민 3");
    observers
      .at(-1)!
      .callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );

    expect(await screen.findByText("고민 1")).toBeInTheDocument();
    expect(screen.getAllByText("고민 3")).toHaveLength(1);
    expect(
      screen.getAllByRole("link").map((link) => link.getAttribute("href")),
    ).toEqual(["/post/3", "/post/2", "/post/1"]);
  });

  it("글이 없으면 빈 목록 안내를 보여 준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ items: [], nextCursor: null })),
    );

    renderFeed();

    expect(
      await screen.findByText("아직 올라온 고민이 없어요."),
    ).toBeInTheDocument();
  });

  it("필터에 맞는 글이 없으면 다른 안내를 보여 준다", async () => {
    navigation.search = "jobRole=HR";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ items: [], nextCursor: null })),
    );

    renderFeed();

    expect(
      await screen.findByText("조건에 맞는 고민이 없어요."),
    ).toBeInTheDocument();
  });

  it("US2-AC3 인기순을 누르면 주소의 검색어를 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ items: [], nextCursor: null })),
    );
    renderFeed();

    fireEvent.click(screen.getByRole("button", { name: "인기순" }));

    expect(navigation.replace).toHaveBeenCalledWith("/home?order=POPULAR", {
      scroll: false,
    });
  });

  it("US2-AC4 직군과 경력을 여러 개 고르면 모두 검색어에 남는다", async () => {
    navigation.search = "jobRole=DESIGN";
    const fetchMock = vi
      .fn()
      .mockResolvedValue(ok({ items: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);
    renderFeed();

    expect(screen.getByRole("button", { name: "디자인" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    fireEvent.click(screen.getByRole("button", { name: "개발" }));

    expect(navigation.replace).toHaveBeenCalledWith(
      "/home?jobRole=DESIGN&jobRole=DEVELOPMENT",
      { scroll: false },
    );
    expect(fetchMock).toHaveBeenCalledWith("/api/feed?jobRole=DESIGN", {
      cache: "no-store",
    });
  });
});
