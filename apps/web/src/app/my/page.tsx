"use client";

import Link from "next/link";
import { Suspense } from "react";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  useMeQuery,
} from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { stopNotificationStream } from "@/features/notification-stream";
import { Button, Card, Spinner } from "@/shared/ui";
import { EmotionStatsPanel } from "@/widgets/emotion-stats-panel";
import { MyActivity } from "@/widgets/my-activity";

/**
 * 마이페이지: 내 프로필(FR-015), 감정 통계(004 US4), 내 활동 탭(004 US3). 로그인과 온보딩은 proxy.ts의 라우트 가드가
 * 먼저 확인한다.
 */
export default function MyPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold text-neutral-900">마이페이지</h1>
        <div className="flex items-center gap-2">
          <Button asChild variant="ghost" size="sm">
            <Link href="/home">피드로</Link>
          </Button>
          <LogoutButton onLoggedOut={stopNotificationStream} />
        </div>
      </header>
      <Profile />
      <EmotionStatsPanel />
      {/* 고른 탭을 주소 검색어에서 읽는다(useSearchParams). */}
      <Suspense
        fallback={
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        }
      >
        <MyActivity />
      </Suspense>
    </div>
  );
}

function Profile() {
  const { data: member, isPending } = useMeQuery();

  if (isPending || !member) {
    return (
      <Card className="flex w-full items-center justify-center">
        <Spinner />
      </Card>
    );
  }

  return (
    <Card className="w-full">
      <dl className="flex flex-col gap-4 text-sm">
        <div className="flex items-center justify-between">
          <dt className="text-neutral-500">닉네임</dt>
          <dd className="font-medium text-neutral-900">{member.nickname}</dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-neutral-500">직군</dt>
          <dd className="font-medium text-neutral-900">
            {member.jobRole ? JOB_ROLE_LABELS[member.jobRole] : "-"}
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-neutral-500">경력</dt>
          <dd className="font-medium text-neutral-900">
            {member.careerYear ? CAREER_YEAR_LABELS[member.careerYear] : "-"}
          </dd>
        </div>
      </dl>
    </Card>
  );
}
