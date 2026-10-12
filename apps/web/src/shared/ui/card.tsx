import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Card({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      className={cn(
        "rounded-surface shadow-surface border border-neutral-200 bg-white p-5 sm:p-6",
        className,
      )}
      {...props}
    />
  );
}
