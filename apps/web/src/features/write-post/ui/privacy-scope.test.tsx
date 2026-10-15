import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PrivacyScope } from "./privacy-scope";

describe("PrivacyScope", () => {
  it("016 US1-AC1 회원, 로그인하지 않은 방문자, 누구에게도 보이지 않는 것을 세 줄로 나눈다", () => {
    render(<PrivacyScope />);
    const scope = screen.getByRole("region", { name: "이 글은 이렇게 보여요" });

    const rows = within(scope).getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("회원");
    expect(rows[0]).toHaveTextContent("닉네임");
    expect(rows[0]).toHaveTextContent("직군과 경력");
    expect(rows[0]).toHaveTextContent("글 전체");
    expect(rows[2]).toHaveTextContent("이름");
    expect(rows[2]).toHaveTextContent("이메일");
    expect(rows[2]).toHaveTextContent("회사");
  });

  it("011 US3-AC1 글의 앞 50자가 로그인하지 않은 방문자에게 닉네임 없이 보인다고 알린다", () => {
    render(<PrivacyScope />);
    const [, guest] = screen.getAllByRole("listitem");

    expect(guest).toHaveTextContent("로그인하지 않은 방문자");
    expect(guest).toHaveTextContent("앞 50자");
    expect(guest).toHaveTextContent("닉네임 없이");
  });
});
