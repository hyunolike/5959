"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { ApiError, requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { WeeklyReport, WeeklyReportPage } from "../model/types";

/** 편지를 쓰는 동안 처음 30초는 3초마다 다시 받는다. 첫 시도는 대개 몇 초 안에 끝난다. */
export const LETTER_POLL_FAST_MS = 3_000;
/** 그 뒤에는 재시도(30초부터)를 따라 15초마다 받는다. 써지면 30초 안에 보인다(008 US2-AC3). */
export const LETTER_POLL_SLOW_MS = 15_000;
export const LETTER_POLL_SLOWDOWN_AFTER_MS = 30_000;
/** 10분이 지나면 그만 받는다. 그 뒤에 써진 편지는 화면을 다시 열면 보인다. */
export const LETTER_POLL_LIMIT_MS = 10 * 60 * 1_000;

/** 다음 폴링까지의 간격. `false`면 멈춘다. 편지를 쓰는 중(`PENDING`)일 때만 다시 받는다. */
export function letterPollInterval(
  report: WeeklyReport | undefined,
  elapsedMs: number,
): number | false {
  if (report?.letterStatus !== "PENDING" || elapsedMs >= LETTER_POLL_LIMIT_MS) {
    return false;
  }
  return elapsedMs < LETTER_POLL_SLOWDOWN_AFTER_MS
    ? LETTER_POLL_FAST_MS
    : LETTER_POLL_SLOW_MS;
}

/** 같은 출처 BFF 프록시를 거쳐 한 주의 내 리포트를 가져온다. 없는 주는 404를 `ApiError`로 던진다. */
export function fetchWeeklyReport(
  weekStart: string,
  fetchImpl: typeof fetch = fetch,
): Promise<WeeklyReport> {
  return requestApi<WeeklyReport>(
    `/api/members/me/weekly-reports/${encodeURIComponent(weekStart)}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/**
 * 한 주의 리포트(008 US1, US2). 편지를 쓰는 중이면 [letterPollInterval] 간격으로 다시 받아 새로고침 없이
 * 편지를 채운다. 없는 리포트(404)는 다시 시도하지 않는다.
 */
export function useWeeklyReportQuery(weekStart: string) {
  const [startedAt] = useState(() => Date.now());

  return useQuery({
    queryKey: QUERY_KEYS.weeklyReport(weekStart),
    queryFn: () => fetchWeeklyReport(weekStart),
    retry: (failureCount, error) => !isNotFound(error) && failureCount < 2,
    refetchInterval: (query) =>
      query.state.error
        ? false
        : letterPollInterval(query.state.data, Date.now() - startedAt),
  });
}

/** 같은 출처 BFF 프록시를 거쳐 내 리포트 목록 한 쪽을 가져온다. 최신 주부터다. */
export function fetchWeeklyReportsPage(
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<WeeklyReportPage> {
  const query = cursor ? `?${new URLSearchParams({ cursor })}` : "";
  return requestApi<WeeklyReportPage>(
    `/api/members/me/weekly-reports${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/** 마이페이지 "주간 리포트"(008 US4-AC1). 리포트는 월요일마다 늘어나므로 탭을 열 때마다 다시 받는다. */
export function useWeeklyReportsQuery() {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.weeklyReports,
    queryFn: ({ pageParam }) => fetchWeeklyReportsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
  });
}
