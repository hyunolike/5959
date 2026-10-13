import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Spinner } from "./spinner";

describe("Spinner", () => {
  it("DS-US4-AC2 불러오는 중이라는 말을 글자로 담아 화면 낭독기가 한국어로 읽는다", () => {
    render(<Spinner />);
    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("불러오는 중");
    expect(status).not.toHaveAttribute("aria-label");
  });

  it("DS-US4-AC2 도는 그림은 낭독하지 않고 움직임을 줄이면 돌지 않는다", () => {
    render(<Spinner className="size-6" label="글을 불러오는 중" />);
    const status = screen.getByRole("status");
    expect(status).toHaveTextContent("글을 불러오는 중");
    const ring = status.querySelector("[aria-hidden]");
    expect(ring).toHaveClass("motion-safe:animate-spin", "size-6");
  });
});
