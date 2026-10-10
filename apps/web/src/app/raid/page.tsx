import Link from "next/link";

import { Button } from "@/shared/ui";
import { RaidArena } from "@/widgets/raid-arena";

/**
 * 보스 레이드(006). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(US1-AC7).
 * 실시간 연결은 레이아웃의 알림 종이 열어 두고, 이 화면이 보이는 동안 레이드 소식을 함께 받는다.
 */
export default function RaidPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold text-neutral-900">레이드</h1>
        <Button asChild variant="ghost" size="sm">
          <Link href="/home">피드로</Link>
        </Button>
      </header>
      <RaidArena />
    </div>
  );
}
