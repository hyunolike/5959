import { cn } from "@/shared/lib";

import { EMOTION_LABELS, type EmotionType } from "../model/types";

/** 점과 감정 이름. 색은 `data-emotion`이 여는 감정 색 범위에서 온다. */
export function EmotionTag({
  emotion,
  className,
}: {
  emotion: EmotionType;
  className?: string;
}) {
  return (
    <span
      data-emotion={emotion}
      className={cn(
        "bg-emotion-tint text-caption text-emotion-fg inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full px-2.5 font-bold",
        className,
      )}
    >
      <span aria-hidden className="bg-emotion size-1.5 rounded-full" />
      {EMOTION_LABELS[emotion]}
    </span>
  );
}
