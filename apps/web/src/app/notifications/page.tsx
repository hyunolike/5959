import Link from "next/link";

import { Button } from "@/shared/ui";
import { NotificationList } from "@/widgets/notification-list";

/**
 * 알림 목록(004 US2). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(FR-014).
 * 실시간 연결은 레이아웃의 알림 종이 열어 두고, 새 알림은 이 목록 맨 위에 바로 나타난다.
 */
export default function NotificationsPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold text-neutral-900">알림</h1>
        <Button asChild variant="ghost" size="sm">
          <Link href="/home">피드로</Link>
        </Button>
      </header>
      <NotificationList />
    </div>
  );
}
