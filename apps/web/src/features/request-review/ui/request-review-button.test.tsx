import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RequestReviewButton } from "./request-review-button";

const DONE = "다시 살펴봐 달라고 요청했어요. 결과는 알림으로 알려 드려요.";
const LABEL = "다시 살펴봐 달라고 요청하기";

function stub(status: number, code?: string) {
  const fetchMock = vi.fn().mockImplementation(async () =>
    status === 204
      ? new Response(null, { status: 204 })
      : new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code, message: "서버 문구" },
          }),
          { status, headers: { "content-type": "application/json" } },
        ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderButton(requested = false) {
  const onRequested = vi.fn();
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RequestReviewButton
        targetType="POST"
        targetId={7}
        requested={requested}
        onRequested={onRequested}
      />
    </QueryClientProvider>,
  );
  return onRequested;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("RequestReviewButton", () => {
  it("US4-AC8 누르면 요청했어요로 바뀌고 다시 누를 수 없다", async () => {
    const user = userEvent.setup();
    const fetchMock = stub(204);
    const onRequested = renderButton();

    await user.click(screen.getByRole("button", { name: LABEL }));

    expect(await screen.findByText(DONE)).toHaveAttribute("role", "status");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/review-requests");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      targetType: "POST",
      targetId: 7,
    });
    expect(onRequested).toHaveBeenCalledTimes(1);
  });

  it("US4-AC8 이미 요청했다는 응답(409)도 요청했어요로 바뀐다", async () => {
    const user = userEvent.setup();
    stub(409, "REVIEW_ALREADY_REQUESTED");
    const onRequested = renderButton();

    await user.click(screen.getByRole("button", { name: LABEL }));

    expect(await screen.findByText(DONE)).toHaveAttribute("role", "status");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(onRequested).toHaveBeenCalledTimes(1);
  });

  it("서버가 이미 요청했다고 알려 주면 처음부터 버튼이 없다", () => {
    const fetchMock = stub(204);
    renderButton(true);

    expect(screen.getByRole("status")).toHaveTextContent(DONE);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("그 밖의 실패는 버튼을 남기고 다시 시도하라고 알린다", async () => {
    const user = userEvent.setup();
    stub(500, "INTERNAL_ERROR");
    const onRequested = renderButton();

    await user.click(screen.getByRole("button", { name: LABEL }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "요청하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
    expect(screen.getByRole("button", { name: LABEL })).toBeEnabled();
    expect(onRequested).not.toHaveBeenCalled();
  });

  it("005 US4-AC8 접수 결과를 알릴 자리가 처음부터 있고, 접수되면 초점이 그 안내로 간다", async () => {
    const user = userEvent.setup();
    stub(204);
    renderButton();

    // 내용이 든 채로 새로 생긴 status는 읽히지 않는 일이 많아, 빈 채로 먼저 그려 둔다.
    const status = screen.getByRole("status");
    expect(status).toBeEmptyDOMElement();

    await user.click(screen.getByRole("button", { name: LABEL }));

    expect(await screen.findByText(DONE)).toBe(status);
    // 버튼이 사라져도 키보드 초점이 문서 처음으로 떨어지지 않는다.
    expect(status).toHaveFocus();
  });

  it("005 US4-AC8 요청하는 동안 버튼은 초점을 잃지 않고 두 번 보내지 않는다", async () => {
    const user = userEvent.setup();
    let finish: (response: Response) => void = () => {};
    const fetchMock = vi.fn().mockReturnValue(
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderButton();

    const button = screen.getByRole("button", { name: LABEL });
    await user.click(button);
    await user.click(button);

    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).not.toBeDisabled();
    expect(button).toHaveFocus();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    finish(new Response(null, { status: 204 }));
    expect(await screen.findByText(DONE)).toBeInTheDocument();
  });
});
