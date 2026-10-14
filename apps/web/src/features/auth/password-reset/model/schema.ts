import { z } from "zod";

import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from "@/shared/config";

export const requestResetSchema = z.object({
  email: z.email("올바른 이메일 형식이 아닙니다."),
});

export type RequestResetFormValues = z.infer<typeof requestResetSchema>;

/** 새 비밀번호는 가입과 같은 규칙이다(015 FR-005). 두 칸이 같아야 한다(US2-AC6). */
export const resetPasswordSchema = z
  .object({
    password: z.string().regex(PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE),
    passwordConfirm: z.string(),
  })
  .refine((values) => values.password === values.passwordConfirm, {
    path: ["passwordConfirm"],
    message: "두 칸의 비밀번호가 서로 달라요.",
  });

export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;

/** 429의 남은 시간을 분으로 올림한다. 남은 시간이 있으면 최소 1분이다. */
export function waitMinutes(retryAfterSeconds: number | undefined): number {
  return Math.max(1, Math.ceil((retryAfterSeconds ?? 0) / 60));
}
