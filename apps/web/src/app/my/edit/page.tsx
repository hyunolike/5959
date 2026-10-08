"use client";

import { useMeQuery } from "@/entities/member";
import { ProfileForm } from "@/features/edit-profile";
import { Card, Spinner } from "@/shared/ui";

/**
 * 프로필 수정(004 US5). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(`/my` 이하).
 * 폼은 지금 프로필로 채워야 하므로 내 정보를 받은 뒤에 그린다.
 */
export default function EditProfilePage() {
  const { data: member, isPending, isError } = useMeQuery();

  return (
    <div className="flex w-full max-w-sm flex-col gap-6">
      <h1 className="text-xl font-semibold text-neutral-900">프로필 수정</h1>
      <Card className="w-full">
        {isPending ? (
          <div className="flex justify-center py-4">
            <Spinner />
          </div>
        ) : isError || !member ? (
          <p role="alert" className="text-sm text-red-600">
            프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        ) : (
          <ProfileForm member={member} />
        )}
      </Card>
    </div>
  );
}
