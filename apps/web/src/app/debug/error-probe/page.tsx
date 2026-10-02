import { notFound } from "next/navigation";

import { env } from "@/shared/config";
import { Card } from "@/shared/ui";

import { ErrorProbeButton } from "./error-probe-button";

/**
 * 오류 수집(Sentry) 연결을 눈으로 확인하기 위한 디버그 전용 페이지.
 * `ENABLE_ERROR_PROBE=1`일 때만 존재하고, 그 밖에는 404다. 로그인 여부와
 * 상관없이 공개 경로다(보호 경로 접두사에 없어 route-guard를 그대로 통과한다).
 */
export default function ErrorProbePage() {
  if (env.ENABLE_ERROR_PROBE !== true) {
    notFound();
  }

  return (
    <Card className="w-full max-w-sm text-center">
      <h1 className="text-xl font-semibold text-neutral-900">
        오류 수집 테스트
      </h1>
      <p className="mt-1 text-sm text-neutral-500">
        버튼을 누르면 처리되지 않은 오류가 발생합니다.
      </p>
      <div className="mt-6">
        <ErrorProbeButton />
      </div>
    </Card>
  );
}
