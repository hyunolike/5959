import Link from "next/link";

import { Card } from "@/shared/ui";

import {
  formatWeek,
  weeklyReportHref,
  type WeeklyReportSummary,
} from "../model/types";

/**
 * 리포트 목록의 한 줄(008 US4-AC1): 기간, 가장 많았던 감정, 쓴 글 수. 누르면 그 주의 리포트로 간다.
 * 감정의 이름은 몬스터 엔티티에 있어 위젯이 넘긴다. 분석된 글이 없던 주는 `topEmotionLabel`이 null이다.
 */
export function WeeklyReportItem({
  report,
  topEmotionLabel,
}: {
  report: WeeklyReportSummary;
  topEmotionLabel: string | null;
}) {
  return (
    <Link
      href={weeklyReportHref(report.weekStart)}
      className="block rounded-lg focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
    >
      <Card className="flex items-center justify-between gap-4 transition-colors hover:bg-neutral-50">
        <div className="flex flex-col gap-1">
          <p className="text-sm font-semibold text-neutral-900">
            {formatWeek(report.weekStart, report.weekEnd)}
          </p>
          <p className="text-xs text-neutral-600">
            {topEmotionLabel
              ? `가장 많았던 감정 ${topEmotionLabel}`
              : "감정 분석이 끝난 글이 없어요"}
          </p>
        </div>
        <p className="shrink-0 text-xs text-neutral-600 tabular-nums">
          글 {report.postCount}개
        </p>
      </Card>
    </Link>
  );
}
