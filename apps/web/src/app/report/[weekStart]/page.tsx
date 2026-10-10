import Link from "next/link";

import { Button } from "@/shared/ui";
import { WeeklyReport } from "@/widgets/weekly-report";

/**
 * 한 주의 리포트(008). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다.
 * 주소의 날짜가 틀렸거나 그 주의 내 리포트가 없으면 위젯이 없는 리포트 안내를 보인다(US1-AC9).
 */
export default async function WeeklyReportPage({
  params,
}: {
  params: Promise<{ weekStart: string }>;
}) {
  const { weekStart } = await params;

  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold text-neutral-900">주간 리포트</h1>
        <Button asChild variant="ghost" size="sm">
          <Link href="/my?tab=reports">지난 리포트</Link>
        </Button>
      </header>
      <WeeklyReport weekStart={decodeURIComponent(weekStart)} />
    </div>
  );
}
