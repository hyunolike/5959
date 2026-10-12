import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      className={cn(
        "focus-visible:border-brand-600 focus-visible:ring-brand-100 h-12 w-full rounded-lg border border-neutral-300 bg-white px-4 text-sm text-neutral-900 transition-[border-color,box-shadow] placeholder:text-neutral-500 focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  );
}
