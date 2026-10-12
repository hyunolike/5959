import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

const buttonVariants = cva(
  "ogu-pressable inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-control text-sm font-bold disabled:cursor-not-allowed disabled:bg-neutral-100 disabled:text-neutral-500 disabled:shadow-none aria-disabled:cursor-not-allowed aria-disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-600 focus-visible:ring-offset-2",
  {
    variants: {
      variant: {
        primary:
          "bg-brand-600 text-white hover:bg-brand-650 active:bg-brand-700",
        secondary:
          "border border-brand-200 bg-brand-100 text-brand-700 hover:bg-brand-200 active:bg-brand-200",
        outline:
          "border border-neutral-300 bg-white text-neutral-800 hover:border-brand-600 hover:bg-brand-50 active:bg-brand-100",
        ghost: "text-neutral-700 hover:bg-neutral-100 active:bg-neutral-200",
        destructive: "bg-red-600 text-white hover:bg-red-700 active:bg-red-800",
      },
      size: {
        sm: "min-h-11 px-3",
        md: "min-h-11 px-4",
        lg: "min-h-12 px-6",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "md",
    },
  },
);

interface ButtonProps
  extends ComponentProps<"button">, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({
  className,
  variant,
  size,
  asChild,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot : "button";
  return (
    <Comp
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  );
}
