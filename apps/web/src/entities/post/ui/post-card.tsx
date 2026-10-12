import { Heart, MessageCircle } from "lucide-react";

import Link from "next/link";
import type { ReactNode } from "react";

import { formatDate } from "@/shared/lib";

import type { FeedItem } from "../model/types";

type MonsterView = NonNullable<FeedItem["monster"]>;

/** 숨겨진 내 글과 댓글에 붙는 표시(005 US1-AC3). 내 글 목록과 글 상세가 같은 말을 쓴다. */
export const HIDDEN_FROM_OTHERS_LABEL = "다른 회원에게 보이지 않아요";

/**
 * 피드의 글 하나(US2-AC1). 직군과 경력 라벨, 몬스터 그림은 다른 엔티티(member, monster)의
 * 것이라 위젯이 넘긴다. 몬스터가 아직 없으면(`monster == null`) 분석 중이다.
 * 피드와 내 활동 목록에서 작성 시각도 보인다(`showCreatedAt`, 004 US3-AC1).
 */
export function PostCard({
  item,
  authorMeta,
  renderMonster,
  showCreatedAt = false,
}: {
  item: FeedItem;
  authorMeta: string;
  renderMonster: (monster: MonsterView) => ReactNode;
  showCreatedAt?: boolean;
}) {
  return (
    <Link
      href={`/post/${item.postId}`}
      className="group focus-visible:ring-brand-600 block border-b border-neutral-200 px-1 py-6 transition-colors hover:bg-neutral-50 focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
    >
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-xs text-neutral-500">
        <span className="font-bold break-words text-neutral-700">
          {item.author.nickname}
        </span>
        <span>{authorMeta}</span>
        {showCreatedAt ? (
          <time dateTime={item.createdAt} className="ml-auto tabular-nums">
            {formatDate(item.createdAt, "YYYY.MM.DD HH:mm")}
          </time>
        ) : null}
      </div>
      <p className="mt-3 text-[15px] leading-7 break-words whitespace-pre-line text-neutral-900 sm:text-base">
        {item.contentPreview}
      </p>
      <div aria-label="몬스터" className="mt-4 max-w-[260px]">
        {item.monster ? (
          renderMonster(item.monster)
        ) : (
          <p className="text-sm text-neutral-500">분석 중</p>
        )}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-neutral-500 tabular-nums">
        <span className="inline-flex items-center gap-1.5">
          <Heart
            size={15}
            aria-hidden
            className={item.likedByMe ? "fill-brand-100 text-brand-700" : ""}
          />
          공감 {item.likeCount}
        </span>
        {item.likedByMe ? (
          <span className="text-brand-700">내가 공감함</span>
        ) : null}
        <span className="inline-flex items-center gap-1.5">
          <MessageCircle size={15} aria-hidden />
          댓글 {item.commentCount}
        </span>
        {item.hidden ? (
          <span className="rounded-full bg-amber-50 px-2 py-0.5 text-amber-800">
            {HIDDEN_FROM_OTHERS_LABEL}
          </span>
        ) : null}
      </div>
    </Link>
  );
}
