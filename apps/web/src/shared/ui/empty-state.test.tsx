import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EmptyState } from "./empty-state";

describe("EmptyState", () => {
  it("POLISH-AC8 빈 화면은 그림, 제목, 설명, 버튼 자리를 가진다", () => {
    const { container } = render(
      <EmptyState
        imageSrc="/brand/ogu-hello.webp"
        title="아직 올라온 고민이 없어요."
        description="첫 이야기를 들려주세요."
      >
        <a href="/write">첫 고민 남기기</a>
      </EmptyState>,
    );
    expect(container.querySelector("img")).toHaveClass("size-33");
    expect(screen.getByText("아직 올라온 고민이 없어요.")).toHaveClass(
      "text-title",
      "font-extrabold",
    );
    expect(screen.getByText("첫 이야기를 들려주세요.")).toHaveClass(
      "text-fg-subtle",
    );
    expect(screen.getByRole("link", { name: "첫 고민 남기기" })).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("POLISH-AC8 오류일 때 제목과 설명이 한 문장으로 alert에 읽힌다", () => {
    render(
      <EmptyState
        tone="error"
        title="피드를 불러오지 못했습니다."
        description="잠시 후 다시 시도해주세요."
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "피드를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.",
    );
  });

  it("POLISH-AC8 그림은 장식이라 이름이 없다", () => {
    const { container } = render(
      <EmptyState imageSrc="/brand/ogu-listen.webp" title="아직 없어요" />,
    );
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(container.querySelector("img")).toHaveAttribute("alt", "");
  });

  it("그림과 버튼이 없으면 그 자리를 만들지 않는다", () => {
    const { container } = render(<EmptyState title="아직 없어요" />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelectorAll("div")).toHaveLength(1);
  });
});
