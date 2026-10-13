import { CaretLeft } from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";

import { cn } from "@/shared/lib";

/**
 * 안쪽 화면의 머리말(스펙 "공용 부품"). 왼쪽에 뒤로 가기, 가운데에 화면의 제목(`h1`)이 온다.
 * 오른쪽 칸은 왼쪽과 같은 너비로 비워 제목이 가운데에 오게 한다.
 * 글 상세처럼 보이는 제목이 없는 화면은 `hideTitle`로 제목을 읽히기만 하게 둔다.
 */
export function PageHeader({
  title,
  backHref,
  backLabel,
  hideTitle = false,
  className,
}: {
  title: string;
  backHref: string;
  /** 뒤로 가기 링크의 이름. 어디로 돌아가는지 적는다. */
  backLabel: string;
  hideTitle?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "grid h-14 w-full grid-cols-[2.75rem_1fr_2.75rem] items-center",
        className,
      )}
    >
      <Link
        href={backHref}
        aria-label={backLabel}
        className="ogu-pressable rounded-control-sm text-fg hover:bg-surface-sunken -ml-2.5 inline-flex size-11 items-center justify-center"
      >
        <CaretLeft size={24} weight="bold" aria-hidden />
      </Link>
      <h1
        className={
          hideTitle
            ? "sr-only"
            : "text-body-lg text-fg truncate text-center font-bold"
        }
      >
        {title}
      </h1>
      <span aria-hidden />
    </div>
  );
}
