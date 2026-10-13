import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Button } from "./button";

describe("Button", () => {
  it("DS-US2-AC1 기본은 먹색 주요 버튼이다", () => {
    render(<Button>올리기</Button>);
    expect(screen.getByRole("button", { name: "올리기" })).toHaveClass(
      "bg-action",
      "text-on-action",
      "min-h-11",
    );
  });

  it.each([
    ["secondary", "border-line-strong"],
    ["subtle", "bg-surface-sunken"],
    ["ghost", "text-fg-muted"],
    ["danger", "bg-danger"],
  ] as const)("DS-US2-AC1 %s 변형은 %s를 쓴다", (variant, className) => {
    render(<Button variant={variant}>확인</Button>);
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(className);
  });

  it("DS-US2-AC2 큰 버튼은 52px다", () => {
    render(<Button size="lg">가입하기</Button>);
    expect(screen.getByRole("button", { name: "가입하기" })).toHaveClass(
      "min-h-13",
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
