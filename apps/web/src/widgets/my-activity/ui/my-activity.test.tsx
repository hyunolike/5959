import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { MyActivity } from "./my-activity";

const navigation = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/my",
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

function feedItem(postId: number, nickname = "오구") {
  return {
    postId,
    author: { id: 1, nickname, jobRole: "DESIGN", careerYear: "YEAR_3" },
    contentPreview: `고민 ${postId}`,
    analysisStatus: "PENDING",
    monster: null,
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    createdAt: "2026-10-03T00:00:00Z",
  };
}

function myComment(commentId: number) {
  return {
    commentId,
    postId: 100 + commentId,
    postContentPreview: `댓글을 단 글 ${commentId}`,
    content: `내 댓글 ${commentId}`,
    reply: false,
    createdAt: "2026-10-03T00:00:00Z",
  };
}

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, error: null }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });

const EMPTY = { items: [], nextCursor: null };

/** 경로마다 정해 둔 쪽을 돌려준다. 정하지 않은 경로는 빈 목록이다. */
function stubLists(pages: Record<string, unknown> = {}) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    return ok(pages[path] ?? EMPTY);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderActivity() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MyActivity />
    </QueryClientProvider>,
  );
}

const selectedTab = () => screen.getByRole("tab", { selected: true });

beforeEach(() => {
  navigation.search = "";
  navigation.replace.mockReset();
  observers.length = 0;
  vi.stubGlobal("IntersectionObserver", FakeIntersectionObserver);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("MyActivity", () => {
  it.each([
    ["", "내가 쓴 글", "/api/members/me/posts", "아직 쓴 글이 없어요."],
    [
      "tab=comments",
      "내 댓글",
      "/api/members/me/comments",
      "아직 남긴 댓글이 없어요.",
    ],
    [
      "tab=likes",
      "공감한 글",
      "/api/members/me/liked-posts",
      "아직 공감한 글이 없어요.",
    ],
  ])(
    'US3-AC4 탭(%s)이 비면 안내와 "글쓰기", "피드 보기" 버튼이 보인다',
    async (search, label, path, message) => {
      navigation.search = search;
      const fetchMock = stubLists();

      renderActivity();

      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(selectedTab()).toHaveTextContent(label);
      const panel = screen.getByRole("tabpanel", { name: label });
      expect(
        within(panel).getByRole("link", { name: "글쓰기" }),
      ).toHaveAttribute("href", "/write");
      expect(
        within(panel).getByRole("link", { name: "피드 보기" }),
      ).toHaveAttribute("href", "/home");
      // 고른 탭의 목록만 받는다.
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][0]).toBe(path);
    },
  );

  it.each(["tab=POSTS", "tab=liked", "tab=", "other=1"])(
    "tab 값이 잘못됐으면(%s) 내가 쓴 글 탭이다",
    async (search) => {
      navigation.search = search;
      const fetchMock = stubLists();

      renderActivity();

      await screen.findByText("아직 쓴 글이 없어요.");
      expect(selectedTab()).toHaveTextContent("내가 쓴 글");
      expect(fetchMock.mock.calls[0][0]).toBe("/api/members/me/posts");
    },
  );

  it("탭을 누르면 주소의 ?tab=을 바꾸고, 기본 탭은 검색어를 지운다", async () => {
    navigation.search = "tab=comments";
    stubLists();
    renderActivity();
    await screen.findByText("아직 남긴 댓글이 없어요.");

    await userEvent.click(screen.getByRole("tab", { name: "공감한 글" }));
    expect(navigation.replace).toHaveBeenLastCalledWith("/my?tab=likes", {
      scroll: false,
    });

    await userEvent.click(screen.getByRole("tab", { name: "내가 쓴 글" }));
    expect(navigation.replace).toHaveBeenLastCalledWith("/my", {
      scroll: false,
    });

    // 이미 고른 탭을 다시 눌러도 주소를 건드리지 않는다.
    navigation.replace.mockClear();
    await userEvent.click(screen.getByRole("tab", { name: "내 댓글" }));
    expect(navigation.replace).not.toHaveBeenCalled();
  });

  it("US3-AC1 내가 쓴 글은 피드 카드로 보이고 누르면 그 글로 간다", async () => {
    stubLists({
      "/api/members/me/posts": {
        items: [feedItem(2), feedItem(1)],
        nextCursor: null,
      },
    });

    renderActivity();

    const list = await screen.findByRole("list", { name: "내가 쓴 글" });
    const links = within(list).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/post/2",
      "/post/1",
    ]);
    expect(links[0]).toHaveTextContent("고민 2");
    expect(links[0]).toHaveTextContent("디자인 · 3년차");
    expect(links[0]).toHaveTextContent("분석 중");
    expect(links[0].querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-10-03T00:00:00Z",
    );
  });

  it("US3-AC2 내 댓글은 본문과 달린 글의 앞부분을 보이고 누르면 그 글로 간다", async () => {
    navigation.search = "tab=comments";
    stubLists({
      "/api/members/me/comments": {
        items: [myComment(2), myComment(1)],
        nextCursor: null,
      },
    });

    renderActivity();

    const list = await screen.findByRole("list", { name: "내 댓글" });
    const links = within(list).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/post/102",
      "/post/101",
    ]);
    expect(links[0]).toHaveTextContent("내 댓글 2");
    expect(links[0]).toHaveTextContent("댓글을 단 글 2");
  });

  it("US3-AC3 공감한 글은 받은 순서대로 보이고, 목록 끝이 보이면 다음 쪽을 이어 붙인다", async () => {
    navigation.search = "tab=likes";
    const fetchMock = stubLists({
      "/api/members/me/liked-posts": {
        items: [feedItem(5, "남"), feedItem(9, "남")],
        nextCursor: "NEXT",
      },
      "/api/members/me/liked-posts?cursor=NEXT": {
        items: [feedItem(3, "남")],
        nextCursor: null,
      },
    });

    renderActivity();

    const list = await screen.findByRole("list", { name: "공감한 글" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "더 보기" })).toBeInTheDocument();

    observers
      .at(-1)!
      .callback(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );

    expect(await screen.findByText("고민 3")).toBeInTheDocument();
    expect(
      within(list)
        .getAllByRole("link")
        .map((link) => link.getAttribute("href")),
    ).toEqual(["/post/5", "/post/9", "/post/3"]);
    expect(
      screen.queryByRole("button", { name: "더 보기" }),
    ).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("목록을 불러오지 못하면 안내를 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "실패" },
          }),
          { status: 500, headers: { "content-type": "application/json" } },
        ),
      ),
    );

    renderActivity();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "목록을 불러오지 못했습니다.",
    );
  });
});
