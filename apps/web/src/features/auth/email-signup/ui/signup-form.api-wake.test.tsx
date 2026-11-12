import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

import { ApiReadinessProvider } from "@/shared/api";

import { SignupForm } from "./signup-form";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

/** `/api/health/live`는 `isUp()`에 따라 답하고, 가입은 성공으로 답한다. */
function stubFetch(isUp: () => boolean) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/api/health/live") {
      return Promise.resolve(json({ status: isUp() ? "UP" : "DOWN" }));
    }
    return Promise.resolve(
      json({
        success: true,
        data: { member: { id: 1, onboarded: true } },
        error: null,
      }),
    );
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const signupCalls = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.filter(([url]) => String(url) === "/api/auth/signup");

function renderForm(giveUpAfterMs = 60_000) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ApiReadinessProvider pollIntervalMs={10} giveUpAfterMs={giveUpAfterMs}>
        <SignupForm />
      </ApiReadinessProvider>
    </QueryClientProvider>,
  );
}

async function fillAndSubmit() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("이메일"), "ogu@example.com");
  await user.type(screen.getByLabelText("비밀번호"), "Secret-pass1!");
  await user.click(screen.getByRole("button", { name: "가입하기" }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("SignupForm 서버 깨우기", () => {
  it("US2-AC8 깨우는 중에 제출하면 기다린다는 문구를 보이고, 깨어난 뒤에 한 번만 보낸다", async () => {
    let up = false;
    const fetchMock = stubFetch(() => up);
    renderForm();

    await fillAndSubmit();
    expect(
      await screen.findByRole("button", { name: "서버가 깨면 가입할게요" }),
    ).toBeDisabled();
    expect(signupCalls(fetchMock)).toHaveLength(0);

    up = true;
    await waitFor(() => expect(signupCalls(fetchMock)).toHaveLength(1));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("US2-AC8 끝내 깨어나지 않으면 보내지 않고 잠시 뒤 다시 하라고 안내한다", async () => {
    const fetchMock = stubFetch(() => false);
    renderForm(30);

    await fillAndSubmit();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버가 아직 깨어나지 않았어요",
    );
    expect(signupCalls(fetchMock)).toHaveLength(0);
  });

  it("US2-AC8 깨어 있으면 문구를 바꾸지 않고 바로 보낸다", async () => {
    const fetchMock = stubFetch(() => true);
    renderForm();
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/health/live",
        expect.anything(),
      ),
    );

    await fillAndSubmit();
    await waitFor(() => expect(signupCalls(fetchMock)).toHaveLength(1));
    expect(
      screen.queryByRole("button", { name: "서버가 깨면 가입할게요" }),
    ).not.toBeInTheDocument();
  });
});
