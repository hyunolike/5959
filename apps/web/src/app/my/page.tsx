"use client";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  useMeQuery,
} from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { Card, Spinner } from "@/shared/ui";

/** FR-015: 로그인한 사용자는 자신의 닉네임, 직군, 경력을 볼 수 있다. */
export default function MyPage() {
  const { data: member, isPending } = useMeQuery();

  if (isPending || !member) {
    return (
      <Card className="flex w-full max-w-sm items-center justify-center">
        <Spinner />
      </Card>
    );
  }

  return (
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">마이페이지</h1>
      <dl className="mt-6 flex flex-col gap-4 text-sm">
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
      <div className="mt-6">
        <LogoutButton />
      </div>
    </Card>
  );
}
