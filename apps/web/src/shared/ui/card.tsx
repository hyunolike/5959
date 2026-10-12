import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Card({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      className={cn(
        "rounded-surface border-line bg-surface border p-5 sm:p-6",
        className,
      )}
      {...props}
    />
  );
}
