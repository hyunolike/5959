import { describe, expect, it } from "vitest";

import {
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
