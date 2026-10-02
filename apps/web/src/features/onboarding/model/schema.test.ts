import { describe, expect, it } from "vitest";

import { onboardingSchema } from "./schema";

function withNickname(nickname: string) {
  return {
    nickname,
    jobRole: "DEVELOPMENT" as const,
    careerYear: "YEAR_1" as const,
  };
}

/**
 * data-model.md의 닉네임 규칙(앞뒤 공백 제거 후 `^[가-힣A-Za-z0-9]{1,10}$`)을
 * apps/api의 Member 도메인 검증(T026)과 똑같이 적용하는지 확인한다.
 */
describe("onboardingSchema 닉네임 규칙", () => {
  it.each([
    ["한글", "오구오구"],
    ["영문과 숫자", "Ogu1"],
    ["10자(경계값)", "abcdefghij"],
    ["한 글자(경계값)", "오"],
    ["앞뒤 공백은 제거하고 통과시킨다", "  오구  "],
  ])("허용: %s(%s)", (_label, nickname) => {
    const result = onboardingSchema.safeParse(withNickname(nickname));
    expect(result.success).toBe(true);
  });

  it.each([
    ["빈 문자열", ""],
    ["공백만", "   "],
    ["11자(경계값 초과)", "abcdefghijk"],
    ["가운데 공백", "오 구"],
    ["특수문자", "오구!"],
    ["이모지", "오구😀"],
  ])("거부: %s(%s)", (_label, nickname) => {
    const result = onboardingSchema.safeParse(withNickname(nickname));
    expect(result.success).toBe(false);
  });
});

describe("onboardingSchema 직군, 경력", () => {
  it("직군과 경력이 없으면 실패한다", () => {
    const result = onboardingSchema.safeParse({ nickname: "오구" });
    expect(result.success).toBe(false);
  });

  it("직군, 경력이 라벨 맵의 값 중 하나가 아니면 실패한다", () => {
    const result = onboardingSchema.safeParse({
      nickname: "오구",
      jobRole: "NOT_A_ROLE",
      careerYear: "YEAR_1",
    });
    expect(result.success).toBe(false);
  });

  it("올바른 입력이면 앞뒤 공백을 제거한 닉네임과 함께 성공한다", () => {
    const result = onboardingSchema.safeParse({
      nickname: "  오구  ",
      jobRole: "DEVELOPMENT",
      careerYear: "NEWCOMER",
    });
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.nickname).toBe("오구");
    }
  });
});
