import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { MonsterCardData } from "@/entities/monster";

import { CardDialog } from "./card-dialog";

const CARD: MonsterCardData = {
  cardNo: 128,
  emotion: "ANXIETY",
  grade: "LEGENDARY",
  role: "TOGETHER",
  defeatedAt: "2026-10-12T03:00:00Z",
  myDamage: 4,
  helperCount: null,
  postId: 10,
};

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        카드 열기
      </button>
      {open ? <CardDialog card={CARD} onClose={() => setOpen(false)} /> : null}
    </>
  );
}

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: reduce && query.includes("prefers-reduced-motion: reduce"),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
}

/** 카드가 (100, 100)에서 200 x 280으로 그려진 것처럼 만든다. */
function tiltTarget(): HTMLElement {
  const target = document.querySelector<HTMLElement>(".ogu-card-tilt")!;
  target.getBoundingClientRect = () =>
    ({ left: 100, top: 100, width: 200, height: 280 }) as DOMRect;
  return target;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("CardDialog", () => {
  it("US3-AC1 카드를 크게 보이고 내용이 모두 글자로 있다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "카드 열기" }));

    const dialog = screen.getByRole("dialog", { name: "불안 카드 No.128" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    for (const text of [
      "전설",
      "함께 물리침",
      "2026년 10월 12일에 물리침",
      "내가 준 피해",
    ]) {
      expect(within(dialog).getByText(text)).toBeInTheDocument();
    }
  });

  it("US2-AC6 크게 본 카드에 그 글로 가는 링크가 있다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "카드 열기" }));

    expect(screen.getByRole("link", { name: "글 보기" })).toHaveAttribute(
      "href",
      "/post/10",
    );
  });

  it("US3-AC5 열리면 초점이 안으로 들어가고 닫으면 열었던 곳으로 돌아온다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole("button", { name: "카드 열기" });
    await user.click(opener);

    const close = screen.getByRole("button", { name: "닫기" });
    expect(close).toHaveFocus();
    // Tab은 대화상자 안에서만 돈다.
    await user.tab();
    expect(screen.getByRole("link", { name: "글 보기" })).toHaveFocus();
    await user.tab();
    expect(close).toHaveFocus();

    await user.click(close);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it("US3-AC5 Esc와 바깥을 눌러 닫는다", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "카드 열기" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "카드 열기" }));
    await user.click(screen.getByTestId("card-dialog-backdrop"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("US3-AC2 포인터를 움직이면 그쪽으로 기울고 빛이 따라온다", async () => {
    stubReducedMotion(false);
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "카드 열기" }));
    const target = tiltTarget();

    // 오른쪽 위 모서리 쪽
    fireEvent.pointerMove(target, { clientX: 280, clientY: 128 });

    expect(target.style.getPropertyValue("--card-x")).toBe("90%");
    expect(target.style.getPropertyValue("--card-y")).toBe("10%");
    expect(
      parseFloat(target.style.getPropertyValue("--card-ry")),
    ).toBeGreaterThan(0);
    expect(
      parseFloat(target.style.getPropertyValue("--card-rx")),
    ).toBeGreaterThan(0);

    fireEvent.pointerLeave(target);
    expect(target.style.getPropertyValue("--card-ry")).toBe("");
    expect(target.style.getPropertyValue("--card-x")).toBe("");
  });

  it("US3-AC4 움직임 줄이기에서는 기울지 않고 빛도 움직이지 않는다", async () => {
    stubReducedMotion(true);
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "카드 열기" }));
    const target = tiltTarget();

    fireEvent.pointerMove(target, { clientX: 280, clientY: 128 });

    expect(target.style.getPropertyValue("--card-ry")).toBe("");
    expect(target.style.getPropertyValue("--card-x")).toBe("");
  });
});
