import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

import { OnboardingForm } from "./onboarding-form";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function nicknameAvailability(nickname: string) {
  const taken = nickname.toLowerCase().startsWith("dup");
  return jsonResponse({
    success: true,
    data: { available: !taken, reason: taken ? "TAKEN" : null },
    error: null,
  });
}

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <OnboardingForm />
    </QueryClientProvider>,
  );
}

async function fillJobAndCareer(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText("직군"), "DEVELOPMENT");
  await user.selectOptions(screen.getByLabelText("경력"), "YEAR_1");
}

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("OnboardingForm 닉네임 중복 확인의 오래된 결과 처리", () => {
  it("디바운스가 끝나기 전(오래된 결과만 있을 때) 빠르게 제출하면 막지 않고 서버 확인에 맡긴다", async () => {
    const user = userEvent.setup({ delay: null });
    const putMock = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: {
          member: {
            id: 1,
            authMethod: "EMAIL",
            email: "a@a.com",
            nickname: "free1",
            jobRole: "DEVELOPMENT",
            careerYear: "YEAR_1",
            onboarded: true,
          },
        },
        error: null,
      }),
    );
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes("nickname-availability")) {
        const nickname = new URL(url, "http://localhost").searchParams.get(
          "nickname",
        );
        return Promise.resolve(nicknameAvailability(nickname ?? ""));
      }
      return putMock();
    });
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await fillJobAndCareer(user);

    const nicknameInput = screen.getByLabelText("닉네임");
    await user.type(nicknameInput, "dup1");
    // "dup1"에 대한 결과(사용 중)가 자리 잡을 때까지 기다린다.
    await waitFor(() =>
      expect(
        screen.getByText("이미 사용 중인 닉네임입니다."),
      ).toBeInTheDocument(),
    );

    // 곧바로 사용 가능한 닉네임으로 고쳐 쓰고, 디바운스(400ms)가 끝나기 전에
    // 바로 제출한다 — "dup1"의 오래된 "사용 중" 결과로 막히면 안 된다.
    await user.clear(nicknameInput);
    await user.type(nicknameInput, "free1");
    await user.click(screen.getByRole("button", { name: "완료" }));

    await waitFor(() => expect(putMock).toHaveBeenCalled());
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/home"));
    expect(
      screen.queryByText("이미 사용 중인 닉네임입니다."),
    ).not.toBeInTheDocument();
  });

  it("디바운스가 끝나 확정된(settled) 사용 중 닉네임은 제출을 막는다", async () => {
    const user = userEvent.setup({ delay: null });
    const putMock = vi.fn();
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes("nickname-availability")) {
        const nickname = new URL(url, "http://localhost").searchParams.get(
          "nickname",
        );
        return Promise.resolve(nicknameAvailability(nickname ?? ""));
      }
      return putMock();
    });
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await fillJobAndCareer(user);

    const nicknameInput = screen.getByLabelText("닉네임");
    await user.type(nicknameInput, "dup2");
    await waitFor(() =>
      expect(
        screen.getByText("이미 사용 중인 닉네임입니다."),
      ).toBeInTheDocument(),
    );

    await user.click(screen.getByRole("button", { name: "완료" }));

    expect(
      await screen.findByText("이미 사용 중인 닉네임입니다."),
    ).toBeInTheDocument();
    expect(putMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });
});

describe("OnboardingForm 이미 온보딩한 회원(ALREADY_ONBOARDED)", () => {
  it("서버가 409 ALREADY_ONBOARDED를 돌려주면 /home으로 이동한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes("nickname-availability")) {
        return Promise.resolve(nicknameAvailability("free9"));
      }
      return Promise.resolve(
        jsonResponse(
          {
            success: false,
            data: null,
            error: {
              code: "ALREADY_ONBOARDED",
              message: "이미 온보딩을 마쳤습니다.",
            },
          },
          409,
        ),
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    renderForm();
    await fillJobAndCareer(user);
    await user.type(screen.getByLabelText("닉네임"), "free9");
    await user.click(screen.getByRole("button", { name: "완료" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/home"));
  });
});
