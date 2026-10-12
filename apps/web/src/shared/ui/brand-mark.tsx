import { HeartHandshake } from "lucide-react";
import { cn } from "@/shared/lib";

export function BrandMark({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2.5 text-2xl font-bold tracking-[-0.07em] text-neutral-900",
        className,
      )}
    >
      <span className="bg-brand-600 flex size-10 items-center justify-center rounded-[14px] text-white">
        <HeartHandshake aria-hidden size={24} strokeWidth={1.8} />
      </span>
      오구오구
    </span>
  );
}
