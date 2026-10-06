import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const { pushMock, refreshMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  refreshMock: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, refresh: refreshMock }),
}));

import { LogoutButton } from "./logout-button";

function renderButton(onLoggedOut?: () => void) {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["me"], { id: 1 });
  render(
    <QueryClientProvider client={queryClient}>
      <LogoutButton onLoggedOut={onLoggedOut} />
    </QueryClientProvider>,
  );
  return queryClient;
}

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
  refreshMock.mockReset();
});

describe("LogoutButton", () => {
  it("로그아웃에 성공하면 onLoggedOut을 부르고 캐시를 비운 뒤 로그인 화면으로 간다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    const onLoggedOut = vi.fn();
    const queryClient = renderButton(onLoggedOut);

    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/login"));
    expect(onLoggedOut).toHaveBeenCalledTimes(1);
    expect(queryClient.getQueryData(["me"])).toBeUndefined();
  });

  it("로그아웃 뒤 레이아웃을 새로 그려 알림 종이 사라지게 한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    renderButton();

    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(refreshMock).toHaveBeenCalledTimes(1));
  });

  it("요청이 실패하면 onLoggedOut은 부르지 않지만 로그인 화면으로는 간다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    const onLoggedOut = vi.fn();
    renderButton(onLoggedOut);

    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/login"));
    expect(onLoggedOut).not.toHaveBeenCalled();
  });
});
