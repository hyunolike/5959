import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/** 기기의 선택 상자를 그대로 쓰고 모양만 입력과 맞춘다(56px). */
export function Select({ className, ...props }: ComponentProps<"select">) {
  return (
    <select
      className={cn(
        "rounded-control border-line-strong bg-surface text-body text-fg hover:border-fg-muted focus-visible:border-fg disabled:bg-surface-sunken disabled:text-fg-subtle aria-invalid:border-danger aria-invalid:bg-danger-tint h-14 w-full border px-4 disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
