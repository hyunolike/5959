import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Button } from "./button";

describe("Button", () => {
  it("DS-US2-AC1 기본은 먹색 주요 버튼이다", () => {
    render(<Button>올리기</Button>);
    expect(screen.getByRole("button", { name: "올리기" })).toHaveClass(
      "bg-action",
      "text-on-action",
      "min-h-12",
    );
  });

  it.each([
    ["ghost", "text-fg-muted"],
    ["danger", "bg-danger"],
  ] as const)("DS-US2-AC1 %s 변형은 %s를 쓴다", (variant, className) => {
    render(<Button variant={variant}>확인</Button>);
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(className);
  });

  it("POLISH-AC3 보조 버튼은 테두리 없이 옅은 채움이다", () => {
    render(<Button variant="secondary">취소</Button>);
    const button = screen.getByRole("button", { name: "취소" });
    expect(button).toHaveClass("bg-surface-sunken", "text-fg");
    expect(button).not.toHaveClass("border");
  });

  it("POLISH-AC3 테두리 버튼은 outline이다", () => {
    render(<Button variant="outline">구글로 계속하기</Button>);
    expect(screen.getByRole("button", { name: "구글로 계속하기" })).toHaveClass(
      "border",
      "border-line-strong",
      "bg-surface",
    );
  });

  it.each([
    ["sm", "min-h-10"],
    ["md", "min-h-12"],
    ["lg", "min-h-14"],
  ] as const)("POLISH-AC4 %s 크기는 %s다", (size, className) => {
    render(<Button size={size}>확인</Button>);
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(className);
  });

  it("POLISH-AC4 작은 버튼은 누르는 영역을 44px로 넓힌다", () => {
    render(<Button size="sm">확인</Button>);
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(
      "relative",
      "before:absolute",
      "before:-inset-y-0.5",
      "rounded-control-sm",
    );
  });

  it("DS-US2-AC1 asChild면 링크가 버튼 모양을 입는다", () => {
    render(
      <Button asChild>
        <a href="/write">고민 쓰기</a>
      </Button>,
    );
    expect(screen.getByRole("link", { name: "고민 쓰기" })).toHaveClass(
      "bg-action",
    );
  });

  it("긴 문구가 줄바꿈될 수 있다", () => {
    render(<Button>로그인하고 이야기 보기</Button>);
    expect(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    ).not.toHaveClass("whitespace-nowrap");
  });

  it("DS-US3-AC6 한국어 문구는 단어 단위로 줄바꿈한다", () => {
    render(<Button>로그인하고 이야기 보기</Button>);
    expect(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    ).toHaveClass("break-keep");
  });
});
