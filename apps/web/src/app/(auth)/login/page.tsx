import { LoginForm } from "@/features/auth/email-login";
import { Card } from "@/shared/ui";

export default function LoginPage() {
  return (
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">로그인</h1>
      <p className="mt-1 text-sm text-neutral-500">오구오구에서 다시 만나요.</p>
      <div className="mt-6">
        <LoginForm />
      </div>
    </Card>
  );
}
