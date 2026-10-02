import { LoginForm } from "@/features/auth/email-login";
import {
  OAuthButtons,
  OAuthLoginErrorNotice,
} from "@/features/auth/oauth-buttons";
import { Card } from "@/shared/ui";

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
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">로그인</h1>
      <p className="mt-1 text-sm text-neutral-500">오구오구에서 다시 만나요.</p>
      <div className="mt-4">
        <OAuthLoginErrorNotice error={error} />
      </div>
      <div className="mt-6">
        <OAuthButtons next={next} />
      </div>
      <div className="mt-6">
        <LoginForm next={next} />
      </div>
    </Card>
  );
}
