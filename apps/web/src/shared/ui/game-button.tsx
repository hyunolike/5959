import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

/**
 * 글 상세의 공감과 레이드의 "공격하기"에만 쓴다(스펙 "게임 요소를 쓰는 곳").
 * 누르면 그림자만큼 내려간다. 움직임 줄이기가 켜져 있으면 내려가지 않는다.
 * 켜고 끄는 버튼이면 `aria-pressed`를 주면 눌린 채로 내려가 있다.
 * 요청이 가는 동안(`disabled`와 `aria-busy`)에는 잠긴 회색으로 바꾸지 않아 깜빡이지 않는다.
 */
export function GameButton({
  className,
  type = "button",
  ...props
}: ComponentProps<"button">) {
  return (
    <button
      type={type}
      className={cn(
        "rounded-surface border-game-line bg-game text-title text-fg shadow-game disabled:border-line-strong disabled:bg-surface-sunken disabled:text-fg-subtle disabled:aria-busy:border-game-line disabled:aria-busy:bg-game disabled:aria-busy:text-fg disabled:aria-busy:not-aria-pressed:shadow-game inline-flex min-h-14 w-full items-center justify-center gap-2 border-2 px-6 text-center font-extrabold break-keep transition-[transform,box-shadow] duration-[120ms] active:translate-y-1 active:shadow-none disabled:cursor-not-allowed disabled:shadow-none disabled:active:translate-y-0 aria-pressed:translate-y-1 aria-pressed:shadow-none motion-reduce:transition-none motion-reduce:active:translate-y-0 motion-reduce:aria-pressed:translate-y-0",
        className,
      )}
      {...props}
    />
  );
}
