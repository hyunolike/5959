import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Chip } from "./chip";

describe("Chip", () => {
  it("DS-US2-AC1 선택 상태를 aria-pressed로 전한다", () => {
    render(
      <>
        <Chip pressed>전체</Chip>
        <Chip pressed={false}>개발</Chip>
      </>,
    );
    expect(screen.getByRole("button", { name: "전체" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "개발" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("POLISH-AC5 보이는 높이는 40px이고 누르는 영역은 44px이다", () => {
    render(<Chip pressed={false}>디자인</Chip>);
    expect(screen.getByRole("button", { name: "디자인" })).toHaveClass(
      "h-10",
      "before:-inset-y-0.5",
    );
  });

  it("POLISH-AC5 칩은 테두리 없이 채움으로 고른 것을 알린다", () => {
    render(<Chip pressed={false}>디자인</Chip>);
    const chip = screen.getByRole("button", { name: "디자인" });
    expect(chip).toHaveClass(
      "bg-surface-sunken",
      "text-fg-muted",
      "aria-pressed:bg-action",
      "aria-pressed:text-on-action",
    );
    expect(chip).not.toHaveClass("border");
  });
});
