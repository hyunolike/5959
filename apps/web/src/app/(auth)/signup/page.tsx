import { SignupForm } from "@/features/auth/email-signup";
import { OAuthButtons } from "@/features/auth/oauth-buttons";
import { Card } from "@/shared/ui";

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function SignupPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { next } = await searchParams;

  return (
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">
        이메일로 가입하기
      </h1>
      <p className="mt-1 text-sm text-neutral-500">
        오구오구에서 감정을 나누고 함께 이겨내 보세요.
      </p>
      <div className="mt-6">
        <OAuthButtons next={firstValue(next)} />
      </div>
      <div className="mt-6">
        <SignupForm />
      </div>
    </Card>
  );
}
