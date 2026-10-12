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
      <Card className="w-full px-6 py-8 sm:p-9">
        <h1 className="text-2xl font-bold text-neutral-900">
          이메일로 가입하기
        </h1>
        <p className="mt-1 text-sm text-neutral-500">
          오구오구에서 감정을 나누고 함께 이겨내 보세요.
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
        <p className="mt-7 text-center text-sm text-neutral-500">
          이미 계정이 있나요?{" "}
          <Link
            className="text-brand-700 ml-1 font-bold hover:underline"
            href={withNextPath("/login", next)}
          >
            로그인
          </Link>
        </p>
      </Card>
    </AuthWelcome>
  );
}
