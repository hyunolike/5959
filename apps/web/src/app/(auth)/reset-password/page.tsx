import type { Metadata } from "next";

import {
  InvalidLinkNotice,
  ResetPasswordForm,
} from "@/features/auth/password-reset";
import { AuthWelcome } from "@/widgets/auth-welcome";

/**
 * 주소의 검색어에 링크의 값이 있다. 이 화면에서 다른 곳으로 가는 요청에 주소가 실리지 않게 한다(015 research R7).
 */
export const metadata: Metadata = {
  title: "새 비밀번호",
  referrer: "no-referrer",
  robots: { index: false },
};

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/**
 * 메일의 링크로 여는 새 비밀번호 화면(015 US2). 로그인하지 않고 연다. 링크가 쓸 수 있는지 미리 묻지 않는다.
 * 미리 묻는 조회는 값을 떠보는 길이 된다. 저장할 때 쓸 수 없으면 다시 요청하는 길을 보인다.
 */
export default async function ResetPasswordPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const token = firstValue((await searchParams).token)?.trim();

  return (
    <AuthWelcome>
      <div className="mx-auto w-full max-w-sm">
        <h1 className="text-heading text-fg font-bold">새 비밀번호 정하기</h1>
        <div className="mt-6">
          {token ? <ResetPasswordForm token={token} /> : <InvalidLinkNotice />}
        </div>
      </div>
    </AuthWelcome>
  );
}
