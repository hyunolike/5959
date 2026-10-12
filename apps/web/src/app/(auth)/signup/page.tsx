import Link from "next/link";

import { SignupForm } from "@/features/auth/email-signup";
import { OAuthButtons } from "@/features/auth/oauth-buttons";
import { Card } from "@/shared/ui";
import { env } from "@/shared/config";
import { withNextPath } from "@/shared/lib";
import { AuthWelcome } from "@/widgets/auth-welcome";

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function SignupPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { next: nextParam } = await searchParams;
  const next = firstValue(nextParam);

  return (
    <AuthWelcome>
      <Card className="mx-auto w-full max-w-sm px-5 py-7 sm:p-9">
        <h1 className="text-2xl font-bold text-neutral-900">
          이메일로 가입하기
        </h1>
        <p className="mt-1 text-sm text-neutral-600">
          가입하면 회원들의 고민을 읽고, 내 고민도 나눌 수 있어요.
        </p>
        <div className="mt-6">
          <OAuthButtons next={next} kakaoEnabled={env.KAKAO_LOGIN_ENABLED} />
        </div>
        <div className="my-6 flex items-center gap-3 text-xs text-neutral-500">
          <span className="h-px flex-1 bg-neutral-200" />
          이메일로 가입
          <span className="h-px flex-1 bg-neutral-200" />
        </div>
        <div>
          <SignupForm next={next} />
        </div>
        <div className="mt-6 flex flex-col items-center border-t border-neutral-200 pt-4">
          <p className="text-center text-sm text-neutral-600">
            이미 계정이 있나요?{" "}
            <Link
              className="text-brand-700 ml-1 inline-flex min-h-11 items-center font-bold hover:underline"
              href={withNextPath("/login", next)}
            >
              로그인
            </Link>
          </p>
          <Link
            href="/home"
            className="inline-flex min-h-11 items-center text-sm font-medium text-neutral-600 underline underline-offset-4 hover:text-neutral-900"
          >
            먼저 둘러보기
          </Link>
        </div>
      </Card>
    </AuthWelcome>
  );
}
