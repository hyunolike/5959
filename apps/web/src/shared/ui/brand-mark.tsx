import Image from "next/image";
import { cn } from "@/shared/lib";

/** 얼굴과 자체 레터링을 하나의 이름으로 읽는다. 그림 각각은 장식으로 둔다. */
export function BrandMark({ className }: { className?: string }) {
  return (
    <span
      role="img"
      aria-label="오구오구"
      className={cn(
        "text-title text-fg inline-flex items-center gap-2 leading-none font-extrabold tracking-tight",
        className,
      )}
    >
      <Image
        src="/brand/ogu-symbol.webp"
        alt=""
        width={36}
        height={36}
        className="size-9 shrink-0"
      />
      <Image
        src="/brand/ogu-wordmark.svg"
        alt=""
        width={128}
        height={32}
        unoptimized
        className="h-6 w-24 shrink-0 sm:h-8 sm:w-32"
      />
      <span className="sr-only">오구오구</span>
    </span>
  );
}
