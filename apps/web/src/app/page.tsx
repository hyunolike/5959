import { Card } from "@/shared/ui";

export default function HomePage() {
  return (
    <Card className="w-full max-w-xl text-center">
      <h1 className="text-2xl font-semibold text-neutral-900">오구오구</h1>
      <p className="mt-2 text-sm text-neutral-500">
        감정을 나누고 함께 이겨내는 서비스
      </p>
    </Card>
  );
}
