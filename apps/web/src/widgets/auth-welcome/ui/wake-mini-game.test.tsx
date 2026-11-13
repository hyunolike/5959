import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiReadinessProvider, useApiReadiness } from "@/shared/api";
import { InteractionProvider } from "@/shared/ui";
import { ApiWakeNotice } from "./api-wake-notice";

let up = false;
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}
async function renderWaiting(giveUpAfterMs = 60_000) {
  render(
    <InteractionProvider>
      <ApiReadinessProvider pollIntervalMs={100} giveUpAfterMs={giveUpAfterMs}>
        <ApiWakeNotice />
        <input aria-label="로그인 이메일" defaultValue="ogu@example.com" />
      </ApiReadinessProvider>
    </InteractionProvider>,
  );
  await advance(1);
}
async function openGame() {
  fireEvent.click(screen.getByRole("button", { name: "오구와 놀기" }));
  await advance(1);
  return screen.getByRole("region", { name: "오구의 마음 산책" });
}

beforeEach(() => {
  vi.useFakeTimers();
  up = false;
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json({ status: up ? "UP" : "DOWN" })),
  );
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) =>
    setTimeout(() => callback(performance.now()), 16),
  );
  vi.stubGlobal("cancelAnimationFrame", (id: number) => clearTimeout(id));
  vi.stubGlobal(
    "Image",
    class {
      width = 1536;
      height = 1024;
      onload?: () => void;
      set src(_value: string) {
        queueMicrotask(() => this.onload?.());
      }
    },
  );
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
    clearRect: vi.fn(),
    fillRect: vi.fn(),
    setTransform: vi.fn(),
    drawImage: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    setLineDash: vi.fn(),
    arc: vi.fn(),
    fill: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  sessionStorage.clear();
});

