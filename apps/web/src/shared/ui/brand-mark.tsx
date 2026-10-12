import Image from "next/image";
import { cn } from "@/shared/lib";

export function BrandMark({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "text-fg inline-flex items-center gap-2 text-[26px] leading-none font-bold tracking-[-0.055em]",
        className,
      )}
    >
      <span
        aria-hidden
        className="relative size-11 shrink-0 overflow-hidden rounded-full"
      >
        <Image
          src="/brand/ogu-companion.png"
          alt=""
          width={72}
          height={59}
          sizes="72px"
          className="absolute top-0 left-1/2 h-auto w-[72px] max-w-none -translate-x-1/2"
        />
      </span>
      <span>5959</span>
    </span>
  );
}
