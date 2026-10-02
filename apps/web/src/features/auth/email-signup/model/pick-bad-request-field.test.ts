import { describe, expect, it } from "vitest";

import { pickSignup400Field } from "./pick-bad-request-field";

/**
 * apps/api는 이메일 형식 위반과 비밀번호 규칙 위반을 둘 다 같은 400
 * INVALID_REQUEST로 뭉뚱그린다(SignupApiTests) — 메시지 내용으로 어느
 * 필드 문제인지 가른다.
 */
describe("pickSignup400Field", () => {
  it("메시지에 '이메일'이 있으면 email 필드를 고른다", () => {
    expect(pickSignup400Field("올바른 이메일 형식이 아닙니다.")).toBe("email");
  });

  it("그 외(비밀번호 규칙 위반 등)에는 password 필드를 고른다", () => {
    expect(
      pickSignup400Field(
        "비밀번호는 영문과 숫자를 포함해 8~20자로 입력하세요.",
      ),
    ).toBe("password");
  });

  it("메시지가 비어 있어도 password 필드를 기본으로 고른다", () => {
    expect(pickSignup400Field("")).toBe("password");
  });
});
