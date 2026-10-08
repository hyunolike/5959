import { describe, expect, it } from "vitest";

import { memberProfileSchema } from "@/entities/member";

import {
  changedFields,
  editProfileSchema,
  hasChanges,
  isOwnNickname,
} from "./schema";

const CURRENT = {
  nickname: "Ogu",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
} as const;

const withNickname = (nickname: string) => ({ ...CURRENT, nickname });

describe("editProfileSchema 닉네임 규칙", () => {
  it("US5-AC2 M1 온보딩과 같은 스키마다", () => {
    expect(editProfileSchema).toBe(memberProfileSchema);
  });

  it.each([
    ["한글", "오구오구"],
    ["영문과 숫자", "Ogu1"],
    ["10자(경계값)", "abcdefghij"],
    ["한 글자(경계값)", "오"],
    ["앞뒤 공백은 제거하고 통과시킨다", "  오구  "],
  ])("허용: %s(%s)", (_label, nickname) => {
    expect(editProfileSchema.safeParse(withNickname(nickname)).success).toBe(
      true,
    );
  });

  it.each([
    ["빈 문자열", ""],
    ["공백만", "   "],
    ["11자(경계값 초과)", "abcdefghijk"],
    ["가운데 공백", "오 구"],
    ["특수문자", "오구!"],
    ["이모지", "오구😀"],
  ])("US5-AC2 거부: %s(%s)는 온보딩과 같은 안내를 준다", (_label, nickname) => {
    const result = editProfileSchema.safeParse(withNickname(nickname));

    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0].message).toBe(
        "한글, 영문, 숫자로 1~10자를 입력하세요.",
      );
    }
  });
});

describe("changedFields", () => {
  it("하나도 안 바꾸면 빈 객체다", () => {
    const changes = changedFields(CURRENT, { ...CURRENT });

    expect(changes).toEqual({});
    expect(hasChanges(changes)).toBe(false);
  });

  it("앞뒤 공백만 다른 닉네임은 바뀐 것이 아니다", () => {
    expect(changedFields(CURRENT, withNickname("  Ogu  "))).toEqual({});
  });

  it("바뀐 필드만 담는다", () => {
    expect(
      changedFields(CURRENT, { ...CURRENT, careerYear: "YEAR_4" }),
    ).toEqual({ careerYear: "YEAR_4" });
    expect(
      changedFields(CURRENT, {
        nickname: " 새이름 ",
        jobRole: "DESIGN",
        careerYear: "YEAR_3",
      }),
    ).toEqual({ nickname: "새이름", jobRole: "DESIGN" });
  });

  it("닉네임의 대소문자만 바꿔도 바뀐 것이다", () => {
    const changes = changedFields(CURRENT, withNickname("OGU"));

    expect(changes).toEqual({ nickname: "OGU" });
    expect(hasChanges(changes)).toBe(true);
  });
});

describe("isOwnNickname", () => {
  it("내 닉네임과 대소문자만 다르면 참이다", () => {
    expect(isOwnNickname("Ogu", "Ogu")).toBe(true);
    expect(isOwnNickname("Ogu", " OGU ")).toBe(true);
  });

  it("다른 닉네임이거나 지금 닉네임이 없으면 거짓이다", () => {
    expect(isOwnNickname("Ogu", "Ogu1")).toBe(false);
    expect(isOwnNickname(null, "Ogu")).toBe(false);
    expect(isOwnNickname("Ogu", "")).toBe(false);
  });
});
