import Link from "next/link";
import type { ReactNode } from "react";

import { Card } from "@/shared/ui";

import type { FeedItem } from "../model/types";

type MonsterView = NonNullable<FeedItem["monster"]>;

/**
 * 피드의 글 하나(US2-AC1). 직군과 경력 라벨, 몬스터 그림은 다른 엔티티(member, monster)의
 * 것이라 위젯이 넘긴다. 몬스터가 아직 없으면(`monster == null`) 분석 중이다.
 */
export function PostCard({
  item,
  authorMeta,
  renderMonster,
}: {
  item: FeedItem;
  authorMeta: string;
  renderMonster: (monster: MonsterView) => ReactNode;
}) {
  return (
    <Link
      href={`/post/${item.postId}`}
      className="block rounded-lg focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
    >
      <Card className="flex flex-col gap-4 transition-colors hover:border-neutral-300">
        <div className="flex items-baseline gap-2">
          <span className="font-medium text-neutral-900">
            {item.author.nickname}
          </span>
          <span className="text-xs text-neutral-500">{authorMeta}</span>
        </div>
        <p className="text-sm break-words whitespace-pre-line text-neutral-800">
          {item.contentPreview}
        </p>
        <div aria-label="몬스터">
          {item.monster ? (
            renderMonster(item.monster)
          ) : (
            <p className="text-sm text-neutral-500">분석 중</p>
          )}
        </div>
        <div className="flex items-center gap-3 text-xs text-neutral-600 tabular-nums">
          <span>공감 {item.likeCount}</span>
          {item.likedByMe ? (
            <span className="rounded-full bg-red-50 px-2 py-0.5 text-red-600">
              내가 공감함
            </span>
          ) : null}
          <span>댓글 {item.commentCount}</span>
        </div>
      </Card>
    </Link>
  );
}
