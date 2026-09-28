import type { components } from "@/shared/api";

export type MemberProfile = components["schemas"]["MemberProfile"];
export type JobRole = components["schemas"]["JobRole"];
export type CareerYear = components["schemas"]["CareerYear"];

/** 직군 한국어 라벨 (spec FR-009, data-model.md) */
export const JOB_ROLE_LABELS: Record<JobRole, string> = {
  PLANNING: "기획",
  DESIGN: "디자인",
  DEVELOPMENT: "개발",
  MARKETING: "마케팅",
  SALES: "영업",
  HR: "인사",
  GENERAL_AFFAIRS: "총무",
  PRODUCTION: "생산",
  ACCOUNTING: "회계",
  OTHER: "기타",
};

/** 경력 한국어 라벨 (spec FR-009, data-model.md) */
export const CAREER_YEAR_LABELS: Record<CareerYear, string> = {
  NEWCOMER: "신입",
  YEAR_1: "1년차",
  YEAR_2: "2년차",
  YEAR_3: "3년차",
  YEAR_4: "4년차",
  YEAR_5: "5년차",
  YEAR_6: "6년차",
  YEAR_7_PLUS: "7년차 이상",
};
