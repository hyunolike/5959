import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

type IconButtonProps = Omit<ComponentProps<"button">, "aria-label"> & {
  /** 아이콘만 있는 버튼은 화면 읽기 도구가 읽을 이름이 꼭 있어야 한다. */
  "aria-label": string;
};

export function IconButton({
  className,
  type = "button",
  ...props
}: IconButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        "ogu-pressable rounded-control text-fg hover:bg-surface-sunken disabled:text-fg-subtle inline-flex size-11 shrink-0 items-center justify-center disabled:cursor-not-allowed",
        className,
      )}
      {...props}
    />
  );
}
