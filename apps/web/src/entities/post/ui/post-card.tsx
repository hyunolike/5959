import { Heart, MessageCircle } from "lucide-react";

import Link from "next/link";
import type { ReactNode } from "react";

import { formatDate } from "@/shared/lib";
import { Card } from "@/shared/ui";

import type { FeedItem } from "../model/types";

type MonsterView = NonNullable<FeedItem["monster"]>;

/** 숨겨진 내 글과 댓글에 붙는 표시(005 US1-AC3). 내 글 목록과 글 상세가 같은 말을 쓴다. */
export const HIDDEN_FROM_OTHERS_LABEL = "다른 회원에게 보이지 않아요";

/**
 * 피드의 글 하나(US2-AC1). 직군과 경력 라벨, 몬스터 그림은 다른 엔티티(member, monster)의
 * 것이라 위젯이 넘긴다. 몬스터가 아직 없으면(`monster == null`) 분석 중이다.
 * 마이페이지는 작성 시각도 보인다(`showCreatedAt`, 004 US3-AC1).
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
      className="group focus-visible:ring-brand-600 block rounded-2xl focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
    >
      <Card className="hover:border-brand-200 flex flex-col gap-4 transition-[border-color,box-shadow] hover:shadow-sm">
        <div className="flex items-center gap-3">
          <span
            aria-hidden
            className="bg-brand-50 text-brand-700 flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-sm font-bold"
          >
            {Array.from(item.author.nickname)[0]}
          </span>
          <div className="min-w-0">
            <span className="font-bold text-neutral-900">
              {item.author.nickname}
            </span>
            <span className="mt-0.5 block text-xs text-neutral-500">
              {authorMeta}
            </span>
          </div>
        </div>
        <p className="text-sm leading-7 break-words whitespace-pre-line text-neutral-800">
          {item.contentPreview}
        </p>
        <div aria-label="몬스터">
          {item.monster ? (
            renderMonster(item.monster)
          ) : (
            <p className="text-sm text-neutral-500">분석 중</p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-3 border-t border-neutral-100 pt-4 text-xs text-neutral-600 tabular-nums">
          <span className="inline-flex items-center gap-1.5">
            <Heart
              size={16}
              aria-hidden
              className={item.likedByMe ? "fill-brand-100 text-brand-700" : ""}
            />
            공감 {item.likeCount}
          </span>
          {item.likedByMe ? (
            <span className="bg-brand-50 text-brand-700 rounded-full px-2 py-0.5">
              내가 공감함
            </span>
          ) : null}
          <span className="inline-flex items-center gap-1.5">
            <MessageCircle size={16} aria-hidden />
            댓글 {item.commentCount}
          </span>
          {item.hidden ? (
            <span className="rounded-full bg-amber-50 px-2 py-0.5 text-amber-800">
              {HIDDEN_FROM_OTHERS_LABEL}
            </span>
          ) : null}
          {showCreatedAt ? (
            <time
              dateTime={item.createdAt}
              className="ml-auto text-neutral-500"
            >
              {formatDate(item.createdAt, "YYYY.MM.DD HH:mm")}
            </time>
          ) : null}
        </div>
      </Card>
    </Link>
  );
}
