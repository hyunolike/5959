import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { IconButton } from "./icon-button";

describe("IconButton", () => {
  it("DS-US2-AC2 이름이 있고 44px 영역이다", () => {
    render(
      <IconButton aria-label="알림">
        <svg aria-hidden />
      </IconButton>,
    );
    const button = screen.getByRole("button", { name: "알림" });
    expect(button).toHaveClass("size-11");
    expect(button).toHaveAttribute("type", "button");
  });
});
