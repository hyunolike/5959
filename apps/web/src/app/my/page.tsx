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
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-40">
          <p className="mb-1 text-xs font-bold text-neutral-600">
            내 마음 기록
          </p>
          <h1 className="text-2xl font-bold text-neutral-900">마이페이지</h1>
        </div>
        <div className="ml-auto flex items-center gap-2">
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
  const { data: member, isPending, isError } = useMeQuery();

  if (isPending) {
    return (
      <Card className="flex w-full items-center justify-center">
        <Spinner />
      </Card>
    );
  }

  if (isError || !member) {
    return (
      <Card className="w-full">
        <p role="alert" className="text-sm text-red-600">
          프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  return (
    <Card className="flex w-full flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-neutral-200 pb-4">
        <p className="min-w-0 text-base font-bold break-words text-neutral-900">
          {member.nickname}님의 마음 기록
        </p>
        <Button asChild variant="secondary" size="sm" className="ml-auto">
          <Link href="/my/edit">프로필 수정</Link>
        </Button>
      </div>
      <dl className="flex flex-col gap-3 text-sm">
        <div className="flex items-center justify-between gap-4">
          <dt className="shrink-0 text-neutral-600">닉네임</dt>
          <dd className="min-w-0 font-medium break-words text-neutral-900">
            {member.nickname}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt className="shrink-0 text-neutral-600">직군</dt>
          <dd className="font-medium text-neutral-900">
            {member.jobRole ? JOB_ROLE_LABELS[member.jobRole] : "-"}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt className="shrink-0 text-neutral-600">경력</dt>
          <dd className="font-medium text-neutral-900">
            {member.careerYear ? CAREER_YEAR_LABELS[member.careerYear] : "-"}
          </dd>
        </div>
      </dl>
    </Card>
  );
}
