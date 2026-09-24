import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Card({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      className={cn(
        "rounded-lg border border-neutral-200 bg-white p-6 shadow-sm",
        className,
      )}
      {...props}
    />
  );
}
