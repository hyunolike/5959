import { z } from "zod";

/** data-model.md, US1-AC3: 8~20자, 영문 1자 이상과 숫자 1자 이상 포함. */
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/;

export const signupSchema = z.object({
  email: z.email("올바른 이메일 형식이 아닙니다."),
  password: z
    .string()
    .regex(
      PASSWORD_PATTERN,
      "비밀번호는 영문과 숫자를 포함해 8~20자로 입력하세요.",
    ),
});

export type SignupFormValues = z.infer<typeof signupSchema>;
