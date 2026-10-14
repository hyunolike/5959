"use client";

import { useMeQuery } from "@/entities/member";
import {
  SupportResourceList,
  useWithdrawalNoticeQuery,
} from "@/entities/support-resource";
import { WithdrawForm } from "@/features/withdraw";
import { Card, Spinner } from "@/shared/ui";

/**
 * 회원 탈퇴(013 US1). 로그인은 proxy.ts의 라우트 가드가 먼저 확인한다. 온보딩은 보지 않는다.
 * 가입만 하고 그만두는 회원도 이 화면으로 온다(US1-AC9).
 *
 * 최근에 위기로 판정된 글이나 댓글이 있던 회원에게는 도움받을 곳을 함께 보인다(US1-AC10).
 * 그 조회가 실패해도 탈퇴 화면은 그대로 그린다. 탈퇴를 막거나 늦추지 않는다(FR-019).
 */
export default function WithdrawPage() {
  const { data: member, isPending, isError } = useMeQuery();
  const onboarded = member?.onboarded === true;
  const { data: notice } = useWithdrawalNoticeQuery({ enabled: onboarded });

  return (
    <div className="flex w-full max-w-md flex-col gap-5">
      <header>
        <p className="text-label text-fg-subtle mb-1 font-bold">계정</p>
        <h1 className="text-heading text-fg font-bold">회원 탈퇴</h1>
        <p className="text-body-sm text-fg-subtle mt-1 leading-6 break-keep">
          탈퇴하기 전에 아래 내용을 확인해 주세요.
        </p>
      </header>

      {notice?.recentCrisis ? (
        <Card className="w-full">
          <h2 className="text-body text-fg font-bold">
            혼자 견디기 어려운 날이라면
          </h2>
          <p className="text-body-sm text-fg-muted mt-1 mb-3 leading-6 break-keep">
            탈퇴와 상관없이 언제든 이야기할 수 있는 곳이 있어요.
          </p>
          <SupportResourceList />
        </Card>
      ) : null}

      <Card className="w-full">
        {isPending ? (
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        ) : isError || !member ? (
          <p role="alert" className="text-body-sm text-danger">
            계정 정보를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        ) : (
          <WithdrawForm cancelHref={onboarded ? "/my/edit" : "/onboarding"} />
        )}
      </Card>
    </div>
  );
}
