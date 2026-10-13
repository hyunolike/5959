"use client";

import { CircleNotch, LockSimple, type Icon } from "@phosphor-icons/react";
import Link, { useLinkStatus } from "next/link";
import { useId, type MouseEventHandler } from "react";

import { cn } from "@/shared/lib";

export function NavigationItem({
  href,
  label,
  shortLabel,
  icon,
  current,
  mobile = false,
  primary = false,
  locked = false,
  onClick,
  prefetch,
}: {
  href: string;
  label: string;
  shortLabel: string;
  icon: Icon;
  current?: "page" | "location";
  mobile?: boolean;
  /** 좁은 화면의 메뉴에서 주요 동작 색으로 채워 보이는 메뉴. 넓은 화면에서는 다른 메뉴와 같다. */
  primary?: boolean;
  /** 익명에게 로그인 안내를 먼저 여는 메뉴. 이름은 그대로 두고 설명으로만 알린다. */
  locked?: boolean;
  onClick?: MouseEventHandler<HTMLAnchorElement>;
  prefetch?: boolean;
}) {
  const lockedId = useId();
  const filled = mobile && primary;
  return (
    <Link
      href={href}
      onClick={onClick}
      prefetch={prefetch}
      aria-label={label}
      aria-current={current}
      aria-describedby={locked ? lockedId : undefined}
      className={cn(
        "ogu-pressable group rounded-control relative",
        mobile ? "min-h-16 min-w-0" : "min-h-12",
        mobile && (filled ? "flex-[1.4]" : "flex-1"),
        filled
          ? "bg-action text-on-action hover:bg-action-hover"
          : current
            ? "text-fg"
            : "text-fg-subtle hover:bg-surface-sunken active:bg-line",
      )}
    >
      <NavigationContents
        label={label}
        shortLabel={shortLabel}
        icon={icon}
        selected={!!current}
        mobile={mobile}
        filled={filled}
        locked={locked}
      />
      {locked ? (
        <span id={lockedId} className="sr-only">
          로그인 후 이용할 수 있어요
        </span>
      ) : null}
    </Link>
  );
}

/** Next의 실제 이동 대기만 보여준다. prefetched/동일 경로는 가짜 로딩을 만들지 않는다. */
function NavigationContents({
  label,
  shortLabel,
  icon: NavIcon,
  selected,
  mobile,
  filled,
  locked,
}: {
  label: string;
  shortLabel: string;
  icon: Icon;
  selected: boolean;
  mobile: boolean;
  filled: boolean;
  locked: boolean;
}) {
  const { pending } = useLinkStatus();
  const weight = selected ? "fill" : "regular";
  return (
    <span
      data-navigation-pending={pending}
      className={cn(
        "relative flex h-full w-full",
        mobile
          ? "flex-col items-center justify-center py-1"
          : "items-center gap-3 px-3 py-2",
        selected && !mobile && "rounded-control bg-surface-sunken",
      )}
    >
      <span
        aria-busy={pending}
        className={cn(
          "flex items-center",
          mobile ? "flex-col gap-0.5" : "gap-3",
        )}
      >
        <span className="relative flex size-8 shrink-0 items-center justify-center">
          {/* 선택은 채운 아이콘으로, 이동 대기는 돌아가는 표시로 구분한다. */}
          <NavIcon
            aria-hidden
            size={24}
            weight={weight}
            data-icon-weight={weight}
            className={cn(pending && "opacity-0")}
          />
          {pending ? (
            <CircleNotch
              size={20}
              aria-hidden
              className="absolute motion-safe:animate-spin"
            />
          ) : null}
          {locked && !pending ? (
            <span
              aria-hidden
              data-navigation-lock
              className={cn(
                "bg-surface absolute -top-0.5 -right-1 flex size-4 items-center justify-center rounded-full",
                filled ? "text-fg" : "text-fg-subtle",
              )}
            >
              <LockSimple size={11} weight="fill" />
            </span>
          ) : null}
        </span>
        <span
          className={cn(
            "relative whitespace-nowrap",
            mobile ? "text-caption" : "text-body-sm",
            selected || filled ? "font-bold" : "font-medium",
          )}
        >
          {mobile ? shortLabel : label}
          {!mobile && pending ? (
            <span
              aria-hidden
              className="text-fg-subtle absolute top-full left-0 text-[10px] leading-3"
            >
              이동 중
            </span>
          ) : null}
        </span>
      </span>
      {mobile ? (
        <span
          aria-hidden
          className={cn(
            "h-3 text-[10px] leading-3",
            filled ? "text-on-action" : "text-fg-subtle",
          )}
        >
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
