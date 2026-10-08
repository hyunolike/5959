import { describe, expect, it } from "vitest";

import {
  POST_CONTENT_MAX_CODE_POINTS,
  POST_CONTENT_MAX_LENGTH,
  writePostSchema,
} from "./schema";

function withContent(content: string) {
  return { content, commentTone: "COMFORT_ME" as const };
}

function firstMessage(result: ReturnType<typeof writePostSchema.safeParse>) {
  return result.success ? undefined : result.error.issues[0]?.message;
}

/**
 * FR-001, research R8: 앞뒤 공백을 뺀 사람이 보는 글자(grapheme) 1~500자와
 * 댓글 말투 하나. API의 Post 도메인 검증과 같은 규칙이다.
 */
describe("writePostSchema 본문 길이", () => {
  it.each([
    ["한 글자(경계값)", "가"],
    ["500자(경계값)", "가".repeat(POST_CONTENT_MAX_LENGTH)],
    ["결합 이모지 500개는 500자다", "👨‍👩‍👧".repeat(POST_CONTENT_MAX_LENGTH)],
    ["앞뒤 공백은 빼고 센다", `  ${"가".repeat(POST_CONTENT_MAX_LENGTH)}  `],
  ])("US1-AC1 허용: %s", (_label, content) => {
    expect(writePostSchema.safeParse(withContent(content)).success).toBe(true);
  });

  it.each([
    ["빈 문자열", ""],
    ["공백만", "   \n\t "],
  ])("US1-AC2 거부: %s는 본문을 적으라고 안내한다", (_label, content) => {
    const result = writePostSchema.safeParse(withContent(content));
    expect(result.success).toBe(false);
    expect(firstMessage(result)).toBe("고민을 적어 주세요.");
  });

  it("US1-AC2 거부: 501자는 500자 이하로 적으라고 안내한다", () => {
    const result = writePostSchema.safeParse(
      withContent("가".repeat(POST_CONTENT_MAX_LENGTH + 1)),
    );
    expect(result.success).toBe(false);
    expect(firstMessage(result)).toBe("500자 이하로 적어 주세요.");
  });

  it("US1-AC2 거부: 글자 수는 500자 이하여도 코드 포인트가 5,000개를 넘으면 거부한다", () => {
    // 결합 문자를 겹친 한 글자(Zalgo). 글자 수는 1이지만 코드 포인트는 5,001개다.
    const zalgo = `a${"́".repeat(POST_CONTENT_MAX_CODE_POINTS)}`;
    const result = writePostSchema.safeParse(withContent(zalgo));
    expect(result.success).toBe(false);
    expect(firstMessage(result)).toBe("너무 긴 글이에요. 조금 줄여 주세요.");
  });

  it("성공하면 앞뒤 공백을 뺀 본문을 돌려준다", () => {
    const result = writePostSchema.safeParse(withContent("  고민  "));
    expect(result.success && result.data.content).toBe("고민");
  });
});

describe("writePostSchema 댓글 말투", () => {
  it("US1-AC2 말투를 고르지 않으면 말투를 고르라고 안내한다", () => {
    const result = writePostSchema.safeParse({ content: "고민" });
    expect(result.success).toBe(false);
    expect(firstMessage(result)).toBe("댓글 말투를 골라 주세요.");
  });

  it("목록에 없는 말투는 거부한다", () => {
    const result = writePostSchema.safeParse({
      content: "고민",
      commentTone: "SHOUT",
    });
    expect(result.success).toBe(false);
  });

  it.each(["VENT_WITH_ME", "COMFORT_ME", "WARM_ADVICE", "MAKE_ME_LAUGH"])(
    "US1-AC1 말투 %s는 허용한다",
    (commentTone) => {
      expect(
        writePostSchema.safeParse({ content: "고민", commentTone }).success,
      ).toBe(true);
    },
  );
});
