import type { Metadata } from "next";
import Link from "next/link";

import { RequestResetForm } from "@/features/auth/password-reset";
import { fetchPasswordResetAvailable } from "../password-reset-availability";
import { AuthWelcome } from "@/widgets/auth-welcome";

export const metadata: Metadata = { title: "비밀번호 찾기" };

/**
 * 비밀번호 재설정 메일 요청(015 US1). 로그인하지 않고 연다. 메일 발송이 꺼져 있으면 폼 대신 지금은 쓸 수 없다는
 * 안내를 보인다(US4-AC2). 로그인 화면은 꺼져 있을 때 이곳으로 가는 길을 그리지 않지만, 주소를 직접 열 수는 있다.
 */
export default async function ForgotPasswordPage() {
  const available = await fetchPasswordResetAvailable();

  return (
    <AuthWelcome>
      <div className="mx-auto w-full max-w-sm">
        <h1 className="text-heading text-fg font-bold">비밀번호 찾기</h1>
        <p className="text-body-sm text-fg-subtle mt-1 break-keep">
          이메일로 가입했다면 비밀번호를 다시 정할 수 있는 링크를 메일로 보내
          드려요.
        </p>
        <div className="mt-6">
          {available ? (
            <RequestResetForm />
          ) : (
            <p role="status" className="text-body-sm text-fg break-keep">
              지금은 비밀번호 재설정을 쓸 수 없어요.
            </p>
          )}
        </div>
        <p className="text-body-sm text-fg-subtle mt-6 text-center">
          <Link
            href="/login"
            className="text-fg inline-flex min-h-11 items-center font-bold hover:underline"
          >
            로그인으로 돌아가기
          </Link>
        </p>
      </div>
    </AuthWelcome>
  );
}
