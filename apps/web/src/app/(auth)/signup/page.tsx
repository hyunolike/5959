import { SignupForm } from "@/features/auth/email-signup";
import { Card } from "@/shared/ui";

export default function SignupPage() {
  return (
    <Card className="w-full max-w-sm">
      <h1 className="text-xl font-semibold text-neutral-900">
        이메일로 가입하기
      </h1>
      <p className="mt-1 text-sm text-neutral-500">
        오구오구에서 감정을 나누고 함께 이겨내 보세요.
      </p>
      <div className="mt-6">
        <SignupForm />
      </div>
    </Card>
  );
}
