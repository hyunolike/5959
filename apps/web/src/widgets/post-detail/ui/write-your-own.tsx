import Link from "next/link";

import { Button } from "@/shared/ui";

/**
 * 남의 글을 읽고 "나도 그런 적 있는데" 하는 순간에 쓰기로 잇는 길(016 US4). 글과 댓글 다음,
 * 비슷한 고민 추천 앞에 둔다. 내 글에는 두지 않는다.
 */
export function WriteYourOwn() {
  return (
    <aside
      aria-label="나도 털어놓기"
      className="rounded-surface bg-surface-sunken flex flex-wrap items-center justify-between gap-3 px-4 py-3.5"
    >
      <p className="text-body-sm text-fg break-keep">
        비슷한 마음이 있다면 털어놓아 보세요.
      </p>
      <Button
        asChild
        variant="secondary"
        size="sm"
        className="bg-surface hover:bg-line shrink-0"
        data-umami-event="detail-write-own"
      >
        <Link href="/write">나도 털어놓기</Link>
      </Button>
    </aside>
  );
}
