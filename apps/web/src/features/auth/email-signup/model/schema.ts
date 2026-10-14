import { z } from "zod";

import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from "@/shared/config";

export const signupSchema = z.object({
  email: z.email("올바른 이메일 형식이 아닙니다."),
  password: z.string().regex(PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE),
});

export type SignupFormValues = z.infer<typeof signupSchema>;
