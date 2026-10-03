import { describe, expect, it } from "vitest";

import {
  COMMENT_CONTENT_MAX_CODE_POINTS,
  COMMENT_CONTENT_MAX_LENGTH,
  writeCommentSchema,
} from "./schema";

function firstMessage(content: string) {
  const result = writeCommentSchema.safeParse({ content });
  return result.success ? undefined : result.error.issues[0]?.message;
}

/** FR-008, research R8: 앞뒤 공백을 뺀 글자(grapheme) 1~300자, 코드 포인트 3,000개 이하. */
describe("writeCommentSchema", () => {
  it("상한은 API와 같은 300자, 코드 포인트 3,000개다", () => {
    expect(COMMENT_CONTENT_MAX_LENGTH).toBe(300);
    expect(COMMENT_CONTENT_MAX_CODE_POINTS).toBe(3000);
  });

  it.each([
    ["한 글자(경계값)", "가"],
    ["300자(경계값)", "가".repeat(300)],
    ["결합 이모지 300개는 300자다", "👨‍👩‍👧".repeat(300)],
    ["앞뒤 공백은 빼고 센다", `  ${"가".repeat(300)}  `],
  ])("허용: %s", (_label, content) => {
    expect(writeCommentSchema.safeParse({ content }).success).toBe(true);
  });

  it("앞뒤 공백을 뺀 값을 보낸다", () => {
    expect(writeCommentSchema.parse({ content: "  힘내요 \n" })).toEqual({
      content: "힘내요",
    });
  });

  it.each([
    ["빈 문자열", ""],
    ["공백만", "  \n "],
  ])("거부: %s는 댓글을 적으라고 안내한다", (_label, content) => {
    expect(firstMessage(content)).toBe("댓글을 적어 주세요.");
  });

  it("거부: 301자는 300자 이하로 적으라고 안내한다", () => {
    expect(firstMessage("가".repeat(301))).toBe("300자 이하로 적어 주세요.");
  });

  it("거부: 글자 수는 300자 이하여도 코드 포인트가 3,000개를 넘으면 따로 안내한다", () => {
    const zalgo = `a${"́".repeat(COMMENT_CONTENT_MAX_CODE_POINTS)}`;
    expect(firstMessage(zalgo)).toBe("너무 긴 댓글이에요. 조금 줄여 주세요.");
  });
});
