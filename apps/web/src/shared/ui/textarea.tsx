import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return (
    <textarea
      className={cn(
        "rounded-control border-line-strong bg-surface text-body text-fg placeholder:text-fg-subtle hover:border-fg-muted disabled:bg-surface-sunken disabled:text-fg-subtle aria-invalid:border-danger aria-invalid:bg-danger-tint min-h-32 w-full resize-y border px-4 py-3 disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
