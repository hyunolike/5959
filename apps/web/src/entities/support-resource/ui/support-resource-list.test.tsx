import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SupportResourceList } from "./support-resource-list";

const RESOURCES = [
  {
    name: "정신건강위기상담전화",
    phone: "1577-0199",
    hours: "24시간",
    description: "가까운 정신건강복지센터로 연결돼요",
  },
];

function renderList() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <SupportResourceList />
    </QueryClientProvider>,
  );
}

function respond(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("SupportResourceList", () => {
  it("005 US1-AC1 전화 링크는 어느 기관인지 이름으로 읽히고 누르는 영역이 44px이다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          respond({ success: true, data: RESOURCES, error: null }),
        ),
    );
    renderList();

    const link = await screen.findByRole("link", {
      name: "정신건강위기상담전화 1577-0199 전화 걸기",
    });
    expect(link).toHaveAttribute("href", "tel:15770199");
    expect(link).toHaveTextContent(/^1577-0199$/);
    expect(link).toHaveClass("min-h-11");
    // 운영 시간은 가장 작은 글자 단계로 쓰지 않는다.
    expect(screen.getByText(/24시간/)).not.toHaveClass("text-caption");
  });

  it("005 US1-AC1 목록을 받는 동안에도 가장 중요한 번호는 보인다", () => {
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));
    renderList();

    const link = screen.getByRole("link", {
      name: "자살예방상담전화 109 전화 걸기",
    });
    expect(link).toHaveAttribute("href", "tel:109");
    expect(link).toHaveClass("min-h-11");
  });

  it("005 US1-AC1 목록을 못 받아도 가장 중요한 번호는 보인다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        respond(
          {
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "실패" },
          },
          500,
        ),
      ),
    );
    renderList();

    expect(
      await screen.findByRole("link", {
        name: "자살예방상담전화 109 전화 걸기",
      }),
    ).toHaveAttribute("href", "tel:109");
  });
});
