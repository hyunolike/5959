import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Comment } from "@/entities/comment";

import { worstSafetyOfMine } from "./post-comments";
import {
  needsSafetyNotice,
  SafetyNotice,
  type ContentSafety,
} from "./safety-notice";

const RESOURCES = [
  {
    name: "자살예방상담전화",
    phone: "109",
    hours: "24시간",
    description: "지금 힘든 마음을 이야기할 수 있어요",
  },
  {
    name: "정신건강위기상담전화",
    phone: "1577-0199",
    hours: "24시간",
    description: "가까운 정신건강복지센터로 연결돼요",
  },
  {
    name: "청소년상담전화",
    phone: "1388",
    hours: "24시간",
    description: "청소년과 보호자가 상담받을 수 있어요",
  },
];

function stubResources(status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify(
        status === 200
          ? { success: true, data: RESOURCES, error: null }
          : {
              success: false,
              data: null,
              error: { code: "INTERNAL_ERROR", message: "실패" },
            },
      ),
      { status, headers: { "content-type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderNotice(
  safety: ContentSafety | undefined,
  target: "post" | "comment" = "post",
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <SafetyNotice
        safety={safety}
        target={target}
        action={<button type="button">다시 살펴봐 주세요</button>}
      />
    </QueryClientProvider>,
  );
}

const safety = (overrides: Partial<ContentSafety> = {}): ContentSafety => ({
  level: "NONE",
  hidden: false,
  reviewRequested: false,
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("SafetyNotice", () => {
  it("US1-AC1 위기면 닫을 수 없는 안내와 전화 링크 셋이 보인다", async () => {
    const fetchMock = stubResources();

    renderNotice(safety({ level: "CRISIS", hidden: true }));

    const notice = screen.getByRole("region", { name: "도움 안내" });
    const list = await within(notice).findByRole("list", {
      name: "도움받을 수 있는 곳",
    });
    const links = within(list).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "tel:109",
      "tel:15770199",
      "tel:1388",
    ]);
    expect(links.map((link) => link.textContent)).toEqual([
      "109",
      "1577-0199",
      "1388",
    ]);
    // 접는 버튼이 없다
    expect(
      within(notice).queryByRole("button", { name: /접기|도움받을 곳 보기/ }),
    ).not.toBeInTheDocument();
    // 단계 이름은 화면에 쓰지 않는다(기관 이름의 "위기"는 예외다)
    expect(notice).not.toHaveTextContent(/CRISIS|CONCERN|위기로|우려로/);
    expect(notice).toHaveTextContent("마음이 많이 힘드신가요?");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/safety/support-resources",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("US1-AC3 숨겨졌으면 다른 회원에게 보이지 않는다는 설명과 넘겨받은 행동이 보인다", async () => {
    stubResources();

    renderNotice(safety({ level: "CRISIS", hidden: true }));

    const notice = screen.getByRole("region", { name: "도움 안내" });
    expect(notice).toHaveTextContent(
      "이 글은 다른 회원에게 보이지 않아요. 나에게는 그대로 보여요.",
    );
    expect(
      within(notice).getByRole("button", { name: "다시 살펴봐 주세요" }),
    ).toBeVisible();
    await within(notice).findByRole("list");
  });

  it("US1-AC4 우려면 숨김 설명 없이 접을 수 있는 안내가 보인다", async () => {
    const user = userEvent.setup();
    stubResources();

    renderNotice(safety({ level: "CONCERN" }));

    const notice = screen.getByRole("region", { name: "도움 안내" });
    await within(notice).findByRole("list");
    expect(notice).not.toHaveTextContent("보이지 않아요");
    // 숨겨지지 않았으면 재검토 행동을 두지 않는다
    expect(
      within(notice).queryByRole("button", { name: "다시 살펴봐 주세요" }),
    ).not.toBeInTheDocument();

    await user.click(within(notice).getByRole("button", { name: "접기" }));

    expect(within(notice).queryByRole("list")).not.toBeInTheDocument();
    const reopen = within(notice).getByRole("button", {
      name: "도움받을 곳 보기",
    });
    expect(reopen).toHaveAttribute("aria-expanded", "false");
    await user.click(reopen);
    expect(within(notice).getByRole("list")).toBeVisible();
  });

  it("US1-AC5 댓글이 숨겨졌으면 내 댓글 가운데 보이지 않는 것이 있다고 알린다", async () => {
    stubResources();

    renderNotice(safety({ level: "CRISIS", hidden: true }), "comment");

    const notice = screen.getByRole("region", { name: "도움 안내" });
    expect(notice).toHaveTextContent(
      "내 댓글 가운데 다른 회원에게 보이지 않는 것이 있어요. 나에게는 그대로 보여요.",
    );
    await within(notice).findByRole("list");
  });

  it("safety가 없거나 위험이 없고 숨겨지지 않았으면 아무것도 그리지 않고 리소스도 받지 않는다", () => {
    const fetchMock = stubResources();

    renderNotice(undefined);
    renderNotice(safety());

    expect(screen.queryByRole("region", { name: "도움 안내" })).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(needsSafetyNotice(undefined)).toBe(false);
    expect(needsSafetyNotice(safety())).toBe(false);
    expect(needsSafetyNotice(safety({ hidden: true }))).toBe(true);
  });

  it("도움 리소스를 받지 못해도 자살예방상담전화 109는 보인다", async () => {
    stubResources(500);

    renderNotice(safety({ level: "CRISIS", hidden: true }));

    const link = await screen.findByRole("link", { name: "109" });
    expect(link).toHaveAttribute("href", "tel:109");
  });
});

describe("worstSafetyOfMine", () => {
  const comment = (
    commentId: number,
    mine: ContentSafety | undefined,
    replies: Comment[] = [],
  ): Comment => ({
    commentId,
    author: null,
    content: null,
    hidden: false,
    safety: mine,
    likeCount: 0,
    likedByMe: false,
    mine: mine !== undefined,
    createdAt: "2026-10-08T00:00:00Z",
    replies,
  });

  it("내 댓글이 없으면 undefined다", () => {
    expect(worstSafetyOfMine([])).toBeUndefined();
    expect(worstSafetyOfMine([comment(1, undefined)])).toBeUndefined();
  });

  it("내 댓글과 답글 가운데 가장 무거운 단계를 고르고, 하나라도 숨겨졌으면 숨김이다", () => {
    const worst = worstSafetyOfMine([
      comment(1, safety({ level: "CONCERN" })),
      comment(2, undefined, [
        comment(3, safety({ level: "CRISIS", hidden: true })),
      ]),
      comment(4, safety()),
    ]);

    expect(worst).toEqual({
      level: "CRISIS",
      hidden: true,
      reviewRequested: false,
    });
  });
});
