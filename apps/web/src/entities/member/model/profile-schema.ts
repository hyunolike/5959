import { z } from "zod";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "./types";

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

/** 닉네임 확인 API가 준 사유를 보여 줄 말. 온보딩과 프로필 수정이 같은 안내를 쓴다(004 US5-AC2). */
export const NICKNAME_REASON_LABEL: Record<"INVALID_FORMAT" | "TAKEN", string> =
  {
    INVALID_FORMAT: "한글, 영문, 숫자로 1~10자를 입력하세요.",
    TAKEN: "이미 사용 중인 닉네임입니다.",
  };

/**
 * 닉네임, 직군, 경력의 입력 규칙. 온보딩(M1)과 프로필 수정(004 US5)이 같은 스키마를 써서 같은 입력이
 * 같은 결과를 낸다.
 */
export const memberProfileSchema = z.object({
  nickname: z
    .string()
    .trim()
    .regex(NICKNAME_PATTERN, NICKNAME_REASON_LABEL.INVALID_FORMAT),
  jobRole: z.enum(JOB_ROLE_VALUES, { message: "직군을 선택하세요." }),
  careerYear: z.enum(CAREER_YEAR_VALUES, { message: "경력을 선택하세요." }),
});

export type MemberProfileFormValues = z.infer<typeof memberProfileSchema>;
