import { z } from "zod";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";

/** data-model.md: 앞뒤 공백 제거 후 `^[가-힣A-Za-z0-9]{1,10}$`. apps/api의 Member 도메인 검증(T026)과 같다. */
const NICKNAME_PATTERN = /^[가-힣A-Za-z0-9]{1,10}$/;

const JOB_ROLE_VALUES = Object.keys(JOB_ROLE_LABELS) as [
  keyof typeof JOB_ROLE_LABELS,
  ...(keyof typeof JOB_ROLE_LABELS)[],
];
const CAREER_YEAR_VALUES = Object.keys(CAREER_YEAR_LABELS) as [
  keyof typeof CAREER_YEAR_LABELS,
  ...(keyof typeof CAREER_YEAR_LABELS)[],
];

export const onboardingSchema = z.object({
  nickname: z
    .string()
    .trim()
    .regex(NICKNAME_PATTERN, "한글, 영문, 숫자로 1~10자를 입력하세요."),
  jobRole: z.enum(JOB_ROLE_VALUES, { message: "직군을 선택하세요." }),
  careerYear: z.enum(CAREER_YEAR_VALUES, { message: "경력을 선택하세요." }),
});

export type OnboardingFormValues = z.infer<typeof onboardingSchema>;
