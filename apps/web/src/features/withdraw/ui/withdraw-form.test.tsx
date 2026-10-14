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

import { WithdrawForm } from "./withdraw-form";

function renderForm(onWithdrawn?: () => void) {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["me"], { id: 1 });
  render(
    <QueryClientProvider client={queryClient}>
      <WithdrawForm cancelHref="/my/edit" onWithdrawn={onWithdrawn} />
    </QueryClientProvider>,
  );
  return queryClient;
}

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function errorResponse(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      success: false,
      data: null,
      error: { code, message: "실패" },
    }),
    { status, headers: { "content-type": "application/json" } },
  );
}

const confirmBox = () =>
  screen.getByRole("checkbox", {
    name: "안내를 모두 읽었고, 되돌릴 수 없다는 것을 알고 있어요.",
  });
const withdrawButton = () => screen.getByRole("button", { name: "탈퇴하기" });

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
  refreshMock.mockReset();
});

describe("WithdrawForm", () => {
  it("US1-AC2 사라지는 것과 남는 것, 되돌릴 수 없다는 것을 알린다", () => {
    renderForm();

    const removed = screen.getByRole("region", { name: "탈퇴하면 사라져요" });
    expect(removed).toHaveTextContent("내가 쓴 글과 댓글");
    expect(removed).toHaveTextContent("받은 알림과 주간 리포트");
    expect(removed).toHaveTextContent("몬스터 도감");
    const kept = screen.getByRole("region", { name: "탈퇴해도 남아요" });
    expect(kept).toHaveTextContent("다른 회원의 글에 남긴 공감 수");
    expect(kept).toHaveTextContent("몬스터의 HP와 레이드 기록");
    expect(screen.getByText("되돌릴 수 없어요.")).toBeInTheDocument();
    expect(
      screen.getByText(/예전 글과 기록은 돌아오지 않아요/),
    ).toBeInTheDocument();
  });

  it("US1-AC3 확인 칸을 채우기 전에는 탈퇴가 눌리지 않고 까닭이 읽힌다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    expect(confirmBox()).not.toBeChecked();
    // FR-021: 초점을 받을 수 있어야 왜 눌리지 않는지 들을 수 있다
    expect(withdrawButton()).toHaveAttribute("aria-disabled", "true");
    expect(withdrawButton()).toHaveAccessibleDescription(
      "확인 칸을 채우면 탈퇴할 수 있어요.",
    );
    await userEvent.click(withdrawButton());

    expect(fetchMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("US1-AC4 확인 칸을 채우고 탈퇴하면 캐시를 비우고 로그인 화면으로 간다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    const onWithdrawn = vi.fn();
    const queryClient = renderForm(onWithdrawn);

    await userEvent.click(confirmBox());
    expect(withdrawButton()).toHaveAttribute("aria-disabled", "false");
    await userEvent.click(withdrawButton());

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/login?withdrawn=1"),
    );
    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(onWithdrawn).toHaveBeenCalledTimes(1);
    expect(queryClient.getQueryData(["me"])).toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/withdraw");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ confirmed: true }));
  });

  it("FR-021 키보드만으로 확인하고 탈퇴한다", async () => {
    stubFetch(new Response(null, { status: 204 }));
    renderForm();

    await userEvent.tab();
    expect(confirmBox()).toHaveFocus();
    await userEvent.keyboard(" ");
    await userEvent.tab();
    expect(screen.getByRole("link", { name: "취소" })).toHaveFocus();
    await userEvent.tab();
    expect(withdrawButton()).toHaveFocus();
    await userEvent.keyboard("{Enter}");

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/login?withdrawn=1"),
    );
  });

  it("US1-AC7 취소는 왔던 곳으로 가는 링크이고 아무것도 보내지 않는다", () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    expect(screen.getByRole("link", { name: "취소" })).toHaveAttribute(
      "href",
      "/my/edit",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US1-AC8 실패하면 안내를 보이고 화면에 남아 다시 해 볼 수 있다", async () => {
    stubFetch(errorResponse(500, "INTERNAL_ERROR"));
    const queryClient = renderForm();
    await userEvent.click(confirmBox());

    await userEvent.click(withdrawButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
    expect(pushMock).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(["me"])).toEqual({ id: 1 });
    expect(confirmBox()).toBeChecked();

    stubFetch(new Response(null, { status: 204 }));
    await userEvent.click(withdrawButton());
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/login?withdrawn=1"),
    );
  });

  it("US1-AC11 운영자에게는 지정을 먼저 풀라고 알린다", async () => {
    stubFetch(errorResponse(409, "OPERATOR_CANNOT_WITHDRAW"));
    renderForm();
    await userEvent.click(confirmBox());

    await userEvent.click(withdrawButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "운영자 지정을 먼저 푼 뒤에 탈퇴할 수 있어요.",
    );
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("세션이 이미 끝나 401이면 로그인 화면으로 보낸다", async () => {
    stubFetch(errorResponse(401, "SESSION_EXPIRED"));
    renderForm();
    await userEvent.click(confirmBox());

    await userEvent.click(withdrawButton());

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/login"));
  });

  it("FR-020 취소와 탈퇴가 같은 줄에 같은 크기로 놓인다", () => {
    renderForm();

    const cancel = screen.getByRole("link", { name: "취소" });
    expect(cancel.parentElement).toBe(withdrawButton().parentElement);
    expect(cancel.parentElement).toHaveClass("grid-cols-2");
    expect(cancel).toHaveClass("min-h-12");
    expect(withdrawButton()).toHaveClass("min-h-12");
  });
});
