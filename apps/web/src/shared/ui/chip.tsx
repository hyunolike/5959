import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 필터와 선택에 쓰는 알약 모양 버튼. 고르지 않은 것은 옅은 채움, 고른 것은 잉크 채움이다.
 * 보이는 높이는 40px이고 `::before`로 누르는 영역을 44px로 맞춘다. `::before`는 테두리 안쪽(38px)을 기준으로
 * 놓이므로 위아래 3px씩 넓힌다. 투명한 테두리는 채움이 사라지는 고대비 모드에서 보인다.
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
        "ogu-pressable bg-surface-sunken text-body-sm text-fg-muted hover:bg-line aria-pressed:bg-action aria-pressed:text-on-action aria-pressed:hover:bg-action-hover relative inline-flex h-10 shrink-0 items-center rounded-full border border-transparent px-4 font-medium before:absolute before:inset-x-0 before:-inset-y-[3px] before:content-[''] aria-pressed:font-bold",
        className,
      )}
      {...props}
    />
  );
}
