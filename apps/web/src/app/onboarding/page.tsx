import type { Metadata } from "next";
import Link from "next/link";
import { OnboardingForm } from "@/features/onboarding";
import { AuthWelcome } from "@/widgets/auth-welcome";

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export const metadata: Metadata = { title: "프로필 입력" };

export default async function OnboardingPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { next } = await searchParams;

  return (
    <AuthWelcome>
      <div className="mx-auto w-full max-w-sm">
        <h1 className="text-heading text-fg font-bold">프로필을 알려주세요</h1>
        <p className="text-body-sm text-fg-subtle mt-1">
          닉네임과 직군, 경력을 입력하면 서비스를 쓸 준비가 끝나요.
        </p>
        <div className="mt-6">
          <OnboardingForm next={firstValue(next)} />
        </div>
        {/* 가입만 하고 그만두는 길(013 US1-AC9). 마이페이지는 온보딩을 마쳐야 열린다. */}
        <p className="text-body-sm text-fg-subtle mt-6 text-center">
          가입을 그만두려면{" "}
          <Link
            href="/withdraw"
            className="text-fg-muted inline-flex min-h-11 items-center px-1 font-bold underline underline-offset-2"
          >
            계정 지우기
          </Link>
        </p>
      </div>
    </AuthWelcome>
  );
}
