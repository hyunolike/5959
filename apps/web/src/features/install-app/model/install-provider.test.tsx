import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InstallAppProvider, useInstallApp } from "./install-provider";

function Consumer() {
  const install = useInstallApp();
  return (
    <>
      <button
        disabled={!install.canPrompt || install.pending}
        onClick={() => void install.requestInstall()}
      >
        설치
      </button>
      <button onClick={install.dismissPromotion}>숨기기</button>
      <output>{install.installed ? "설치됨" : "미확인"}</output>
      <span>{install.message}</span>
      <span>{install.promotionVisible ? "홍보 보임" : "홍보 숨김"}</span>
    </>
  );
}
const mount = () =>
  render(
    <InstallAppProvider>
      <Consumer />
    </InstallAppProvider>,
  );
function offer() {
  let choose!: (value: {
    outcome: "accepted" | "dismissed";
    platform: string;
  }) => void;
  const prompt = vi.fn(async () => {});
  const event = Object.assign(
    new Event("beforeinstallprompt", { cancelable: true }),
    {
      prompt,
      userChoice: new Promise<{
        outcome: "accepted" | "dismissed";
        platform: string;
      }>((resolve) => {
        choose = resolve;
      }),
    },
  );
  act(() => window.dispatchEvent(event));
  return { prompt, choose };
}

beforeEach(() => {
  localStorage.clear();
  vi.stubGlobal("isSecureContext", true);
  vi.stubGlobal(
    "matchMedia",
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});
afterEach(() => vi.unstubAllGlobals());

describe("InstallAppProvider", () => {
  it("실제 프롬프트 이벤트 전에는 설치 실행을 제공하지 않는다", () => {
    mount();
    expect(screen.getByRole("button", { name: /^설치$/ })).toBeDisabled();
  });
  it("한 이벤트는 사용자 동작 한 번만 소비하며 수락을 설치 완료로 오인하지 않는다", async () => {
    mount();
    const event = offer();
    const button = screen.getByRole("button", { name: /^설치$/ });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(event.prompt).toHaveBeenCalledTimes(1));
    expect(screen.getByText("홍보 보임")).toBeInTheDocument();
    await act(async () =>
      event.choose({ outcome: "accepted", platform: "web" }),
    );
    expect(screen.getByText("미확인")).toBeInTheDocument();
    expect(screen.getByText(/브라우저 안내/)).toBeInTheDocument();
    expect(button).toBeDisabled();
    act(() => window.dispatchEvent(new Event("appinstalled")));
    expect(screen.getByText("설치됨")).toBeInTheDocument();
  });
  it("설치 취소 후 완료를 표시하지 않고 홍보를 숨기며 재방문도 유지한다", async () => {
    const view = mount();
    const event = offer();
    fireEvent.click(screen.getByRole("button", { name: /^설치$/ }));
    await act(async () =>
      event.choose({ outcome: "dismissed", platform: "web" }),
    );
    expect(screen.getByText("미확인")).toBeInTheDocument();
    expect(screen.getByText("홍보 숨김")).toBeInTheDocument();
    view.unmount();
    mount();
    offer();
    expect(screen.getByText("홍보 숨김")).toBeInTheDocument();
  });
  it("스토리지가 차단돼도 명시적인 안내 숨기기는 동작한다", () => {
    const storage = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new DOMException("blocked", "SecurityError");
      });
    mount();
    offer();
    fireEvent.click(screen.getByRole("button", { name: "숨기기" }));
    expect(screen.getByText("홍보 숨김")).toBeInTheDocument();
    storage.mockRestore();
  });
  it("프롬프트 실패를 성공으로 표시하지 않고 상태를 회복한다", async () => {
    mount();
    const event = offer();
    event.prompt.mockRejectedValueOnce(new Error("prompt failed"));
    fireEvent.click(screen.getByRole("button", { name: /^설치$/ }));
    expect(
      await screen.findByText(/설치 안내를 열지 못했어요/),
    ).toBeInTheDocument();
    expect(screen.getByText("미확인")).toBeInTheDocument();
  });
  it("standalone에서는 프롬프트가 와도 실행하지 않는다", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn(() => ({
        matches: true,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    mount();
    const event = offer();
    expect(screen.getByText("설치됨")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^설치$/ })).toBeDisabled();
    expect(event.prompt).not.toHaveBeenCalled();
  });
});
