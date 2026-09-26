import type { ApiHealthStatus } from "@/shared/api";
import { cn } from "@/shared/lib";

type Status = ApiHealthStatus | "LOADING";

const LABEL: Record<Status, string> = {
  LOADING: "서버 상태 확인 중",
  UP: "서버 정상",
  DOWN: "서버 점검 중",
};

const DOT: Record<Status, string> = {
  LOADING: "bg-neutral-300",
  UP: "bg-emerald-500",
  DOWN: "bg-amber-500",
};

export function ServiceStatusView({ status }: { status: Status }) {
  return (
    <p
      role="status"
      className="inline-flex items-center gap-2 text-sm text-neutral-600"
    >
      <span aria-hidden className={cn("size-2 rounded-full", DOT[status])} />
      {LABEL[status]}
    </p>
  );
}
