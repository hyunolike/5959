"use client";

import { m } from "framer-motion";
import { LoaderCircle, type LucideIcon } from "lucide-react";
import Link, { useLinkStatus } from "next/link";
import { cn, useReducedMotionPreference } from "@/shared/lib";

export function NavigationItem({
  href,
  label,
  shortLabel,
  icon,
  current,
  mobile = false,
}: {
  href: string;
  label: string;
  shortLabel: string;
  icon: LucideIcon;
  current?: "page" | "location";
  mobile?: boolean;
}) {
  return (
    <Link
      href={href}
      aria-label={label}
      aria-current={current}
      className={cn(
        "ogu-pressable group relative rounded-xl focus-visible:outline-offset-2",
        mobile ? "min-h-16 min-w-0 flex-1" : "min-h-12",
        current
          ? "text-brand-700"
          : "hover:bg-brand-50 active:bg-brand-100 text-neutral-600",
      )}
    >
      <NavigationContents
        label={label}
        shortLabel={shortLabel}
        icon={icon}
        selected={!!current}
        mobile={mobile}
      />
    </Link>
  );
}

/** Next의 실제 이동 대기만 보여준다. prefetched/동일 경로는 가짜 로딩을 만들지 않는다. */
function NavigationContents({
  label,
  shortLabel,
  icon: Icon,
  selected,
  mobile,
}: {
  label: string;
  shortLabel: string;
  icon: LucideIcon;
  selected: boolean;
  mobile: boolean;
}) {
  const { pending } = useLinkStatus();
  const reduce = useReducedMotionPreference();
  return (
    <span
      data-navigation-pending={pending}
      className={cn(
        "relative flex h-full w-full",
        mobile
          ? "flex-col items-center justify-center py-1"
          : "items-center gap-3 px-3 py-2",
        selected && !mobile && "bg-brand-50 rounded-xl",
      )}
    >
      <span
        aria-busy={pending}
        className={cn("flex items-center", mobile ? "flex-col gap-1" : "gap-3")}
      >
        <span
          className={cn(
            "relative flex shrink-0 items-center justify-center rounded-2xl",
            mobile ? "h-8 w-10" : "size-8",
            pending && "bg-brand-100 text-brand-700",
          )}
        >
          <m.span
            aria-hidden
            initial={false}
            animate={{ opacity: selected ? 1 : 0, scale: selected ? 1 : 0.9 }}
            transition={{ duration: reduce ? 0 : 0.15 }}
            className={cn(
              "absolute inset-0 rounded-2xl",
              mobile ? "bg-brand-600" : "bg-brand-100",
            )}
          />
          <Icon
            aria-hidden
            size={mobile ? 21 : 20}
            strokeWidth={selected ? 2.4 : 1.8}
            className={cn(
              "relative",
              selected && mobile && "text-white",
              pending && "opacity-0",
            )}
          />
          {pending ? (
            <LoaderCircle
              size={18}
              aria-hidden
              className={cn(
                "absolute motion-safe:animate-spin",
                selected && mobile ? "text-white" : "text-brand-700",
              )}
            />
          ) : null}
        </span>
        <span
          className={cn(
            "relative inline-flex items-center gap-1 whitespace-nowrap",
            mobile ? "text-xs leading-4" : "text-sm",
            selected ? "font-bold" : "font-medium",
          )}
        >
          {selected ? (
            <span
              aria-hidden
              data-current-mark
              className="bg-brand-600 size-1.5 rounded-full"
            />
          ) : null}
          {mobile ? shortLabel : label}
          {!mobile && pending ? (
            <span
              aria-hidden
              className="text-brand-700 absolute top-full left-0 text-[10px] leading-3"
            >
              이동 중
            </span>
          ) : null}
        </span>
      </span>
      {mobile ? (
        <span aria-hidden className="text-brand-700 h-3 text-[10px] leading-3">
          {pending ? "이동 중" : null}
        </span>
      ) : null}
      {/* busy 영역 밖에 두어 이동 시작 안내가 busy로 인해 읽히지 않는 일을 피한다. */}
      <span aria-live="polite" className="sr-only">
        {pending ? `${label} 이동 중` : ""}
      </span>
    </span>
  );
}
