import Image from "next/image";
import { cn } from "@/shared/lib";

/** 로고: 오구의 얼굴과 서비스 이름. 이름이 글자로 있으므로 그림은 낭독하지 않는다. */
export function BrandMark({ className }: { className?: string }) {
  return (
    <span
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
      <span>오구오구</span>
    </span>
  );
}
