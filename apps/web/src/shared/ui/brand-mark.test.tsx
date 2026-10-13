import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { BrandMark } from "./brand-mark";

describe("BrandMark", () => {
  it("DS-US5-AC1 로고는 오구의 얼굴과 서비스 이름이다", () => {
    const { container } = render(<BrandMark />);

    expect(screen.getByText("오구오구")).toBeInTheDocument();
    expect(container).not.toHaveTextContent("5959");
    // 이름이 글자로 있으므로 그림은 낭독하지 않는다.
    const image = container.querySelector("img");
    expect(image).toHaveAttribute("alt", "");
    expect(image?.getAttribute("src")).toContain("ogu-symbol.webp");
  });

  it("DS-US5-AC1 서비스 이름은 글자 단계 안의 크기로 쓴다", () => {
    const { container } = render(<BrandMark />);
    expect(container.firstElementChild).toHaveClass(
      "text-title",
      "font-extrabold",
    );
    expect(container.firstElementChild?.className).not.toMatch(/text-\[/);
  });
});
