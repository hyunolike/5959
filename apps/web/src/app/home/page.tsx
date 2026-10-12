"use client";

import Link from "next/link";
import { PencilLine, Sparkles } from "lucide-react";
import { Suspense } from "react";

import { useMeQuery } from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { stopNotificationStream } from "@/features/notification-stream";
import { Button, Spinner } from "@/shared/ui";
import { FeedList } from "@/widgets/feed-list";
import { BossBanner } from "@/widgets/raid-arena";

/**
 * 홈은 피드다(US2). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다
 * (온보딩 전이면 /onboarding으로 보낸다).
 */
export default function HomePage() {
  const { data: member, isError } = useMeQuery();

  return (
    <div className="flex w-full max-w-2xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <div>
          <p className="text-brand-700 mb-1 text-xs font-bold tracking-wider">
            OUR LITTLE COMMUNITY
          </p>
          <h1 className="text-2xl font-bold text-neutral-900">
            {member?.nickname ? `${member.nickname}님, 반가워요` : "피드"}
          </h1>
          {isError ? (
            <p role="alert" className="text-sm text-red-600">
              프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
            </p>
          ) : null}
        </div>
        <div className="flex items-center gap-2">
          <LogoutButton onLoggedOut={stopNotificationStream} />
        </div>
      </header>
      <div className="border-brand-100 bg-brand-50 rounded-2xl border p-5 sm:p-6">
        <div className="text-brand-700 mb-3 flex items-center gap-2 text-xs font-bold">
          <Sparkles size={16} aria-hidden />
          오늘의 마음을 들려주세요
        </div>
        <p className="text-lg font-bold text-neutral-900">
          마음에 담아둔 이야기가 있나요?
        </p>
        <p className="mt-2 text-sm leading-6 text-neutral-600">
          작은 걱정도 괜찮아요. 비슷한 마음을 가진 사람들이 함께할 거예요.
        </p>
        <Button asChild variant="outline" className="mt-5">
          <Link href="/write">
            <PencilLine size={17} aria-hidden />
            고민 쓰기
          </Link>
        </Button>
      </div>
      {/* 살아 있는 보스가 있으면 레이드로 가는 안내를 보인다(006 US5-AC7). */}
      <BossBanner />
      {/* 정렬과 필터를 주소 검색어에서 읽는다(useSearchParams). */}
      <Suspense
        fallback={
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        }
      >
        <FeedList />
      </Suspense>
    </div>
  );
}
