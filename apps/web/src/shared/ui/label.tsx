import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Label({ className, ...props }: ComponentProps<"label">) {
  return (
    <label
      className={cn("text-sm font-medium text-neutral-700", className)}
      {...props}
    />
  );
}
