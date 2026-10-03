import { z } from "zod";

import { countGraphemes } from "@/shared/lib";

/** FR-008: 앞뒤 공백을 뺀 사람이 보는 글자(grapheme) 1~300자. API `Comment.CONTENT_MAX_LENGTH`와 같다. */
export const COMMENT_CONTENT_MAX_LENGTH = 300;

/**
 * research R8: 결합 문자를 겹친 댓글(Zalgo)을 막는 코드 포인트 상한. API
 * `Comment.CONTENT_MAX_CODE_POINTS`와 같다. 넘으면 API가 400을 돌려주므로 미리 막는다.
 */
export const COMMENT_CONTENT_MAX_CODE_POINTS = 3000;

export const writeCommentSchema = z.object({
  content: z
    .string()
    .trim()
    .refine((value) => value.length > 0, "댓글을 적어 주세요.")
    .refine(
      (value) => countGraphemes(value) <= COMMENT_CONTENT_MAX_LENGTH,
      `${COMMENT_CONTENT_MAX_LENGTH}자 이하로 적어 주세요.`,
    )
    .refine(
      (value) => Array.from(value).length <= COMMENT_CONTENT_MAX_CODE_POINTS,
      "너무 긴 댓글이에요. 조금 줄여 주세요.",
    ),
});

export type WriteCommentFormInput = z.input<typeof writeCommentSchema>;
export type WriteCommentFormValues = z.output<typeof writeCommentSchema>;
