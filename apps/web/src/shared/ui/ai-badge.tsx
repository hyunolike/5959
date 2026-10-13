import Link from "next/link";

import { cn } from "@/shared/lib";

/** AI 친구 안내 화면(009 US2-AC4). */
export const AI_FRIENDS_PATH = "/ai-friends";

/**
 * AI 친구의 닉네임 옆에 붙는 표시(009 US2-AC1). 색이나 그림이 아니라 글자로 알리고,
 * 누르면 AI 친구가 무엇인지 알려 주는 화면으로 간다. 감정 색과 게임 요소는 쓰지 않는다.
 */
export function AiBadge({ className }: { className?: string }) {
  return (
    <Link
      href={AI_FRIENDS_PATH}
      className={cn(
        "border-line-strong text-caption text-fg-muted hover:bg-surface-sunken relative inline-flex shrink-0 items-center rounded-full border px-2 py-0.5 font-bold",
        // 글자 높이는 20px이라 누르는 영역만 위아래로 12px씩 넓힌다.
        "after:absolute after:-inset-x-1 after:-inset-y-3 after:content-['']",
        className,
      )}
    >
      AI 친구
    </Link>
  );
}
