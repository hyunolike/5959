import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 버튼은 채움의 무게로 구분한다(스펙 "공용 부품"). 주 버튼은 잉크, 보조 버튼은 옅은 채움이다.
 * 옅은 면 위처럼 채움이 묻히는 자리와 구글 로그인에는 테두리가 있는 `outline`을 쓴다.
 * 높이는 40, 48, 56px이다. 40px짜리는 `::before`로 누르는 영역을 44px로 넓힌다.
 */
const buttonVariants = cva(
  "ogu-pressable text-body-sm disabled:bg-surface-sunken disabled:text-fg-subtle inline-flex items-center justify-center gap-2 py-2 text-center font-bold disabled:cursor-not-allowed disabled:border-transparent aria-disabled:cursor-not-allowed aria-disabled:opacity-60 break-keep",
  {
    variants: {
      variant: {
        primary: "bg-action text-on-action hover:bg-action-hover",
        secondary: "bg-surface-sunken text-fg hover:bg-line",
        outline:
          "border-line-strong bg-surface text-fg hover:bg-surface-sunken border",
        ghost: "text-fg-muted hover:bg-surface-sunken",
        danger: "bg-danger text-on-danger hover:opacity-90",
      },
      size: {
        sm: "rounded-control-sm relative min-h-10 px-3.5 before:absolute before:inset-x-0 before:-inset-y-0.5 before:content-['']",
        md: "rounded-control min-h-12 px-5",
        lg: "rounded-control text-body min-h-14 px-6",
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
