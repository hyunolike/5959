import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

// 내 글이 있는지는 따로 받는 쿼리다. fetch를 세는 테스트에 섞이지 않게 값으로 고정한다.
let hasMyPost: boolean | undefined = true;
vi.mock("@/entities/post", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/entities/post")>()),
  useHasMyPostQuery: () => hasMyPost,
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
  hasMyPost = true;
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

  it("US1-AC1 올리기에 성공하면 상세로 넘어가는 동안 다시 누를 수 없다", async () => {
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
    await fillAndSubmit(user, "고민");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/42"));
    expect(screen.getByRole("button", { name: "올리기" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "올리기" }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("글자 수 카운터는 입력마다 읽어 주지 않고, 500자를 넘었을 때만 알린다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    await user.type(screen.getByLabelText("고민"), "고민");
    const counter = screen.getByText("2/500");
    expect(counter.closest("[aria-live]")).toBeNull();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();

    await user.click(screen.getByLabelText("고민"));
    await user.paste("가".repeat(499));
    expect(screen.getByRole("status")).toHaveTextContent("500자를 넘었어요.");
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

  it("DS-US2-AC1 댓글 말투는 공용 칩이고 고른 것만 눌린 상태다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    await user.click(screen.getByRole("button", { name: "웃겨주기" }));
    const chosen = screen.getByRole("button", { name: "웃겨주기" });
    expect(chosen).toHaveClass("h-10", "rounded-full");
    expect(chosen).toHaveAttribute("aria-pressed", "true");
    expect(
      screen.getByRole("button", { name: "대신 욕해주기" }),
    ).toHaveAttribute("aria-pressed", "false");
  });

  it("DS-US2-AC2 본문 입력은 공용 입력 모양이다", () => {
    renderForm();
    expect(screen.getByLabelText("고민")).toHaveClass(
      "border-line-strong",
      "rounded-control",
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

  it("US1-AC3 입력란이 비어 있으면 쓰기 실마리가 3~5개 보인다", () => {
    renderForm();

    const starters = within(
      screen.getByRole("group", { name: "쓰기 실마리" }),
    ).getAllByRole("button");
    expect(starters.length).toBeGreaterThanOrEqual(3);
    expect(starters.length).toBeLessThanOrEqual(5);
  });

  it("US1-AC4 실마리를 누르면 그 문장이 입력란에 들어가고 초점이 가서 이어 쓸 수 있다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    const [first] = within(
      screen.getByRole("group", { name: "쓰기 실마리" }),
    ).getAllByRole("button");
    const starter = first.textContent ?? "";
    await user.click(first);

    const textarea = screen.getByLabelText("고민");
    expect(textarea).toHaveFocus();
    await user.keyboard("이어 쓴 말");
    expect(textarea).toHaveValue(`${starter} 이어 쓴 말`);
  });

  it("US1-AC5 글자가 있으면 실마리가 없고 다 지우면 다시 보인다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();
    const textarea = screen.getByLabelText("고민");

    await user.type(textarea, "고민");
    expect(
      screen.queryByRole("group", { name: "쓰기 실마리" }),
    ).not.toBeInTheDocument();

    await user.clear(textarea);
    await user.type(textarea, "   ");
    expect(
      screen.getByRole("group", { name: "쓰기 실마리" }),
    ).toBeInTheDocument();
  });

  it("US1-AC6 말투를 고르면 그 말투의 댓글 예시가 보이고 다른 말투를 고르면 바뀐다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm();

    expect(screen.queryByText(/이런 댓글이 달려요/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "무조건 위로해주기" }));
    const comfort = screen.getByText(/이런 댓글이 달려요/).textContent;
    await user.click(screen.getByRole("button", { name: "웃겨주기" }));
    const laugh = screen.getByText(/이런 댓글이 달려요/).textContent;

    expect(comfort).not.toEqual(laugh);
  });

  it("016 US3-AC1 내 글이 없던 회원은 올린 뒤 첫 글 표시와 함께 상세로 간다", async () => {
    hasMyPost = false;
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            success: true,
            data: { postId: 42, analysisStatus: "PENDING" },
            error: null,
          },
          201,
        ),
      ),
    );

    renderForm();
    await fillAndSubmit(user, "고민");

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/post/42?first=1"),
    );
  });

  it("016 US3-AC3 내 글이 있던 회원은 표시 없이 상세로 간다", async () => {
    hasMyPost = true;
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            success: true,
            data: { postId: 42, analysisStatus: "PENDING" },
            error: null,
          },
          201,
        ),
      ),
    );

    renderForm();
    await fillAndSubmit(user, "고민");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/42"));
  });

  it("016 내 글이 있는지 모르면 표시 없이 상세로 간다", async () => {
    hasMyPost = undefined;
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            success: true,
            data: { postId: 42, analysisStatus: "PENDING" },
            error: null,
          },
          201,
        ),
      ),
    );

    renderForm();
    await fillAndSubmit(user, "고민");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/42"));
  });
});
