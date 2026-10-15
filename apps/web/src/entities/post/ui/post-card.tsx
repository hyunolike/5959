import { ChatCircle, Heart } from "@phosphor-icons/react/dist/ssr";
import Link from "next/link";
import type { ReactNode } from "react";

import { formatDate, formatRelativeTime } from "@/shared/lib";

import type { FeedItem } from "../model/types";

type MonsterView = NonNullable<FeedItem["monster"]>;

/** 숨겨진 내 글과 댓글에 붙는 표시(005 US1-AC3). 내 글 목록과 글 상세가 같은 말을 쓴다. */
export const HIDDEN_FROM_OTHERS_LABEL = "다른 회원에게 보이지 않아요";

/**
 * 피드의 글 하나(US2-AC1). 감정 태그와 몬스터 그림은 다른 엔티티(monster)의 것이라 위젯이 넘긴다.
 * 몬스터가 아직 없으면(`monster == null`) 분석 중이고 감정 태그도 없다.
 * 읽는 순서는 감정과 누가 → 무슨 고민 → 몬스터와 HP → 공감과 댓글 수다.
 * 작성 시각(`showCreatedAt`, 004 US3-AC1)은 "12분 전"처럼 쓰고, 정확한 시각은 `datetime`과 `title`에 둔다.
 */
export function PostCard({
  item,
  authorMeta,
  renderEmotion,
  renderMonster,
  showCreatedAt = false,
}: {
  item: FeedItem;
  authorMeta: string;
  renderEmotion: (monster: MonsterView) => ReactNode;
  /** 두 번째 인자는 글 번호다. 불안 몬스터의 모습을 고르는 데 쓴다(017). */
  renderMonster: (monster: MonsterView, postId: number) => ReactNode;
  showCreatedAt?: boolean;
}) {
  const relativeTime = formatRelativeTime(item.createdAt);

  return (
    <Link
      href={`/post/${item.postId}`}
      className="hover:bg-surface-sunken active:bg-line block px-5 py-[18px] transition-colors focus-visible:-outline-offset-2"
    >
      <div className="text-label text-fg-subtle flex flex-wrap items-center gap-x-2 gap-y-1">
        {item.monster ? renderEmotion(item.monster) : null}
        <span className="text-fg font-bold break-words">
          {item.author.nickname}
        </span>
        <span>{authorMeta}</span>
        {showCreatedAt ? (
          <time
            dateTime={item.createdAt}
            title={
              relativeTime
                ? formatDate(item.createdAt, "YYYY.MM.DD HH:mm")
                : undefined
            }
            className="ml-auto tabular-nums"
          >
            {relativeTime}
          </time>
        ) : null}
      </div>
      <p className="text-body text-fg mt-2.5 line-clamp-4 break-words whitespace-pre-line">
        {item.contentPreview}
      </p>
      <div className="mt-3.5 flex flex-wrap items-center gap-x-3 gap-y-2">
        <div className="min-w-0 flex-auto">
          {item.monster ? (
            renderMonster(item.monster, item.postId)
          ) : (
            <p className="text-label text-fg-subtle">분석 중</p>
          )}
        </div>
        <div className="text-label text-fg-muted ml-auto flex shrink-0 items-center gap-3.5 font-medium tabular-nums">
          <span className="inline-flex items-center gap-1">
            <Heart
              size={18}
              weight={item.likedByMe ? "fill" : "regular"}
              aria-hidden
            />
            공감 {item.likeCount}
          </span>
          {item.likedByMe ? <span className="sr-only">내가 공감함</span> : null}
          <span className="inline-flex items-center gap-1">
            <ChatCircle size={18} aria-hidden />
            댓글 {item.commentCount}
          </span>
        </div>
      </div>
      {item.hidden ? (
        <p className="bg-warning-tint text-caption text-warning mt-3 inline-flex rounded-full px-2 py-0.5 font-medium">
          {HIDDEN_FROM_OTHERS_LABEL}
        </p>
      ) : null}
    </Link>
  );
}
