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

  it("DS-US2-AC2 보이는 높이는 36px이고 누르는 영역은 위아래로 넓다", () => {
    render(<Chip pressed={false}>디자인</Chip>);
    expect(screen.getByRole("button", { name: "디자인" })).toHaveClass(
      "h-9",
      "before:-inset-y-[5px]",
    );
  });
});
