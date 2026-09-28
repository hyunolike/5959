import { describe, expect, it } from "vitest";

import { signupSchema } from "./schema";

function withPassword(password: string) {
  return { email: "user@example.com", password };
}

/** data-model.md, US1-AC3: 8~20자, 영문 1자 이상과 숫자 1자 이상 포함. */
describe("signupSchema 비밀번호 규칙", () => {
  it.each([
    ["8자(경계값)", "abcd1234"],
    ["20자(경계값)", "a".repeat(18) + "12"],
    ["영문과 숫자가 섞여 있음", "Passw0rd!"],
  ])("허용: %s(%s)", (_label, password) => {
    expect(signupSchema.safeParse(withPassword(password)).success).toBe(true);
  });

  it.each([
    ["US1-AC3 7자(8자 미만)", "abcd123"],
    ["21자(20자 초과)", "a".repeat(19) + "12"],
    ["US1-AC3 숫자 없음", "abcdefgh"],
    ["US1-AC3 영문 없음", "12345678"],
  ])("거부: %s(%s)", (_label, password) => {
    expect(signupSchema.safeParse(withPassword(password)).success).toBe(false);
  });
});

describe("signupSchema 이메일 형식", () => {
  it("이메일 형식이 아니면 실패한다", () => {
    const result = signupSchema.safeParse({
      email: "not-an-email",
      password: "abcd1234",
    });
    expect(result.success).toBe(false);
  });
});
