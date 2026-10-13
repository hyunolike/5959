import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/features/write-post", () => ({
  WriteForm: () => <form aria-label="고민 쓰기" />,
}));

import WritePage from "./page";

describe("고민 쓰기 화면", () => {
  it("US3-AC1 글의 앞 50자가 로그인하지 않은 방문자에게도 닉네임 없이 보인다고 알린다", () => {
    render(<WritePage />);
    expect(
      screen.getByText(
        "올린 글의 앞 50자는 로그인하지 않은 방문자에게도 닉네임 없이 보여요.",
      ),
    ).toBeInTheDocument();
  });
});
