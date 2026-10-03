import { z } from "zod";

import { COMMENT_TONE_LABELS, type CommentTone } from "@/entities/post";
import { countGraphemes } from "@/shared/lib";

/** FR-001: 앞뒤 공백을 뺀 사람이 보는 글자(grapheme) 1~500자. API `Post.CONTENT_MAX_LENGTH`와 같다. */
export const POST_CONTENT_MAX_LENGTH = 500;

/**
 * research R8: 결합 문자를 겹친 글(Zalgo)을 막는 코드 포인트 상한. API
 * `Post.CONTENT_MAX_CODE_POINTS`와 같다. 넘으면 API가 400을 돌려주므로 미리 막는다.
 */
export const POST_CONTENT_MAX_CODE_POINTS = 5000;

const COMMENT_TONE_VALUES = Object.keys(COMMENT_TONE_LABELS) as [
  CommentTone,
  ...CommentTone[],
];

export const writePostSchema = z.object({
  content: z
    .string()
    .trim()
    .refine((value) => value.length > 0, "고민을 적어 주세요.")
    .refine(
      (value) =>
        countGraphemes(value) <= POST_CONTENT_MAX_LENGTH &&
        Array.from(value).length <= POST_CONTENT_MAX_CODE_POINTS,
      `${POST_CONTENT_MAX_LENGTH}자 이하로 적어 주세요.`,
    ),
  commentTone: z.enum(COMMENT_TONE_VALUES, {
    message: "댓글 말투를 골라 주세요.",
  }),
});

export type WritePostFormInput = z.input<typeof writePostSchema>;
export type WritePostFormValues = z.output<typeof writePostSchema>;
