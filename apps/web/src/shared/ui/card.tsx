import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Card({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      className={cn(
        "rounded-2xl border border-neutral-200/80 bg-white p-6 shadow-[0_3px_16px_#291c3804]",
        className,
      )}
      {...props}
    />
  );
}
