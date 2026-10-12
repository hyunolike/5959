import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

const buttonVariants = cva(
  "ogu-pressable rounded-control text-body-sm disabled:bg-surface-sunken disabled:text-fg-subtle inline-flex items-center justify-center gap-2 text-center font-bold disabled:cursor-not-allowed disabled:border-transparent aria-disabled:cursor-not-allowed aria-disabled:opacity-60",
  {
    variants: {
      variant: {
        primary: "bg-action text-on-action hover:bg-action-hover",
        secondary:
          "border-line-strong bg-surface text-fg hover:bg-surface-sunken border",
        subtle: "bg-surface-sunken text-fg hover:bg-line",
        ghost: "text-fg-muted hover:bg-surface-sunken",
        danger: "bg-danger text-on-danger hover:opacity-90",
      },
      size: {
        sm: "min-h-11 px-3",
        md: "min-h-11 px-4",
        lg: "text-body min-h-13 px-6",
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
