import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ResetPasswordForm } from "./reset-password-form";

const navigateMock = vi.fn();

function renderForm(token = "the-token") {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ResetPasswordForm token={token} navigate={navigateMock} />
    </QueryClientProvider>,
  );
}

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const errorResponse = (status: number, code: string, message = "실패") =>
  new Response(
    JSON.stringify({ success: false, data: null, error: { code, message } }),
    {
      status,
      headers: { "content-type": "application/json" },
    },
  );

async function submit(password: string, confirm = password) {
  await userEvent.type(screen.getByLabelText("새 비밀번호"), password);
  await userEvent.type(screen.getByLabelText("새 비밀번호 확인"), confirm);
  await userEvent.click(
    screen.getByRole("button", { name: "비밀번호 바꾸기" }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  navigateMock.mockReset();
});

describe("ResetPasswordForm", () => {
  it("US2-AC1 새 비밀번호를 저장하면 로그인 화면으로 간다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    await submit("newpass123");

    await waitFor(() =>
      expect(navigateMock).toHaveBeenCalledWith("/login?reset=1"),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/password-reset/confirm");
    expect(init.body).toBe(
      JSON.stringify({ token: "the-token", password: "newpass123" }),
    );
  });

  it("US2-AC6 두 칸이 다르거나 규칙에 맞지 않으면 보내지 않는다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    await submit("newpass123", "newpass124");
    expect(
      await screen.findByText("두 칸의 비밀번호가 서로 달라요."),
    ).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText("새 비밀번호"));
    await userEvent.clear(screen.getByLabelText("새 비밀번호 확인"));
    await submit("short");
    expect(
      await screen.findByText(
        "비밀번호는 영문과 숫자를 포함해 8~20자로 입력하세요.",
      ),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US2-AC3 US2-AC9 쓸 수 없는 링크면 다시 요청하는 길을 보인다", async () => {
    stubFetch(errorResponse(400, "PASSWORD_RESET_LINK_INVALID"));
    renderForm();

    await submit("newpass123");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "쓸 수 없는 링크예요.",
    );
    expect(
      screen.getByRole("link", { name: "재설정 메일 다시 받기" }),
    ).toHaveAttribute("href", "/forgot-password");
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it("서버가 규칙에 맞지 않는다고 하면 비밀번호 칸에 보인다", async () => {
    stubFetch(
      errorResponse(
        400,
        "INVALID_REQUEST",
        "비밀번호는 영문과 숫자를 포함해 8~20자로 입력해 주세요.",
      ),
    );
    renderForm();

    await submit("newpass123");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "8~20자로 입력해 주세요.",
    );
    expect(screen.getByLabelText("새 비밀번호")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });

  it("저장하면 모든 기기에서 로그아웃된다고 미리 알린다", () => {
    stubFetch(new Response(null, { status: 204 }));
    renderForm();

    expect(
      screen.getByText("저장하면 로그인해 있던 모든 기기에서 로그아웃돼요."),
    ).toBeInTheDocument();
  });
});
