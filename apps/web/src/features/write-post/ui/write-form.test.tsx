import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

import { WriteForm } from "./write-form";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const errorResponse = (status: number, code: string, message: string) =>
  jsonResponse(
    { success: false, data: null, error: { code, message } },
    status,
  );

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <WriteForm />
    </QueryClientProvider>,
  );
}

async function fillAndSubmit(
  user: ReturnType<typeof userEvent.setup>,
  content: string,
) {
  await user.type(screen.getByLabelText("고민"), content);
  await user.click(screen.getByRole("button", { name: "무조건 위로해주기" }));
  await user.click(screen.getByRole("button", { name: "올리기" }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("WriteForm", () => {
  it("US1-AC1 본문과 말투로 올리면 같은 출처 /api/posts로 보내고 상세로 이동한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: true,
          data: { postId: 42, analysisStatus: "PENDING" },
          error: null,
        },
        201,
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await fillAndSubmit(user, "  내일 발표가 걱정돼요  ");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/42"));
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts",
      expect.objectContaining({ method: "POST" }),
    );
    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(body).toEqual({
      content: "내일 발표가 걱정돼요",
      commentTone: "COMFORT_ME",
    });
  });

  it("말투 버튼 4개를 보여 주고 고른 말투만 눌린 상태다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    for (const name of [
      "대신 욕해주기",
      "무조건 위로해주기",
      "따뜻한 조언해주기",
      "웃겨주기",
    ]) {
      expect(screen.getByRole("button", { name })).toHaveAttribute(
        "aria-pressed",
        "false",
      );
    }

    await user.click(screen.getByRole("button", { name: "웃겨주기" }));
    expect(screen.getByRole("button", { name: "웃겨주기" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("글자 수 카운터는 사람이 보는 글자(grapheme)로 센다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    await user.type(screen.getByLabelText("고민"), "오늘 👨‍👩‍👧");
    expect(screen.getByText("4/500")).toBeInTheDocument();
  });

  it("US1-AC2 말투를 고르지 않으면 보내지 않고 안내한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await user.type(screen.getByLabelText("고민"), "고민");
    await user.click(screen.getByRole("button", { name: "올리기" }));

    expect(
      await screen.findByText("댓글 말투를 골라 주세요."),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US1-AC2 501자는 보내지 않고 500자 이하로 적으라고 안내한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await user.click(screen.getByLabelText("고민"));
    await user.paste("가".repeat(501));
    await user.click(screen.getByRole("button", { name: "무조건 위로해주기" }));
    await user.click(screen.getByRole("button", { name: "올리기" }));

    expect(
      await screen.findByText("500자 이하로 적어 주세요."),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("429 POST_RATE_LIMITED면 잠시 뒤 다시 써 달라고 안내한다", async () => {
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          errorResponse(429, "POST_RATE_LIMITED", "글을 너무 많이 썼습니다."),
        ),
    );

    renderForm();
    await fillAndSubmit(user, "고민");

    expect(
      await screen.findByText("잠시 뒤 다시 써 주세요"),
    ).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("US1-AC7 403 ONBOARDING_REQUIRED면 온보딩 화면으로 보낸다", async () => {
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          errorResponse(403, "ONBOARDING_REQUIRED", "온보딩이 필요합니다."),
        ),
    );

    renderForm();
    await fillAndSubmit(user, "고민");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/onboarding"));
  });
});
