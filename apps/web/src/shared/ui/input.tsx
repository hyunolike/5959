import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

export function Input({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      className={cn(
        "focus-visible:border-brand-600 focus-visible:ring-brand-100 rounded-control h-12 w-full border border-neutral-300 bg-white px-4 text-base text-neutral-900 transition-[border-color,box-shadow] duration-150 placeholder:text-neutral-500 hover:border-neutral-600 focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-neutral-100 disabled:text-neutral-500 aria-invalid:border-red-600 aria-invalid:bg-red-50/30",
        className,
      )}
      {...props}
    />
  );
}
