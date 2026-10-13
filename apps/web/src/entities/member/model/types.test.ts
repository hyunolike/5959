import { describe, expect, it } from "vitest";

import {
  AI_FRIEND_LABEL,
  authorMetaLabel,
  CAREER_YEAR_LABELS,
  type CareerYear,
  JOB_ROLE_LABELS,
  type JobRole,
} from "./types";

const ALL_JOB_ROLES: JobRole[] = [
  "PLANNING",
  "DESIGN",
  "DEVELOPMENT",
  "MARKETING",
  "SALES",
  "HR",
  "GENERAL_AFFAIRS",
  "PRODUCTION",
  "ACCOUNTING",
  "OTHER",
];

const ALL_CAREER_YEARS: CareerYear[] = [
  "NEWCOMER",
  "YEAR_1",
  "YEAR_2",
  "YEAR_3",
  "YEAR_4",
  "YEAR_5",
  "YEAR_6",
  "YEAR_7_PLUS",
];

describe("JOB_ROLE_LABELS", () => {
  it.each(ALL_JOB_ROLES)("%s에 한국어 라벨이 있다", (role) => {
    expect(JOB_ROLE_LABELS[role]).toEqual(expect.any(String));
    expect(JOB_ROLE_LABELS[role].length).toBeGreaterThan(0);
  });
});

describe("CAREER_YEAR_LABELS", () => {
  it.each(ALL_CAREER_YEARS)("%s에 한국어 라벨이 있다", (year) => {
    expect(CAREER_YEAR_LABELS[year]).toEqual(expect.any(String));
    expect(CAREER_YEAR_LABELS[year].length).toBeGreaterThan(0);
  });
});

describe("authorMetaLabel", () => {
  it("012 US4-AC1 사람 회원의 글은 글을 쓸 때의 직군과 경력을 한국어로 보인다", () => {
    expect(
      authorMetaLabel({
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
        ai: false,
      }),
    ).toBe("개발 · 1년차");
  });

  it("012 US4-AC1 AI 친구의 글은 직군과 경력 자리에 AI 친구라고 보인다", () => {
    expect(authorMetaLabel({ jobRole: null, careerYear: null, ai: true })).toBe(
      "AI 친구",
    );
    expect(AI_FRIEND_LABEL).toBe("AI 친구");
  });

  it("012 FR-029 직군이나 경력이 없으면 꾸며 붙이지 않고 AI 친구라고 보인다", () => {
    expect(
      authorMetaLabel({ jobRole: null, careerYear: "YEAR_1", ai: false }),
    ).toBe("AI 친구");
    expect(
      authorMetaLabel({ jobRole: "HR", careerYear: null, ai: false }),
    ).toBe("AI 친구");
  });
});
