import type { ApiHealthStatus } from "@/shared/api";
import { cn } from "@/shared/lib";

type Status = ApiHealthStatus | "LOADING";

const LABEL: Record<Status, string> = {
  LOADING: "서버 상태 확인 중",
  UP: "서버 정상",
  DOWN: "서버 점검 중",
};

const DOT: Record<Status, string> = {
  LOADING: "bg-line-strong",
  UP: "bg-fg",
  DOWN: "bg-warning",
};

export function ServiceStatusView({ status }: { status: Status }) {
  return (
    <p
      role="status"
      className="text-body-sm text-fg-subtle inline-flex items-center gap-2"
    >
      <span aria-hidden className={cn("size-2 rounded-full", DOT[status])} />
      {LABEL[status]}
    </p>
  );
}
