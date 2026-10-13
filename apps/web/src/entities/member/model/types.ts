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

/** AI 친구의 글에서 직군과 경력 자리에 보이는 말(012 US4-AC1). 색이나 그림이 아니라 글자로 알린다. */
export const AI_FRIEND_LABEL = "AI 친구";

/**
 * 글쓴이의 이름 옆에 보이는 한 줄. 사람 회원은 글을 쓸 때의 직군과 경력이고, AI 친구는 "AI 친구"다(012 FR-029).
 * AI 친구에게는 직군과 경력이 없다. 꾸며 붙이지 않는다.
 */
export function authorMetaLabel(author: {
  jobRole: JobRole | null;
  careerYear: CareerYear | null;
  ai: boolean;
}): string {
  if (author.ai || author.jobRole === null || author.careerYear === null) {
    return AI_FRIEND_LABEL;
  }
  return `${JOB_ROLE_LABELS[author.jobRole]} · ${CAREER_YEAR_LABELS[author.careerYear]}`;
}
