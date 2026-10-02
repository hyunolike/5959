import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { OAuthButtons } from "./oauth-buttons";

describe("OAuthButtons", () => {
  it("카카오, 구글 버튼은 OAuth 시작 라우트로 가는 일반 링크다", () => {
    render(<OAuthButtons />);

    expect(
      screen.getByRole("link", { name: "카카오로 계속하기" }),
    ).toHaveAttribute("href", "/api/auth/oauth/kakao?next=%2Fhome");
    expect(
      screen.getByRole("link", { name: "구글로 계속하기" }),
    ).toHaveAttribute("href", "/api/auth/oauth/google?next=%2Fhome");
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
