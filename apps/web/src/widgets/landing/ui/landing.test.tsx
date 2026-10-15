import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Landing } from "./landing";

describe("Landing", () => {
  it("SEO-AC1 서비스가 무엇인지 제목 하나로 알리고 로그인과 둘러보기로 가는 길을 둔다", () => {
    render(<Landing />);
    expect(
      screen.getByRole("heading", { level: 1, name: /오구오구/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "시작하기" })).toHaveAttribute(
      "href",
      "/login",
    );
    expect(screen.getByRole("link", { name: "먼저 둘러보기" })).toHaveAttribute(
      "href",
      "/home",
    );
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });

  it("DS-US3-AC5 다섯 감정의 몬스터 그림과 이름을 보인다", () => {
    render(<Landing />);
    const list = within(
      screen.getByRole("region", { name: "다섯 가지 감정 몬스터" }),
    );
    expect(list.getAllByRole("img", { name: /몬스터, 멀쩡함$/ })).toHaveLength(
      5,
    );
    for (const name of ["불안", "무기력", "외로움", "자기비하", "짜증"]) {
      expect(list.getByText(name, { exact: true })).toBeInTheDocument();
    }
  });

  it("SEO-AC1 쓰는 순서를 번호 있는 목록으로 보인다", () => {
    render(<Landing />);
    const steps = within(
      screen.getByRole("region", { name: "오구오구는 이렇게 써요" }),
    );
    expect(steps.getAllByRole("listitem")).toHaveLength(4);
    expect(steps.getByText("익명으로 고민을 적어요")).toBeInTheDocument();
  });

  it("016 US5-AC1 쓰는 순서 옆에 사용법 장면이 있다", () => {
    render(<Landing />);
    const steps = within(
      screen.getByRole("region", { name: "오구오구는 이렇게 써요" }),
    );
    expect(
      steps.getByRole("img", { name: /고민을 쓰면 몬스터가 나타나고/ }),
    ).toBeInTheDocument();
  });
});
