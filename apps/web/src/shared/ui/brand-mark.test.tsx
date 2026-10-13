import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { BrandMark } from "./brand-mark";

describe("BrandMark", () => {
  it("BRAND-US1-AC2 그림 로고도 서비스 이름을 한 번 읽을 수 있다", () => {
    render(<BrandMark />);
    expect(screen.getAllByRole("img", { name: /^오구오구$/ })).toHaveLength(1);
  });

  it("LOGO-AC1 로고는 심볼과 글자가 든 가로형 SVG 하나다", () => {
    const { container } = render(<BrandMark />);

    expect(screen.getByText("오구오구")).toHaveClass("sr-only");
    expect(container).not.toHaveTextContent("5959");
    const images = container.querySelectorAll("img");
    expect(images).toHaveLength(1);
    // 이름은 감싼 요소가 읽힌다. 그림은 낭독하지 않는다.
    expect(images[0]).toHaveAttribute("alt", "");
    expect(images[0].getAttribute("src")).toBe("/brand/ogu-logo.svg");
  });

  it("LOGO-AC1 머리말에서 좁은 화면 28px, 넓은 화면 32px 높이로 놓이고 비율을 지킨다", () => {
    const { container } = render(<BrandMark />);
    const image = container.querySelector("img")!;
    expect(image).toHaveClass("h-7", "w-auto", "sm:h-8");
    // 가로형 원본은 304×64다.
    expect(image).toHaveAttribute("width", "304");
    expect(image).toHaveAttribute("height", "64");
  });
});
