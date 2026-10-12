import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      className={cn(
        "rounded-control border-line-strong bg-surface text-body text-fg placeholder:text-fg-subtle hover:border-fg-muted disabled:bg-surface-sunken disabled:text-fg-subtle aria-invalid:border-danger aria-invalid:bg-danger-tint h-13 w-full border px-4 disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
