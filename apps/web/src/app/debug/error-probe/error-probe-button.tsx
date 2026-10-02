"use client";

import { Button } from "@/shared/ui";

/**
 * `ENABLE_ERROR_PROBE=1`일 때만 렌더링되는 디버그 전용 버튼. 눌리면 처리되지
 * 않은 오류를 던져서, Sentry `beforeSend`/`beforeBreadcrumb` 스크러빙이
 * 실제 배포 환경에서도 잘 동작하는지 수동으로 확인할 수 있게 한다.
 */
export function ErrorProbeButton() {
  return (
    <Button
      variant="destructive"
      onClick={() => {
        throw new Error("오구오구 오류 수집 테스트용 오류(error-probe)");
      }}
    >
      오류 던지기
    </Button>
  );
}
