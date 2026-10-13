import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { TextField } from "./text-field";
import { Textarea } from "./textarea";

describe("TextField", () => {
  it("DS-US2-AC1 이름표가 입력에 이어진다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).toHaveClass("h-14");
  });

  it("도움말과 오류를 입력의 설명으로 잇는다", () => {
    render(
      <TextField label="닉네임" hint="2~10자" error="이미 쓰는 닉네임이에요" />,
    );
    const input = screen.getByLabelText("닉네임");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("2~10자 이미 쓰는 닉네임이에요");
    expect(screen.getByRole("alert")).toHaveTextContent(
      "이미 쓰는 닉네임이에요",
    );
  });

  it("POLISH-AC7 입력은 56px이고 테두리가 남아 있다", () => {
    render(
      <>
        <TextField label="닉네임" />
        <Textarea aria-label="고민" />
      </>,
    );
    expect(screen.getByLabelText("닉네임")).toHaveClass(
      "h-14",
      "border",
      "border-line-strong",
      "focus-visible:border-fg",
    );
    expect(screen.getByLabelText("고민")).toHaveClass(
      "border",
      "border-line-strong",
      "focus-visible:border-fg",
    );
  });

  it("오류가 없으면 aria-invalid를 붙이지 않는다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).not.toHaveAttribute("aria-invalid");
  });

  it("DS-US3-AC6 여러 줄 입력은 세로로만 크기를 바꿀 수 있다", () => {
    render(<Textarea aria-label="고민" />);
    expect(screen.getByLabelText("고민")).toHaveClass("resize-y");
  });
});
