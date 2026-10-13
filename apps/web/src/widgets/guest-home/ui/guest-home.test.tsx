import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const requestLogin = vi.hoisted(() => vi.fn());
vi.mock("@/features/auth/guest-access", () => ({
  useGuestAccess: () => ({ authenticated: false, requestLogin }),
}));

import { GuestHome } from "./guest-home";

beforeEach(() => {
  requestLogin.mockReset();
});

describe("GuestHome", () => {
  it("DS-US3-AC5 다섯 감정의 몬스터 그림과 이름을 보인다", () => {
    render(<GuestHome />);
    expect(
      screen.getAllByRole("img", { name: /몬스터, 멀쩡함$/ }),
    ).toHaveLength(5);
    for (const name of ["불안", "무기력", "외로움", "자기비하", "짜증"]) {
      expect(screen.getByText(name, { exact: true })).toBeInTheDocument();
    }
  });

  it("DS-US3-AC5 회원의 글, 수치, 피드 구역이 없다", () => {
    const { container } = render(<GuestHome />);
    expect(
      screen.queryByRole("region", { name: "피드" }),
    ).not.toBeInTheDocument();
    expect(container.querySelector('a[href^="/post/"]')).toBeNull();
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
    expect(container).not.toHaveTextContent(/HP \d|공감 \d|댓글 \d|\d+명/);
  });

  it("GUEST-US1-AC1 무엇을 하는 곳인지와 글은 로그인 후 본다는 것을 알린다", () => {
    render(<GuestHome />);
    expect(
      screen.getByRole("heading", { level: 1, name: "마음 이야기" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", {
        name: "마음 이야기는 로그인 후 볼 수 있어요",
      }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });

  it("GUEST-US1-AC2 버튼은 가려던 곳과 함께 로그인 안내를 연다", async () => {
    const user = userEvent.setup();
    render(<GuestHome />);
    await user.click(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    );
    expect(requestLogin).toHaveBeenLastCalledWith("/home");
    await user.click(screen.getByRole("button", { name: "고민 쓰기" }));
    expect(requestLogin).toHaveBeenLastCalledWith("/write");
  });

  it("DS-US3-AC5 메뉴 설명 목록은 두지 않는다", () => {
    render(<GuestHome />);
    expect(screen.queryByText("메뉴 안내")).not.toBeInTheDocument();
  });
});
