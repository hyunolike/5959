"use client";

import { useApiHealthQuery } from "../api/use-api-health-query";
import { ServiceStatusView } from "./service-status-view";

export function ServiceStatus() {
  const { data, isPending } = useApiHealthQuery();
  return (
    <ServiceStatusView
      status={isPending ? "LOADING" : (data?.status ?? "DOWN")}
    />
  );
}
