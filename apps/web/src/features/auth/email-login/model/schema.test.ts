import { describe, expect, it } from "vitest";

import { loginSchema } from "./schema";

describe("loginSchema", () => {
  it("이메일과 비밀번호가 있으면 통과한다", () => {
    const result = loginSchema.safeParse({
      email: "user@example.com",
      password: "abcd1234",
    });
    expect(result.success).toBe(true);
  });

  it("이메일 형식이 아니면 실패한다", () => {
    const result = loginSchema.safeParse({
      email: "not-an-email",
      password: "abcd1234",
    });
    expect(result.success).toBe(false);
  });

  it("비밀번호가 비어 있으면 실패한다", () => {
    const result = loginSchema.safeParse({
      email: "user@example.com",
      password: "",
    });
    expect(result.success).toBe(false);
  });
});
