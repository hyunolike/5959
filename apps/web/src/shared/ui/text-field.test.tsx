import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { TextField } from "./text-field";

describe("TextField", () => {
  it("DS-US2-AC1 이름표가 입력에 이어진다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).toHaveClass("h-13");
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

  it("오류가 없으면 aria-invalid를 붙이지 않는다", () => {
    render(<TextField label="닉네임" />);
    expect(screen.getByLabelText("닉네임")).not.toHaveAttribute("aria-invalid");
  });
});
