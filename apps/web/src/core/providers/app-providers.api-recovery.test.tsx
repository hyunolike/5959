import { useQuery } from "@tanstack/react-query";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AppProviders } from "./app-providers";
import { ConfirmDialog } from "@/shared/ui";

function MemberScreen() {
  const [draft, setDraft] = useState("");
  const { data } = useQuery({
    queryKey: ["recovery-profile"],
    queryFn: async () => (await fetch("/api/members/me")).json(),
  });
  return (
    <>
      <p>{data?.nickname ?? "프로필 로딩"}</p>
      <input
        aria-label="고민 초안"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
      />
    </>
  );
}

let up = false;
let profileCalls = 0;
let healthCalls = 0;
const flush = async () => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
};

beforeEach(() => {
  vi.useFakeTimers();
  up = false;
  profileCalls = 0;
  healthCalls = 0;
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string) => {
      if (path === "/api/health/live") {
        healthCalls += 1;
        return Response.json({ status: up ? "UP" : "DOWN" });
      }
      profileCalls += 1;
      return Response.json({ nickname: `복구된 회원 ${profileCalls}` });
    }),
  );
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("로그인된 사용자의 API 복구", () => {
  it("재연결 중에는 열린 확인창과 초점 가두기를 멈추고 복구 후 다시 보여 준다", async () => {
    up = true;
    render(
      <AppProviders authenticated>
        <ConfirmDialog
          open
          title="글을 지울까요?"
          confirmLabel="삭제"
          onConfirm={() => {}}
          onCancel={() => {}}
        />
      </AppProviders>,
    );
    await flush();
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16 * 60_000);
    });
    up = false;
    fireEvent.focus(window);
    await flush();
    expect(screen.getByText("서버를 깨우고 있어요")).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    up = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    await flush();
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
  it("잠든 동안 회원 조회를 보내지 않고, 깨어나면 새로고침 없이 불러온다", async () => {
    render(
      <AppProviders authenticated>
        <MemberScreen />
      </AppProviders>,
    );
    await flush();
    expect(screen.getByText("서버를 깨우고 있어요")).toBeInTheDocument();
    expect(profileCalls).toBe(0);
    expect(screen.queryByText("프로필 로딩")).not.toBeInTheDocument();
    up = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    await flush();
    expect(screen.getByText("복구된 회원 1")).toBeInTheDocument();
  });

  it("7분 뒤에도 깨어나지 않으면 로딩을 끝내고 재시도로 복구한다", async () => {
    render(
      <AppProviders authenticated>
        <MemberScreen />
      </AppProviders>,
    );
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(7 * 60_000);
    });
    expect(
      screen.getByText("서버가 아직 일어나지 않았어요"),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("깨우는 중")).not.toBeInTheDocument();
    expect(profileCalls).toBe(0);
    up = true;
    fireEvent.click(screen.getByRole("button", { name: "다시 연결하기" }));
    await flush();
    await flush();
    expect(screen.getByText("복구된 회원 1")).toBeInTheDocument();
  });

  it("오래된 탭으로 돌아오면 서버를 다시 확인하고 조회를 복구하며 초안은 보존한다", async () => {
    up = true;
    render(
      <AppProviders authenticated>
        <MemberScreen />
      </AppProviders>,
    );
    await flush();
    fireEvent.change(screen.getByRole("textbox", { name: "고민 초안" }), {
      target: { value: "쓰던 고민" },
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16 * 60_000);
    });
    up = false;
    fireEvent.focus(window);
    await flush();
    expect(screen.getByText("서버를 깨우고 있어요")).toBeInTheDocument();
    expect(
      screen.queryByRole("textbox", { name: "고민 초안" }),
    ).not.toBeInTheDocument();
    up = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    await flush();
    expect(screen.getByText("복구된 회원 2")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "고민 초안" })).toHaveValue(
      "쓰던 고민",
    );
  });

  it("로그인 전의 공개 화면에는 회원용 준비 확인을 붙이지 않는다", async () => {
    render(
      <AppProviders>
        <p>서비스 소개</p>
      </AppProviders>,
    );
    await flush();
    expect(screen.getByText("서비스 소개")).toBeInTheDocument();
    expect(healthCalls).toBe(0);
  });
});
