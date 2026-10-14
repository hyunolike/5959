import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RequestResetForm } from "./request-reset-form";

function renderForm() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RequestResetForm />
    </QueryClientProvider>,
  );
}

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const errorResponse = (
  status: number,
  code: string,
  retryAfterSeconds?: number,
) =>
  new Response(
    JSON.stringify({
      success: false,
      data: null,
      error: { code, message: "실패", retryAfterSeconds },
    }),
    { status, headers: { "content-type": "application/json" } },
  );

async function submit(email: string) {
  await userEvent.type(screen.getByLabelText("가입한 이메일"), email);
  await userEvent.click(
    screen.getByRole("button", { name: "재설정 메일 받기" }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("RequestResetForm", () => {
  it("US1-AC5 보내면 가입 여부를 말하지 않는 안내가 폼 자리에 온다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    await submit("me@example.com");

    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent(
      "me@example.com이 가입한 이메일이라면 재설정 메일이 가요.",
    );
    expect(status).toHaveTextContent("30분 동안, 한 번만");
    expect(status).toHaveTextContent("스팸함");
    expect(status).toHaveTextContent("카카오나 구글로 가입했다면");
    expect(status).not.toHaveTextContent("보냈");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/password-reset");
    expect(init.body).toBe(JSON.stringify({ email: "me@example.com" }));
  });

  it("US1-AC6 이메일의 모양이 아니면 보내지 않는다", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));
    renderForm();

    await submit("not-an-email");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "올바른 이메일 형식이 아닙니다.",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US3-AC2 같은 곳에서 너무 자주 요청하면 남은 시간을 분으로 알린다", async () => {
    stubFetch(errorResponse(429, "PASSWORD_RESET_THROTTLED", 3000));
    renderForm();

    await submit("me@example.com");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "50분 뒤에 다시 시도해 주세요.",
    );
  });

  it("US4-AC2 메일 발송이 꺼져 있으면 지금은 쓸 수 없다고 알린다", async () => {
    stubFetch(errorResponse(503, "PASSWORD_RESET_UNAVAILABLE"));
    renderForm();

    await submit("me@example.com");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "지금은 비밀번호 재설정 메일을 보낼 수 없어요.",
    );
  });
});
