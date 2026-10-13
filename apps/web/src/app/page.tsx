import { Card } from "@/shared/ui";
import { ServiceStatus } from "@/widgets/service-status";

export default function HomePage() {
  return (
    <Card className="w-full max-w-xl text-center">
      <h1 className="text-heading text-fg font-bold">오구오구</h1>
      <p className="text-body-sm text-fg-subtle mt-2">
        감정을 나누고 함께 이겨내는 서비스
      </p>
      <div className="mt-6">
        <ServiceStatus />
      </div>
    </Card>
  );
}
