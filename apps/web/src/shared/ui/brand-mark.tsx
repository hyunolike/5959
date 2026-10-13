import Image from "next/image";

import { cn } from "@/shared/lib";

/**
 * 머리말의 로고. 오구 얼굴 심볼과 "오구오구" 글자가 든 가로형 SVG 하나다(원본 304×64).
 * 이름은 감싼 요소가 한 번 읽히고 그림은 장식으로 둔다.
 */
export function BrandMark({ className }: { className?: string }) {
  return (
    <span
      role="img"
      aria-label="오구오구"
      className={cn("inline-flex items-center", className)}
    >
      <Image
        src="/brand/ogu-logo.svg"
        alt=""
        width={304}
        height={64}
        unoptimized
        priority
        className="h-7 w-auto shrink-0 sm:h-8"
      />
      <span className="sr-only">오구오구</span>
    </span>
  );
}