describe("서버 대기 중 마음 산책", () => {
  it("선택해서 열고 무대를 눌러 시작하며 거리와 점프를 표시한다", async () => {
    await renderWaiting();
    expect(
      screen.queryByRole("region", { name: "오구의 마음 산책" }),
    ).not.toBeInTheDocument();
    const game = await openGame();
    const stage = within(game).getByRole("button", {
      name: "톡 눌러 함께 산책하기",
    });
    fireEvent.click(stage);
    await advance(1_000);
    expect(
      within(game).getByRole("button", { name: "점프하기" }),
    ).toBeInTheDocument();
    expect(within(game).getByText(/^함께 [1-9]\d*m$/)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "로그인 이메일" })).toHaveValue(
      "ogu@example.com",
    );
  });

  it("충돌하면 쉬는 화면에서 재시작하고 점수를 초기화한다", async () => {
    await renderWaiting();
    const game = await openGame();
    fireEvent.click(
      within(game).getByRole("button", { name: "톡 눌러 함께 산책하기" }),
    );
    await advance(11_000);
    expect(within(game).getByText("잠깐 쉬어 가요")).toBeInTheDocument();
    fireEvent.click(
      within(game).getByRole("button", { name: "다시 산책하기" }),
    );
    expect(within(game).getByText("함께 0m")).toBeInTheDocument();
    expect(
      within(game).getByRole("button", { name: "점프하기" }),
    ).toBeInTheDocument();
  });

  it("초점이 입력창으로 옮겨지면 멈추고 입력창의 키를 가로채지 않는다", async () => {
    await renderWaiting();
    const game = await openGame();
    fireEvent.click(
      within(game).getByRole("button", { name: "톡 눌러 함께 산책하기" }),
    );
    await advance(1_000);
    act(() => screen.getByRole("textbox").focus());
    const distance = within(game).getByText(/^함께 \d+m$/).textContent;
    fireEvent.keyDown(screen.getByRole("textbox"), { key: " " });
    await advance(10_000);
    expect(within(game).getByText(distance!)).toBeInTheDocument();
    expect(
      within(game).getByRole("button", { name: "이어서 산책하기" }),
    ).toBeInTheDocument();
  });

  it("길게 누른 반복 키로 새 판을 시작하지 않는다", async () => {
    await renderWaiting();
    const game = await openGame();
    const stage = within(game).getByRole("button", {
      name: "톡 눌러 함께 산책하기",
    });
    fireEvent.keyDown(stage, { key: " ", repeat: true });
    expect(stage).toHaveAccessibleName("톡 눌러 함께 산책하기");
    fireEvent.keyDown(stage, { key: "ArrowUp" });
    expect(stage).toHaveAccessibleName("점프하기");
  });

  it("탭을 숨기거나 프레임이 크게 늦어지면 거리를 건너뛰지 않고 멈춘다", async () => {
    let skew = 0;
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) =>
      setTimeout(() => callback(performance.now() + skew), 16),
    );
    await renderWaiting();
    const game = await openGame();
    fireEvent.click(
      within(game).getByRole("button", { name: "톡 눌러 함께 산책하기" }),
    );
    await advance(500);
    skew = 10_000;
    await advance(20);
    expect(
      within(game).getByRole("button", { name: "이어서 산책하기" }),
    ).toBeInTheDocument();
    expect(within(game).getByText(/^함께 [0-5]m$/)).toBeInTheDocument();
    skew = 0;
    fireEvent.click(
      within(game).getByRole("button", { name: "이어서 산책하기" }),
    );
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    fireEvent(document, new Event("visibilitychange"));
    await advance(10_000);
    expect(
      within(game).getByRole("button", { name: "이어서 산책하기" }),
    ).toBeInTheDocument();
  });

  it("Canvas를 사용할 수 없어도 안내를 닫거나 서버 준비 완료로 복귀할 수 있다", async () => {
    vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null);
    await renderWaiting();
    await openGame();
    expect(
      screen.getByText("산책 화면을 불러오지 못했어요"),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "안내로 돌아가기" }),
    ).toBeInTheDocument();
    up = true;
    await advance(100);
    expect(screen.getByText("준비됐어요")).toBeInTheDocument();
    await advance(1_200);
    expect(
      screen.queryByRole("region", { name: "오구의 마음 산책" }),
    ).not.toBeInTheDocument();
  });

  it("서버가 깨어나면 조작을 막고 완료 안내 후 자동으로 닫는다", async () => {
    await renderWaiting();
    const game = await openGame();
    fireEvent.click(
      within(game).getByRole("button", { name: "톡 눌러 함께 산책하기" }),
    );
    up = true;
    await advance(100);
    expect(screen.getByText("준비됐어요")).toBeInTheDocument();
    expect(
      within(game).getByRole("button", { name: "산책 완료" }),
    ).toHaveAttribute("aria-disabled", "true");
    await advance(1_200);
    expect(
      screen.queryByRole("region", { name: "오구의 마음 산책" }),
    ).not.toBeInTheDocument();
  });

  it("저장을 사용할 수 없어도 재시작하고 닫았다 열어도 방문 기록을 유지한다", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    await renderWaiting();
    const game = await openGame();
    fireEvent.click(
      within(game).getByRole("button", { name: "톡 눌러 함께 산책하기" }),
    );
    await advance(11_000);
    expect(within(game).getByText("잠깐 쉬어 가요")).toBeInTheDocument();
    const record = within(game).getByText(/^이번 방문 최고 \d+m$/).textContent;
    fireEvent.click(
      within(game).getByRole("button", { name: "다시 산책하기" }),
    );
    expect(
      within(game).getByRole("button", { name: "점프하기" }),
    ).toBeInTheDocument();
    fireEvent.click(within(game).getByRole("button", { name: "놀이 닫기" }));
    await advance(20);
    const reopened = await openGame();
    expect(within(reopened).getByText(record!)).toBeInTheDocument();
  });

  it("연결 실패 시 놀이를 멈추고 재연결 안내를 가리지 않는다", async () => {
    await renderWaiting(1_000);
    const game = await openGame();
    await advance(1_100);
    expect(
      screen.getByText("서버가 아직 일어나지 않았어요"),
    ).toBeInTheDocument();
    expect(
      within(game).getByText("연결을 다시 확인해 주세요"),
    ).toBeInTheDocument();
    expect(
      within(game).getByRole("button", { name: "산책 멈춤" }),
    ).toHaveAttribute("aria-disabled", "true");
  });

  it("인증 재시도로 실패했던 판을 자동으로 재개하지 않는다", async () => {
    function Retry() {
      const { waitUntilReady } = useApiReadiness();
      return <button onClick={() => void waitUntilReady()}>인증 재시도</button>;
    }
    render(
      <InteractionProvider>
        <ApiReadinessProvider pollIntervalMs={100} giveUpAfterMs={1_000}>
          <ApiWakeNotice />
          <Retry />
        </ApiReadinessProvider>
      </InteractionProvider>,
    );
    await advance(1);
    await openGame();
    await advance(1_100);
    fireEvent.click(screen.getByRole("button", { name: "인증 재시도" }));
    await advance(1);
    expect(
      screen.queryByRole("region", { name: "오구의 마음 산책" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "오구와 놀기" }),
    ).toBeInTheDocument();
  });

  it("닫으면 시작 버튼에 초점이 돌아온다", async () => {
    await renderWaiting();
    await openGame();
    fireEvent.click(screen.getByRole("button", { name: "놀이 닫기" }));
    await advance(20);
    expect(screen.getByRole("button", { name: "오구와 놀기" })).toHaveFocus();
  });
});
