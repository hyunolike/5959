import Link from "next/link";

import { SignupForm } from "@/features/auth/email-signup";
import { OAuthButtons } from "@/features/auth/oauth-buttons";
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
      <div className="mx-auto w-full max-w-sm">
        <h1 className="text-heading text-fg font-bold">이메일로 가입하기</h1>
        <p className="text-body-sm text-fg-subtle mt-1">
          가입하면 회원들의 고민을 읽고, 내 고민도 나눌 수 있어요.
        </p>
        <div className="mt-6">
          <OAuthButtons next={next} kakaoEnabled={env.KAKAO_LOGIN_ENABLED} />
        </div>
        <div className="text-caption text-fg-subtle my-6 flex items-center gap-3">
          <span className="bg-line h-px flex-1" />
          이메일로 가입
          <span className="bg-line h-px flex-1" />
        </div>
        <div>
          <SignupForm next={next} />
        </div>
        <div className="border-line mt-6 flex flex-col items-center border-t pt-4">
          <p className="text-body-sm text-fg-subtle text-center">
            이미 계정이 있나요?{" "}
            <Link
              className="text-fg ml-1 inline-flex min-h-11 items-center font-bold hover:underline"
              href={withNextPath("/login", next)}
            >
              로그인
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
