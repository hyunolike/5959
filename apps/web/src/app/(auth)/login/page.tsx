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
      <section className="mx-auto w-full max-w-sm">
        <h1 className="text-2xl font-bold text-neutral-900">로그인</h1>
        <p className="mt-2 text-sm text-neutral-500">
          오구오구에 다시 오신 걸 환영해요.
        </p>
        <div className="mt-4">
          <OAuthLoginErrorNotice error={error} />
        </div>
        <div className="mt-6">
          <OAuthButtons next={next} kakaoEnabled={env.KAKAO_LOGIN_ENABLED} />
        </div>
        <div className="my-6 flex items-center gap-3 text-xs text-neutral-500">
          <span className="h-px flex-1 bg-neutral-200" />
          이메일로 로그인
          <span className="h-px flex-1 bg-neutral-200" />
        </div>
        <div>
          <LoginForm next={next} />
        </div>
        <p className="mt-7 text-center text-sm text-neutral-500">
          아직 계정이 없나요?{" "}
          <Link
            className="text-brand-700 ml-1 font-bold hover:underline"
            href={withNextPath("/signup", next)}
          >
            회원가입
          </Link>
        </p>
      </section>
    </AuthWelcome>
  );
}
