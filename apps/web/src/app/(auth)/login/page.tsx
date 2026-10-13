import Link from "next/link";

import { LoginForm } from "@/features/auth/email-login";
import {
  OAuthButtons,
  OAuthLoginErrorNotice,
} from "@/features/auth/oauth-buttons";
import { env } from "@/shared/config";
import { withNextPath } from "@/shared/lib";
import { AuthWelcome } from "@/widgets/auth-welcome";

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { error, next: nextParam } = await searchParams;
  const next = firstValue(nextParam);

  return (
    <AuthWelcome>
      <div className="mx-auto w-full max-w-sm">
        <h1 className="text-heading text-fg font-bold">로그인</h1>
        <p className="text-body-sm text-fg-subtle mt-1">
          오구오구에 다시 오신 걸 환영해요.
        </p>
        <div className="mt-4">
          <OAuthLoginErrorNotice error={error} />
        </div>
        <div className="mt-6">
          <OAuthButtons next={next} kakaoEnabled={env.KAKAO_LOGIN_ENABLED} />
        </div>
        <div className="text-caption text-fg-subtle my-6 flex items-center gap-3">
          <span className="bg-line h-px flex-1" />
          이메일로 로그인
          <span className="bg-line h-px flex-1" />
        </div>
        <div>
          <LoginForm next={next} />
        </div>
        <div className="border-line mt-6 flex flex-col items-center border-t pt-4">
          <p className="text-body-sm text-fg-subtle text-center">
            아직 계정이 없나요?{" "}
            <Link
              className="text-fg ml-1 inline-flex min-h-11 items-center font-bold hover:underline"
              href={withNextPath("/signup", next)}
            >
              회원가입
            </Link>
          </p>
          <Link
            href="/home"
            className="text-body-sm text-fg-subtle hover:text-fg inline-flex min-h-11 items-center font-medium underline underline-offset-4"
          >
            먼저 둘러보기
          </Link>
        </div>
      </div>
    </AuthWelcome>
  );
}
