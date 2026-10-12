import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      className={cn(
        "focus-visible:border-brand-500 focus-visible:ring-brand-100 h-12 w-full rounded-xl border border-neutral-200 bg-neutral-50/60 px-4 text-sm text-neutral-900 transition-[border-color,box-shadow] placeholder:text-neutral-400 focus-visible:bg-white focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}
