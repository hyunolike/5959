import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 필터와 선택에 쓰는 알약 모양 버튼. 보이는 높이는 36px이고,
 * `::before`로 위아래 4px씩 넓혀 누르는 영역을 44px로 맞춘다.
 */
export function Chip({
  pressed,
  className,
  type = "button",
  ...props
}: ComponentProps<"button"> & { pressed: boolean }) {
  return (
    <button
      type={type}
      aria-pressed={pressed}
      className={cn(
        "ogu-pressable border-line-strong bg-surface text-body-sm text-fg-muted aria-pressed:border-action aria-pressed:bg-action aria-pressed:text-on-action relative inline-flex h-9 shrink-0 items-center rounded-full border px-3.5 font-medium before:absolute before:inset-x-0 before:-inset-y-1 before:content-[''] aria-pressed:font-bold",
        className,
      )}
      {...props}
    />
  );
}
