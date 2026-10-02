import { OnboardingForm } from "@/features/onboarding";
import { Card } from "@/shared/ui";

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function OnboardingPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { next } = await searchParams;

  return (
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">
        프로필을 알려주세요
      </h1>
      <p className="mt-1 text-sm text-neutral-500">
        닉네임과 직군, 경력을 입력하면 서비스를 쓸 준비가 끝나요.
      </p>
      <div className="mt-6">
        <OnboardingForm next={firstValue(next)} />
      </div>
    </Card>
  );
}
