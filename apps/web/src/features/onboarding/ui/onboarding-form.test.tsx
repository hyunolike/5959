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

function renderForm(next?: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <OnboardingForm next={next} />
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
  refreshMock.mockReset();
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
    // 온보딩 쿠키가 생긴 뒤 루트 레이아웃을 새로 받아 알림 종을 그린다.
    expect(refreshMock).toHaveBeenCalledTimes(1);
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

describe("OnboardingForm 완료 뒤 이동", () => {
  it.each([
    ["/my", "/my"],
    [undefined, "/home"],
    ["//evil.example", "/home"],
  ])("next가 %j면 완료 뒤 %s로 간다", async (next, expected) => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = input.toString();
        if (url.includes("nickname-availability")) {
          return Promise.resolve(nicknameAvailability("free1"));
        }
        return Promise.resolve(
          jsonResponse({
            success: true,
            data: {
              member: {
                id: 1,
                authMethod: "EMAIL",
                email: "a@example.com",
                nickname: "free1",
                jobRole: "DEVELOPMENT",
                careerYear: "YEAR_1",
                onboarded: true,
              },
            },
            error: null,
          }),
        );
      }),
    );

    renderForm(next);
    await fillJobAndCareer(user);
    await user.type(screen.getByLabelText("닉네임"), "free1");
    await user.click(screen.getByRole("button", { name: "완료" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith(expected));
  });

  it("DS-US2-AC2 직군과 경력은 56px 공용 선택 상자이고 완료 버튼도 56px이다", () => {
    vi.stubGlobal("fetch", vi.fn());
    renderForm();
    for (const name of ["직군", "경력"]) {
      expect(screen.getByLabelText(name)).toHaveClass(
        "h-14",
        "border-line-strong",
      );
    }
    expect(screen.getByRole("button", { name: "완료" })).toHaveClass(
      "min-h-14",
    );
  });

  it("DS-US2-AC1 쓸 수 있는 닉네임 안내는 초록색 없이 굵은 글씨다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async (input: RequestInfo | URL) =>
          nicknameAvailability(
            new URL(String(input), "http://localhost").searchParams.get(
              "nickname",
            ) ?? "",
          ),
        ),
    );
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText("닉네임"), "오구");
    const hint = await screen.findByText("사용할 수 있는 닉네임입니다.");
    expect(hint).toHaveClass("text-fg", "font-medium");
    expect(hint.className).not.toContain("leaf");
  });

  it("DS-US2-AC1 고르지 않고 내면 선택 상자가 잘못됨으로 표시되고 오류 문구와 이어진다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => nicknameAvailability("오구")),
    );
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole("button", { name: "완료" }));
    const jobRole = screen.getByLabelText("직군");
    await waitFor(() =>
      expect(jobRole).toHaveAttribute("aria-invalid", "true"),
    );
    expect(jobRole).toHaveAccessibleDescription("직군을 선택하세요.");
    expect(document.getElementById("jobRole-error")).toHaveClass(
      "text-label",
      "text-danger",
    );
  });

  it("DS-US2-AC1 쓸 수 없는 닉네임 안내는 오류 색으로 쓸 수 있는 안내보다 눈에 띈다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async (input: RequestInfo | URL) =>
          nicknameAvailability(
            new URL(String(input), "http://localhost").searchParams.get(
              "nickname",
            ) ?? "",
          ),
        ),
    );
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText("닉네임"), "dupName");
    expect(await screen.findByText("이미 사용 중인 닉네임입니다.")).toHaveClass(
      "text-danger",
    );
  });

  it("DS-US2-AC1 닉네임 규칙은 틀리기 전부터 보이고 입력과 이어져 있다", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(async () => nicknameAvailability("오구")),
    );
    renderForm();
    const nickname = screen.getByLabelText("닉네임");
    expect(screen.getByText("한글, 영문, 숫자 1~10자")).toBeVisible();
    // 빈 알림 자리도 함께 이어져 있어 끝에 공백이 붙는다.
    expect(nickname).toHaveAccessibleDescription(
      /^한글, 영문, 숫자 1~10자\s*$/,
    );
    expect(nickname).toHaveAttribute("aria-required", "true");
  });

  it("DS-US2-AC1 닉네임 확인 결과는 처음부터 있던 알림 자리에 채워져 화면 낭독기가 읽는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation(async (input: RequestInfo | URL) =>
          nicknameAvailability(
            new URL(String(input), "http://localhost").searchParams.get(
              "nickname",
            ) ?? "",
          ),
        ),
    );
    const user = userEvent.setup();
    renderForm();
    const status = screen.getByRole("status");
    expect(status).toBeEmptyDOMElement();

    await user.type(screen.getByLabelText("닉네임"), "오구");

    expect(await screen.findByText("사용할 수 있는 닉네임입니다.")).toBe(
      status,
    );
    expect(screen.getByLabelText("닉네임")).toHaveAccessibleDescription(
      "한글, 영문, 숫자 1~10자 사용할 수 있는 닉네임입니다.",
    );
  });
});

function stubOnboardingSuccess() {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = input.toString();
    if (url.includes("nickname-availability")) {
      return Promise.resolve(nicknameAvailability("free"));
    }
    return Promise.resolve(
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
  });
  vi.stubGlobal("fetch", fetchMock);
}

async function completeOnboarding(next?: string) {
  const user = userEvent.setup({ delay: null });
  stubOnboardingSuccess();
  renderForm(next);
  await user.type(screen.getByLabelText("닉네임"), "free1");
  await fillJobAndCareer(user);
  await user.click(screen.getByRole("button", { name: "완료" }));
}

describe("OnboardingForm 첫 글로 이어 주기(016)", () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it("US2-AC1 직군과 경력을 묻는 까닭과 이름과 회사는 묻지 않는다는 문장이 두 칸의 설명으로 읽힌다", () => {
    renderForm();

    for (const label of ["직군", "경력"]) {
      expect(screen.getByLabelText(label)).toHaveAccessibleDescription(
        expect.stringContaining("이름과 회사는 묻지 않아요"),
      );
    }
    expect(
      screen.getByText(/비슷한 일을 하는 사람의 고민을 찾고/),
    ).toBeInTheDocument();
  });

  it("US2-AC2 가려던 곳 없이 마치면 홈에서 환영 안내를 보이도록 표시를 남긴다", async () => {
    await completeOnboarding();

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/home"));
    expect(sessionStorage.getItem("ogu.welcome.pending")).toBe("1");
  });

  it("US2-AC4 가려던 곳이 있으면 표시 없이 그곳으로 간다", async () => {
    await completeOnboarding("/post/3");

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/3"));
    expect(sessionStorage.getItem("ogu.welcome.pending")).toBeNull();
  });

  it("저장소가 막혀도 마치면 홈으로 간다", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    await completeOnboarding();

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/home"));
  });
});
