import { describe, expect, it } from "vitest";

import { formatWeek, weeklyReportHref } from "./types";

describe("formatWeek", () => {
  it("한 주의 기간을 월과 일로 쓴다", () => {
    expect(formatWeek("2026-10-05", "2026-10-11")).toBe("10월 5일 ~ 10월 11일");
  });

  it("달과 해가 바뀌는 주도 날짜 문자열 그대로 읽는다", () => {
    expect(formatWeek("2026-12-28", "2027-01-03")).toBe("12월 28일 ~ 1월 3일");
  });

  it("날짜가 아니면 받은 값을 그대로 둔다", () => {
    expect(formatWeek("어제", "오늘")).toBe("어제 ~ 오늘");
  });
});

describe("weeklyReportHref", () => {
  it("그 주의 리포트 화면 주소다", () => {
    expect(weeklyReportHref("2026-10-05")).toBe("/report/2026-10-05");
  });
});
