import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

import { DeletePostButton } from "./delete-post-button";

function renderButton(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  const confirmSpy = vi.fn();
  vi.stubGlobal("confirm", confirmSpy);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(["posts", 7], { postId: 7 });
  queryClient.setQueryData(["feed", { order: "LATEST" }], { pages: [] });
  const { unmount } = render(
    <QueryClientProvider client={queryClient}>
      <DeletePostButton postId={7} />
    </QueryClientProvider>,
  );
  return { fetchMock, confirmSpy, queryClient, unmount };
}

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("DeletePostButton", () => {
  it("US4-AC2 확인 대화상자에서 삭제하면 DELETE를 보내고 /home으로 가며, 화면을 떠난 뒤에 상세 캐시를 지우고 피드를 낡게 표시한다", async () => {
    const user = userEvent.setup({ delay: null });
    const { fetchMock, confirmSpy, queryClient, unmount } = renderButton(
      new Response(null, { status: 204 }),
    );

    await user.click(screen.getByRole("button", { name: "삭제" }));
    const dialog = screen.getByRole("alertdialog", { name: "글을 지울까요?" });
    expect(fetchMock).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "삭제하기" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/home"));
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts/7",
      expect.objectContaining({ method: "DELETE" }),
    );
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(dialog).toBeInTheDocument();
    // 아직 상세 화면에 있는 동안 지우면 상세가 다시 불러와 404("삭제된 글이에요")가 잠깐 보인다
    expect(queryClient.getQueryData(["posts", 7])).toEqual({ postId: 7 });
    expect(fetchMock).toHaveBeenCalledTimes(1);

    unmount();
    expect(queryClient.getQueryData(["posts", 7])).toBeUndefined();
    expect(
      queryClient.getQueryState(["feed", { order: "LATEST" }])?.isInvalidated,
    ).toBe(true);
  });

  it("취소하면 아무것도 보내지 않고 대화상자를 닫는다", async () => {
    const user = userEvent.setup({ delay: null });
    const { fetchMock } = renderButton(new Response(null, { status: 204 }));

    await user.click(screen.getByRole("button", { name: "삭제" }));
    await user.click(screen.getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("US4-AC4 403 NOT_AUTHOR면 이동하지 않고 안내한다", async () => {
    const user = userEvent.setup({ delay: null });
    renderButton(
      new Response(
        JSON.stringify({
          success: false,
          data: null,
          error: { code: "NOT_AUTHOR", message: "작성자만 할 수 있습니다." },
        }),
        { status: 403 },
      ),
    );

    await user.click(screen.getByRole("button", { name: "삭제" }));
    await user.click(screen.getByRole("button", { name: "삭제하기" }));

    expect(
      await screen.findByText("내 글만 지울 수 있어요."),
    ).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });
});
