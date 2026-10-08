import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { MemberProfile } from "@/entities/member";
import { QUERY_KEYS } from "@/shared/config";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

import { ProfileForm } from "./profile-form";

const MEMBER: MemberProfile = {
  id: 1,
  authMethod: "EMAIL",
  email: "a@a.com",
  nickname: "Ogu",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
  onboarded: true,
};

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const ok = (data: unknown) =>
  jsonResponse({ success: true, data, error: null });

const fail = (status: number, code: string, message: string) =>
  jsonResponse(
    { success: false, data: null, error: { code, message } },
    status,
  );

/** 닉네임 확인은 "dup"으로 시작하면 사용 중이라고 답한다. PATCH는 [patch]가 답한다. */
function stubApi(patch: () => Response = () => ok(MEMBER)) {
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("nickname-availability")) {
        const nickname = new URL(url, "http://localhost").searchParams.get(
          "nickname",
        )!;
        const taken = nickname.toLowerCase().startsWith("dup");
        return ok({ available: !taken, reason: taken ? "TAKEN" : null });
      }
      if (url === "/api/members/me" && init?.method === "PATCH") {
        return patch();
      }
      throw new Error(`예상하지 못한 요청: ${url}`);
    },
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const patchCalls = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === "PATCH");

const availabilityCalls = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([url]) =>
    String(url).includes("nickname-availability"),
  );

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ProfileForm member={MEMBER} />
    </QueryClientProvider>,
  );
  return queryClient;
}

const saveButton = () => screen.getByRole("button", { name: "저장" });

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("ProfileForm", () => {
  it("지금 프로필로 채워져 있고, 하나도 안 바꾸면 저장 버튼이 막혀 있다", () => {
    stubApi();

    renderForm();

    expect(screen.getByLabelText("닉네임")).toHaveValue("Ogu");
    expect(screen.getByLabelText("직군")).toHaveValue("DEVELOPMENT");
    expect(screen.getByLabelText("경력")).toHaveValue("YEAR_3");
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole("link", { name: "취소" })).toHaveAttribute(
      "href",
      "/my",
    );
  });

  it("바꿨다가 원래 값으로 되돌리면 저장 버튼이 다시 막힌다", async () => {
    const user = userEvent.setup();
    stubApi();
    renderForm();

    await user.selectOptions(screen.getByLabelText("직군"), "DESIGN");
    expect(saveButton()).toBeEnabled();

    await user.selectOptions(screen.getByLabelText("직군"), "DEVELOPMENT");
    expect(saveButton()).toBeDisabled();
  });

  it("US5-AC1 바뀐 필드만 보내고, 저장되면 내 정보를 바꾸고 마이페이지로 간다", async () => {
    const user = userEvent.setup();
    const saved = { ...MEMBER, careerYear: "YEAR_4" as const };
    const fetchMock = stubApi(() => ok(saved));
    const queryClient = renderForm();

    await user.selectOptions(screen.getByLabelText("경력"), "YEAR_4");
    await user.click(saveButton());

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/my"));
    const calls = patchCalls(fetchMock);
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({
      careerYear: "YEAR_4",
    });
    expect(queryClient.getQueryData(QUERY_KEYS.me)).toEqual(saved);
    // 닉네임을 바꾸지 않았으므로 중복 확인을 하지 않는다.
    expect(availabilityCalls(fetchMock)).toHaveLength(0);
  });

  it("US5-AC2 다른 회원이 쓰는 닉네임이면 미리 안내하고 저장하지 않는다", async () => {
    const user = userEvent.setup();
    const fetchMock = stubApi();
    renderForm();

    await user.clear(screen.getByLabelText("닉네임"));
    await user.type(screen.getByLabelText("닉네임"), "dupName");

    expect(
      await screen.findByText("이미 사용 중인 닉네임입니다."),
    ).toBeInTheDocument();

    await user.click(saveButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이미 사용 중인 닉네임입니다.",
    );
    expect(patchCalls(fetchMock)).toHaveLength(0);
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("US5-AC2 허용되지 않는 문자는 온보딩과 같은 안내를 보이고 저장하지 않는다", async () => {
    const user = userEvent.setup();
    const fetchMock = stubApi();
    renderForm();

    await user.clear(screen.getByLabelText("닉네임"));
    await user.type(screen.getByLabelText("닉네임"), "오구!");
    await user.click(saveButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "한글, 영문, 숫자로 1~10자를 입력하세요.",
    );
    expect(patchCalls(fetchMock)).toHaveLength(0);
  });

  it("US5-AC2 저장 때 서버가 409 NICKNAME_TAKEN을 주면 닉네임 필드에 보인다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = stubApi(() =>
      fail(409, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다."),
    );
    renderForm();

    // 미리 확인은 통과했지만 저장하는 사이에 다른 회원이 먼저 가져간 경우
    await user.clear(screen.getByLabelText("닉네임"));
    await user.type(screen.getByLabelText("닉네임"), "free1");
    await user.click(saveButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "이미 사용 중인 닉네임입니다.",
    );
    expect(patchCalls(fetchMock)).toHaveLength(1);
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("내 닉네임의 대소문자만 바꾸면 중복 확인 없이 저장한다", async () => {
    const user = userEvent.setup();
    const fetchMock = stubApi(() => ok({ ...MEMBER, nickname: "OGU" }));
    renderForm();

    await user.clear(screen.getByLabelText("닉네임"));
    await user.type(screen.getByLabelText("닉네임"), "OGU");
    // 디바운스(400ms)가 지나도 확인 요청이 나가지 않는다.
    await new Promise((resolve) => setTimeout(resolve, 600));
    await user.click(saveButton());

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/my"));
    expect(availabilityCalls(fetchMock)).toHaveLength(0);
    expect(JSON.parse(String(patchCalls(fetchMock)[0][1]?.body))).toEqual({
      nickname: "OGU",
    });
  });

  it("알 수 없는 오류면 폼에 안내를 보인다", async () => {
    const user = userEvent.setup();
    stubApi(() => fail(500, "INTERNAL_ERROR", "서버 오류"));
    renderForm();

    await user.selectOptions(screen.getByLabelText("직군"), "HR");
    await user.click(saveButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "프로필을 저장하지 못했습니다.",
    );
    expect(pushMock).not.toHaveBeenCalled();
  });
});
