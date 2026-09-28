import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { OAuthLoginErrorNotice } from "./oauth-login-error-notice";

describe("OAuthLoginErrorNotice", () => {
  it("US3-AC4 동의를 취소하고 돌아오면 오류가 아닌 안내로 다시 시도할 수 있다고 알려준다", () => {
    render(<OAuthLoginErrorNotice error="oauth_cancelled" />);

    const notice = screen.getByRole("status");
    expect(notice).toHaveTextContent("외부 계정 로그인을 취소했습니다");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("US3-AC3 이메일로 가입한 주소면 원래 가입한 방법으로 로그인하라고 안내한다", () => {
    render(<OAuthLoginErrorNotice error="email_registered" />);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "이메일과 비밀번호로 로그인해주세요",
    );
  });

  it("외부 로그인에 실패하면 다시 시도하라고 안내한다", () => {
    render(<OAuthLoginErrorNotice error="oauth_failed" />);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "외부 계정으로 로그인하지 못했습니다",
    );
  });

  it.each([[undefined], ["unknown"], [["oauth_failed", "x"]]])(
    "알 수 없는 값(%j)이면 아무것도 보여주지 않는다",
    (error) => {
      const { container } = render(<OAuthLoginErrorNotice error={error} />);

      expect(container).toBeEmptyDOMElement();
    },
  );
});
