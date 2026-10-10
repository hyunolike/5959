import type { components } from "@/shared/api";

export type WeeklyReport = components["schemas"]["WeeklyReport"];
export type WeeklyReportSummary = components["schemas"]["WeeklyReportSummary"];
export type WeeklyReportPage = components["schemas"]["WeeklyReportPage"];
export type WeeklyLetterStatus = components["schemas"]["WeeklyLetterStatus"];

/** 리포트 화면의 주소. 알림과 마이페이지 목록이 같은 곳으로 보낸다. */
export function weeklyReportHref(weekStart: string): string {
  return `/report/${weekStart}`;
}

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

function monthDay(date: string): string {
  const match = DATE.exec(date);
  return match ? `${Number(match[2])}월 ${Number(match[3])}일` : date;
}

/**
 * 한 주의 기간을 "10월 5일 ~ 10월 11일"로 쓴다. 서버가 한국 시간으로 자른 날짜 문자열을 그대로 읽는다.
 * `Date`로 바꾸지 않아 브라우저의 시간대가 달라도 날짜가 밀리지 않는다.
 */
export function formatWeek(weekStart: string, weekEnd: string): string {
  return `${monthDay(weekStart)} ~ ${monthDay(weekEnd)}`;
}
