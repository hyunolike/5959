import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Label({ className, ...props }: ComponentProps<"label">) {
  return (
    <label
      className={cn("text-label text-fg-muted font-medium", className)}
      {...props}
    />
  );
}
