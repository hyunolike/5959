import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { OAuthButtons } from "./oauth-buttons";

describe("OAuthButtons", () => {
  it("US3-AC1 카카오가 꺼져 있으면 구글 로그인만 표시한다", () => {
    render(<OAuthButtons kakaoEnabled={false} />);
    expect(
      screen.queryByRole("link", { name: "카카오로 계속하기" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "구글로 계속하기" }),
    ).toHaveAttribute("href", "/api/auth/oauth/google?next=%2Fhome");
  });
  it("카카오, 구글 버튼은 OAuth 시작 라우트로 가는 일반 링크다", () => {
    render(<OAuthButtons />);

    expect(
      screen.getByRole("link", { name: "카카오로 계속하기" }),
    ).toHaveAttribute("href", "/api/auth/oauth/kakao?next=%2Fhome");
    expect(
      screen.getByRole("link", { name: "구글로 계속하기" }),
    ).toHaveAttribute("href", "/api/auth/oauth/google?next=%2Fhome");
  });

  it("POLISH-AC18 카카오 버튼은 카카오가 정한 노란 바탕이고 구글 버튼은 테두리 버튼이다", () => {
    render(<OAuthButtons />);
    const kakao = screen.getByRole("link", { name: "카카오로 계속하기" });
    expect(kakao).toHaveClass("bg-kakao", "text-fg", "min-h-14");
    expect(kakao).not.toHaveClass("bg-surface");
    expect(screen.getByRole("link", { name: "구글로 계속하기" })).toHaveClass(
      "border-line-strong",
      "bg-surface",
    );
  });

  it("next를 받으면 인코딩해 쿼리에 담는다", () => {
    render(<OAuthButtons next="/my/posts?tab=1" />);

    expect(
      screen.getByRole("link", { name: "카카오로 계속하기" }),
    ).toHaveAttribute(
      "href",
      "/api/auth/oauth/kakao?next=%2Fmy%2Fposts%3Ftab%3D1",
    );
  });
});
