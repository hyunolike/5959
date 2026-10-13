import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { SegmentedControl } from "./segmented-control";

const OPTIONS = [
  { value: "LATEST", label: "최신순" },
  { value: "POPULAR", label: "인기순" },
] as const;

describe("SegmentedControl", () => {
  it("POLISH-AC6 세그먼트는 이름이 있는 묶음이고 고른 칸만 눌린 상태다", () => {
    render(
      <SegmentedControl
        label="정렬"
        options={OPTIONS}
        value="LATEST"
        onChange={vi.fn()}
      />,
    );
    const group = screen.getByRole("group", { name: "정렬" });
    expect(group).toHaveClass("bg-surface-sunken", "rounded-control-sm");
    expect(screen.getByRole("button", { name: "최신순" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "인기순" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("POLISH-AC6 고른 칸은 흰 면으로 떠 있고 칸의 누르는 영역은 44px이다", () => {
    render(
      <SegmentedControl
        label="정렬"
        options={OPTIONS}
        value="LATEST"
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "최신순" })).toHaveClass(
      "h-8",
      "before:-inset-y-[7px]",
      "aria-pressed:bg-surface",
      "aria-pressed:shadow-raised",
      "border",
      "border-transparent",
    );
  });

  it("POLISH-AC6 다른 칸을 누르면 그 값으로 onChange를 부른다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <SegmentedControl
        label="정렬"
        options={OPTIONS}
        value="LATEST"
        onChange={onChange}
      />,
    );
    await user.click(screen.getByRole("button", { name: "인기순" }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith("POPULAR");
  });

  it("POLISH-AC6 이미 고른 칸을 눌러도 onChange를 부르지 않는다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <SegmentedControl
        label="정렬"
        options={OPTIONS}
        value="LATEST"
        onChange={onChange}
      />,
    );
    await user.click(screen.getByRole("button", { name: "최신순" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
