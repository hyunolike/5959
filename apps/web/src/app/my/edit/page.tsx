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
    <div className="flex w-full max-w-md flex-col gap-5">
      <header>
        <p className="text-label text-fg-subtle mb-1 font-bold">내 마음 기록</p>
        <h1 className="text-heading text-fg font-bold">프로필 수정</h1>
        <p className="text-body-sm text-fg-subtle mt-1 leading-6">
          닉네임, 직군, 경력은 내가 쓴 글에 함께 보여요.
        </p>
      </header>
      <Card className="w-full">
        {isPending ? (
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        ) : isError || !member ? (
          <p role="alert" className="text-body-sm text-danger">
            프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        ) : (
          <ProfileForm member={member} />
        )}
      </Card>
    </div>
  );
}
