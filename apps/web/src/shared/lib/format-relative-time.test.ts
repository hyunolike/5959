import { describe, expect, it } from "vitest";

import { formatRelativeTime } from "./format-relative-time";

const NOW = "2026-10-13T12:00:00+09:00";

describe("formatRelativeTime", () => {
  it.each([
    ["2026-10-13T11:59:30+09:00", "방금"],
    ["2026-10-13T11:59:00+09:00", "1분 전"],
    ["2026-10-13T11:01:00+09:00", "59분 전"],
    ["2026-10-13T11:00:00+09:00", "1시간 전"],
    ["2026-10-12T12:01:00+09:00", "23시간 전"],
    ["2026-10-12T12:00:00+09:00", "1일 전"],
    ["2026-10-06T12:01:00+09:00", "6일 전"],
  ])("DS-US3-AC1 %s는 %s로 쓴다", (date, expected) => {
    expect(formatRelativeTime(date, NOW)).toBe(expected);
  });

  it("DS-US3-AC1 일주일이 지나면 날짜로 쓴다", () => {
    expect(formatRelativeTime("2026-10-06T03:00:00Z", NOW)).toMatch(
      /^2026\.10\.06$/,
    );
  });

  it("시계가 어긋나 미래인 시각은 방금으로 쓴다", () => {
    expect(formatRelativeTime("2026-10-13T12:03:00+09:00", NOW)).toBe("방금");
  });

  it("읽을 수 없는 값은 빈 문자열이다", () => {
    expect(formatRelativeTime("not-a-date", NOW)).toBe("");
  });
});
