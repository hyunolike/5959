import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { TOAST_DURATION_MS, Toaster, useToasts } from "./toast";

function Harness({ onOpen }: { onOpen?: (message: string) => void }) {
  const { toasts, show, dismiss } = useToasts();
  return (
    <>
      <button
        type="button"
        onClick={() =>
          show({
            message: `알림 ${toasts.length + 1}`,
            onClick: onOpen
              ? () => onOpen(`알림 ${toasts.length + 1}`)
              : undefined,
          })
        }
      >
        띄우기
      </button>
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </>
  );
}

function showToast() {
  fireEvent.click(screen.getByRole("button", { name: "띄우기" }));
}

describe("Toast", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("토스트는 화면 오른쪽 아래 영역에 뜬다", () => {
    render(<Harness />);
    showToast();

    const region = screen.getByRole("region", { name: "새 알림" });
    expect(region).toHaveClass("fixed", "bottom-4", "right-4");
    expect(region).toHaveTextContent("알림 1");
  });

  it("4초 뒤에 저절로 사라진다", () => {
    render(<Harness />);
    showToast();

    act(() => {
      vi.advanceTimersByTime(TOAST_DURATION_MS - 1);
    });
    expect(screen.getByText("알림 1")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText("알림 1")).not.toBeInTheDocument();
    expect(TOAST_DURATION_MS).toBe(4000);
  });

  it("여러 개가 쌓이면 최신이 위에 오고 각각 제 시간에 사라진다", () => {
    render(<Harness />);
    showToast();
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    showToast();
    showToast();

    const items = screen.getAllByRole("button", { name: /^알림 \d$/ });
    expect(items.map((item) => item.textContent)).toEqual([
      "알림 3",
      "알림 2",
      "알림 1",
    ]);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.queryByText("알림 1")).not.toBeInTheDocument();
    expect(screen.getByText("알림 2")).toBeInTheDocument();
    expect(screen.getByText("알림 3")).toBeInTheDocument();
  });

  it("누르면 콜백을 실행하고 그 토스트를 닫는다", () => {
    const onOpen = vi.fn();
    render(<Harness onOpen={onOpen} />);
    showToast();

    fireEvent.click(screen.getByRole("button", { name: "알림 1" }));

    expect(onOpen).toHaveBeenCalledWith("알림 1");
    expect(screen.queryByText("알림 1")).not.toBeInTheDocument();
  });

  it("토스트가 없어도 읽어 주기 영역은 비어 있는 채로 남아 새 토스트를 알린다", () => {
    render(<Toaster toasts={[]} onDismiss={() => {}} />);

    const region = screen.getByRole("region", { name: "새 알림" });
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toBeEmptyDOMElement();
  });
});
